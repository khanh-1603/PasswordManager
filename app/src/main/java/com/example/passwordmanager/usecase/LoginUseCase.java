package com.example.passwordmanager.usecase;

import android.content.Context;
import android.util.Patterns;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.sync.SyncManager;
import com.example.passwordmanager.firebase.IAuthRepository;
import com.example.passwordmanager.firebase.ISecurityRepository;
import com.example.passwordmanager.security.AuthAttemptManager;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.security.LocalUnlockAttemptHandler;
import com.example.passwordmanager.security.VaultKeyStore;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;

import javax.crypto.SecretKey;

/**
 * Đăng nhập bằng Email/Mật khẩu. Thử "mở khóa nhanh" bằng Vault Key đã lưu
 * local trước (offline) nếu email trùng với tài khoản đã đăng nhập gần nhất;
 * nếu không khớp/không có, mới gọi Firebase Auth qua mạng. Sau khi xác thực
 * xong (bằng đường nào cũng vậy) sẽ khởi tạo CryptoSession và kéo dữ liệu
 * Vault từ Firestore về Room (SyncManager.pullFromFirebase()).
 */
public class LoginUseCase {

    public interface Callback extends LocalUnlockAttemptHandler.Callback{
        void onLoading();
        void onSuccess(FirebaseUser user);
    }

    private final Context appContext;
    private final IAuthRepository authRepository;
    private final ISecurityRepository securityRepository;
    private final VaultKeyStore vaultKeyStore;
    private final AuthAttemptManager authAttemptManager;
    private final LocalAuthManager localAuthManager;
    private final LocalUnlockAttemptHandler attemptHandler;
    private final ExecutorService executor;

    public LoginUseCase(
            Context appContext,
            IAuthRepository authRepository,
            ISecurityRepository securityRepository,
            VaultKeyStore vaultKeyStore,
            AuthAttemptManager authAttemptManager,
            LocalAuthManager localAuthManager,
            ExecutorService executor
    ) {
        this.appContext = appContext.getApplicationContext();
        this.authRepository = authRepository;
        this.securityRepository = securityRepository;
        this.vaultKeyStore = vaultKeyStore;
        this.authAttemptManager = authAttemptManager;
        this.localAuthManager = localAuthManager;
        this.executor = executor;
        this.attemptHandler = new LocalUnlockAttemptHandler(authAttemptManager);
    }

    public void execute(String email, String password, Callback callback) {
        final String safeEmail = email == null ? "" : email.trim();
        final String safePassword = password == null ? "" : password;

        if (safeEmail.isEmpty()) {
            callback.onError("Nhập email");
            return;
        }

        if (safePassword.isEmpty()) {
            callback.onError("Nhập mật khẩu");
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(safeEmail).matches()) {
            callback.onError("Email không hợp lệ");
            return;
        }

        callback.onLoading();

        FirebaseUser currentUser = authRepository.getCurrentUser();

        // Nếu đã có cache user trùng email và có Vault Key -> Thử mở khóa Fast Local
        if (currentUser != null
                && currentUser.getEmail() != null
                && currentUser.getEmail().equalsIgnoreCase(safeEmail)
                && vaultKeyStore.hasVaultKey(currentUser.getUid())) {

            executor.execute(() -> {
                String uid = currentUser.getUid();
                String saltBase64 = vaultKeyStore.loadSalt(uid);

                if (saltBase64 != null) {
                    try {
                        byte[] salt = CryptoManager.decodeSalt(saltBase64);
                        CryptoManager cryptoManager = new CryptoManager(safePassword, salt);
                        SecretKey savedVaultKey = vaultKeyStore.loadVaultKey(uid);

                        if (savedVaultKey != null && CryptoManager.isSameKey(
                                cryptoManager.getSecretKey().getEncoded(),
                                savedVaultKey.getEncoded()
                        )) {
                            authAttemptManager.reset(uid);
                            CryptoSession.start(cryptoManager);

                            // Mở khóa Local thành công -> Báo kết quả về
                            callback.onSuccess(currentUser);
                            return;
                        }

                        // Email khớp acc đang cache nhưng sai mật khẩu -> tính là 1 lần
                        // thử mở khóa local sai, dùng chung ngưỡng với màn Master Password.
                        attemptHandler.handleFailure(uid, new LocalUnlockAttemptHandler.Callback() {
                            @Override public void onError(String message) {
                                callback.onError(message);
                            }
                            @Override public void onVaultWipeRequired(String wipedUid) {
                                callback.onVaultWipeRequired(wipedUid);
                            }
                        });
                        return;

                    } catch (Exception e) {
                        // Lỗi kỹ thuật khi giải mã (không phải do sai mật khẩu) -> thử Firebase online.
                    }
                }

                // Nếu Fast Local thất bại -> Gọi Firebase Auth qua mạng
                performFirebaseAuthLogin(safeEmail, safePassword, callback);
            });

        } else {
            // Đăng nhập qua mạng Firebase Auth
            performFirebaseAuthLogin(safeEmail, safePassword, callback);
        }
    }

    private void performFirebaseAuthLogin(
            String email, String password, Callback callback
    ) {
        authRepository.login(
                email,
                password,
                user -> {
                    if (user == null) {
                        callback.onError("Không thể đăng nhập");
                        return;
                    }

                    authAttemptManager.reset(user.getUid());
                    String uid = user.getUid();
                    loadOrCreateSalt(uid, password, user, callback);
                },
                callback::onError
        );
    }

    private void loadOrCreateSalt(
            String uid, String password, FirebaseUser user, Callback callback
    ) {
        securityRepository.getSecurityData(uid)
                .addOnSuccessListener(document -> {
                    String saltBase64;

                    if (document.exists() && document.getString("salt") != null) {
                        saltBase64 = document.getString("salt");
                        vaultKeyStore.saveSalt(uid, saltBase64);
                        startLoginCryptoSession(user, password, saltBase64, callback);
                        return;

                    } else {
                        saltBase64 = CryptoManager.generateSaltBase64();

                        securityRepository.saveSalt(uid, saltBase64)
                                .addOnSuccessListener(unused -> {
                                    vaultKeyStore.saveSalt(uid, saltBase64);
                                    startLoginCryptoSession(user, password, saltBase64, callback);
                                })
                                .addOnFailureListener(e -> callback.onError(
                                        "Không thể lưu thông tin mã hóa: " + e.getMessage()
                                ));
                        return;
                    }
                })
                .addOnFailureListener(e -> callback.onError(
                        "Không thể lấy thông tin mã hóa: " + e.getMessage()
                ));
    }

    private void startLoginCryptoSession(
            FirebaseUser user, String password, String saltBase64, Callback callback
    ) {
        try {
            byte[] salt = CryptoManager.decodeSalt(saltBase64);
            CryptoManager cryptoManager = new CryptoManager(password, salt);

            CryptoSession.start(cryptoManager);
            vaultKeyStore.saveVaultKey(user.getUid(), cryptoManager.getSecretKey());

            authAttemptManager.reset(user.getUid());
            localAuthManager.resetPinFailedAttempts(user.getUid());

            SyncManager syncManager = new SyncManager(
                    AppDatabase.getInstance(appContext).credentialDao(),
                    executor
                    );
            syncManager.pullFromFirebase();

            callback.onSuccess(user);

        } catch (Exception e) {
            CryptoSession.clear();
            callback.onError("Không thể khởi tạo mã hóa: " + e.getMessage());
        }
    }


}