package com.example.passwordmanager.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.sync.NetworkManager;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.firebase.AuthRepository;
import com.example.passwordmanager.firebase.IAuthRepository;
import com.example.passwordmanager.firebase.ISecurityRepository;
import com.example.passwordmanager.firebase.SecurityRepository;
import com.example.passwordmanager.security.AuthAttemptManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.security.VaultKeyStore;

import com.example.passwordmanager.usecase.ChangeMasterPasswordUseCase;
import com.example.passwordmanager.usecase.LoginUseCase;
import com.example.passwordmanager.usecase.RegisterUseCase;
import com.example.passwordmanager.usecase.UnlockWithMasterPasswordUseCase;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;


/**
 * ViewModel xử lý xác thực tài khoản (Master Password), dùng chung cho cả 2 màn:
 *  - LoginActivity ở chế độ thường (email + Master Password -> FirebaseAuth).
 *  - LoginActivity ở chế độ "master_password_unlock" (chỉ Master Password,
 *    xác thực lại với Vault Key local, không cần mạng).
 *
 * Quản lý Đăng nhập, Đăng ký, Tải Salt từ Firestore, băm khóa AES,
 * khởi tạo CryptoSession/VaultKeyStore, và đếm số lần nhập sai Master Password
 * (AuthAttemptManager) để tự động xóa Vault local khi bị dò quét (brute-force).
 */
public class AuthViewModel extends AndroidViewModel {
    private final IAuthRepository authRepository;
    private final ISecurityRepository securityRepository;
    private final VaultKeyStore vaultKeyStore;
    private final AuthAttemptManager authAttemptManager;
    private final LocalAuthManager localAuthManager;
    private final ExecutorService executor;

    private final MutableLiveData<Resource<FirebaseUser>> loginResult =
            new MutableLiveData<>();

    private final MutableLiveData<Resource<FirebaseUser>> registerResult =
            new MutableLiveData<>();

    private final MutableLiveData<Resource<Boolean>> masterPasswordUnlockResult =
            new MutableLiveData<>();

    /// Phát ra UID cần xóa Vault local khi Master Password sai quá số lần cho phép.
    private final MutableLiveData<String> vaultWipeEvent
            = new MutableLiveData<>();

    private final MutableLiveData<Resource<FirebaseUser>> updateProfileResult =
            new MutableLiveData<>();

    private final MutableLiveData<Resource<Void>> deleteAccountResult =
            new MutableLiveData<>();

    private final MutableLiveData<Resource<Void>> changePasswordResult =
            new MutableLiveData<>();

    /**
     * Dependency được inject từ AuthViewModelFactory (qua AppContainer)
     * thay vì AuthViewModel tự "new" từng Repository/Manager như trước đây.
     * Nhờ vậy có thể thay bằng bản giả (mock/fake) khi viết unit test.
     */
    public AuthViewModel(
            @NonNull Application application,
            IAuthRepository authRepository,
            ISecurityRepository securityRepository,
            VaultKeyStore vaultKeyStore,
            AuthAttemptManager authAttemptManager,
            LocalAuthManager localAuthManager,
            ExecutorService executor
    ) {
        super(application);

        this.authRepository = authRepository;
        this.securityRepository = securityRepository;
        this.vaultKeyStore = vaultKeyStore;
        this.authAttemptManager = authAttemptManager;
        this.localAuthManager = localAuthManager;
        this.executor = executor;
    }

    public FirebaseUser getUser() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    /// Đăng xuất tài khoản hiện tại. View gọi qua đây thay vì tự giữ AuthRepository riêng.
    public void logout() {
        authRepository.logout();
        CryptoSession.clear();
        AppContainer.getInstance(getApplication())
                .clearCredentialRepository();
    }

    // =========================================================
    // LiveData
    // =========================================================

    /// Trả về kết quả của quá trình đăng nhập.
    public LiveData<Resource<FirebaseUser>> getLoginResult() {
        return loginResult;
    }

    /// Trả về kết quả của quá trình đăng ký.
    public LiveData<Resource<FirebaseUser>> getRegisterResult() {
        return registerResult;
    }

    /// Trả về kết quả xác thực Master Password khi mở khóa local (offline).
    public LiveData<Resource<Boolean>> getMasterPasswordUnlockResult() {
        return masterPasswordUnlockResult;
    }

    /// Phát ra khi Vault local vừa bị xóa do sai Master Password quá nhiều lần.
    public LiveData<String> getVaultWipeEvent() {
        return vaultWipeEvent;
    }

    public LiveData<Resource<FirebaseUser>> getUpdateProfileResult() {
        return updateProfileResult;
    }

    public LiveData<Resource<Void>> getDeleteAccountResult() {
        return deleteAccountResult;
    }

    public LiveData<Resource<Void>> getChangePasswordResult() {
        return changePasswordResult;
    }

    // =========================================================
    // LOGIN (Email + Master Password -> FirebaseAuth)
    // =========================================================

    /**
     * Thực hiện đăng nhập tài khoản bằng Email và Mật khẩu trên Firebase Server.
     * Validate định dạng Email & Mật khẩu trước khi gửi request.
     */
    public void login(String email, String password) {
        new LoginUseCase(
                getApplication(),
                authRepository,
                securityRepository,
                vaultKeyStore,
                authAttemptManager,
                localAuthManager,
                executor
        ).execute(email, password, new LoginUseCase.Callback() {
            @Override
            public void onLoading() {
                loginResult.postValue(Resource.loading());
            }

            @Override
            public void onSuccess(FirebaseUser user) {
                loginResult.postValue(Resource.success(user));
            }

            @Override
            public void onError(String message) {
                loginResult.postValue(Resource.error(message));
            }

            @Override
            public void onVaultWipeRequired(String uid) {
                vaultWipeEvent.postValue(uid);
            }
        });
    }

    // =========================================================
    // MASTER PASSWORD UNLOCK (offline, dùng Vault Key local)
    // =========================================================

    /**
     * Mở khóa Vault bằng Master Password và Salt đã lưu local (không cần mạng).
     * Dùng khi Local Auth (biometric/PIN) bị tắt hoặc đã sai quá nhiều lần.
     * Nếu mật khẩu đúng, khôi phục CryptoSession từ khóa vừa tạo.
     * Nếu sai quá số lần cho phép (AuthAttemptManager), toàn bộ dữ liệu
     * Vault local sẽ bị xóa để chống dò quét mật khẩu (brute-force).
     */
    public void unlockWithMasterPassword(String uid, String masterPassword) {

        new UnlockWithMasterPasswordUseCase(
                vaultKeyStore,
                authAttemptManager,
                localAuthManager,
                executor
        ).execute(uid, masterPassword, new UnlockWithMasterPasswordUseCase.Callback() {
            @Override
            public void onSuccess() {
                masterPasswordUnlockResult.postValue(Resource.success(true));
            }

            @Override
            public void onError(String message) {
                masterPasswordUnlockResult.postValue(Resource.error(message));
            }

            @Override
            public void onVaultWipeRequired(String uid) {
                vaultWipeEvent.postValue(uid);
            }
        });
    }

    /**
     * Ghi nhận 1 lần đăng nhập (Master Password) sai và gắn kèm số lần thử
     * còn lại vào thông báo lỗi, dùng chung bộ đếm với màn Master Password
     * fallback (AuthAttemptManager).
     */
    private void handleMasterPasswordFailure(String uid) {
        int attempts = authAttemptManager.recordFailure(uid);
        int remaining = AuthAttemptManager.MAX_ATTEMPTS - attempts;

        if (authAttemptManager.reachedLimit(uid)) {
            authAttemptManager.reset(uid);
            FirebaseAuth.getInstance().signOut();

            masterPasswordUnlockResult.postValue(
                    Resource.error(
                            "Sai Master Password quá nhiều lần. "
                                    + "Đã xóa dữ liệu cục bộ, vui lòng đăng nhập lại."
                    )
            );

            // Việc xóa Vault/PIN/biometric local thuộc domain của SecurityViewModel.
            // AuthViewModel chỉ phát sự kiện, Activity sẽ gọi securityViewModel.wipeLocalVault(uid).
            vaultWipeEvent.postValue(uid);
            return;
        }

        masterPasswordUnlockResult.postValue(
                Resource.error("Master Password không đúng. Còn " + remaining + " lần thử.")
        );
    }

    // =========================================================
    // REGISTER
    // =========================================================

    /**
     * Thực hiện đăng ký tài khoản mới bằng Email và Mật khẩu trên Firebase Server.
     * Kiểm tra độ dài mật khẩu (>= 8 ký tự) và mật khẩu xác nhận khớp nhau.
     */
    public void register(
            String username, String email, String password, String confirmPassword
    ) {
        new RegisterUseCase(
                authRepository,
                securityRepository,
                vaultKeyStore
        ).execute(username, email, password, confirmPassword, new RegisterUseCase.Callback() {
            @Override
            public void onLoading() {
                registerResult.postValue(Resource.loading());
            }

            @Override
            public void onSuccess(FirebaseUser user) {
                registerResult.postValue(Resource.success(user));
            }

            @Override
            public void onError(String message) {
                registerResult.postValue(Resource.error(message));
            }
        });
    }

    // =========================================================
    // UPDATE PROFILE
    // =========================================================

    public void updateDisplayName(String newName) {
        String trimmedName = newName == null ? "" : newName.trim();

        if (trimmedName.isEmpty()) {
            updateProfileResult.setValue(
                    Resource.error("Vui lòng nhập tên hiển thị")
            );

            return;
        }

        if (trimmedName.length() < 2) {
            updateProfileResult.setValue(
                    Resource.error("Tên hiển thị phải từ 2 ký tự trở lên")
            );

            return;
        }

        updateProfileResult.setValue(Resource.loading());

        authRepository.updateDisplayName(
                trimmedName,
                user -> updateProfileResult.postValue(
                        Resource.success(user)
                ),

                message -> updateProfileResult.postValue(
                        Resource.error(message)
                )
        );
    }

    // =========================================================
    // DELETE PROFILE
    // =========================================================

    public void deleteAccount(String password) {
        if (password == null || password.isEmpty()) {
            deleteAccountResult.setValue(
                    Resource.error("Vui lòng nhập mật khẩu xác nhận")
            );
            return;
        }

        if (!NetworkManager.isNetworkConnected(getApplication())) {
            deleteAccountResult.setValue(Resource.error("Không có kết nối mạng"));
            return;
        }

        deleteAccountResult.setValue(Resource.loading());

        authRepository.deleteAccount(
                password,
                user -> {
                    try {
                        String uid = authRepository.getCurrentUser() != null
                                ? authRepository.getCurrentUser().getUid()
                                : null;

                        if (uid != null) {
                            // Xóa dữ liệu Room DB local
                            AppDatabase.getInstance(getApplication())
                                    .credentialDao().deleteAll(uid);

                            // Xóa VaultKeyStore & LocalAuth
                            vaultKeyStore.clear(uid);
                            localAuthManager.clear(uid);
                        }

                        // Xóa CryptoSession trong RAM
                        CryptoSession.clear();

                        deleteAccountResult.postValue(
                                Resource.success(null)
                        );

                    } catch (Exception e) {
                        deleteAccountResult.postValue(
                                Resource.error("Xóa dữ liệu local thất bại: "
                                        + e.getMessage())
                        );
                    }
                },
                message -> deleteAccountResult.postValue(
                        Resource.error(message)
                )
        );
    }

    // =========================================================
    // CHANGE MASTER PASSWORD
    // =========================================================

    public void changeMasterPassword(
            String currentPassword,
            String newPassword,
            String confirmPassword
    ) {
        new ChangeMasterPasswordUseCase(
                getApplication(),
                authRepository,
                securityRepository,
                vaultKeyStore,
                executor
        ).execute(
                currentPassword,
                newPassword,
                confirmPassword,
                new ChangeMasterPasswordUseCase.Callback() {
                    @Override
                    public void onLoading() {
                        changePasswordResult.postValue(Resource.loading());
                    }

                    @Override
                    public void onSuccess() {
                        changePasswordResult.postValue(Resource.success(null));
                    }

                    @Override
                    public void onError(String message) {
                        changePasswordResult.postValue(Resource.error(message));
                    }
                }
        );
    }


    // =========================================================
    // DỌN DẸP LIVEDATA
    // =========================================================

    public void clearLoginResult() {
        loginResult.setValue(null);
    }

    public void clearRegisterResult() {
        registerResult.setValue(null);
    }

    public void clearUpdateProfileResult() {
        updateProfileResult.setValue(null);
    }

    public void clearDeleteAccountResult() {
        deleteAccountResult.setValue(null);
    }

    public void clearChangePasswordResult() {
        changePasswordResult.setValue(null);
    }
}