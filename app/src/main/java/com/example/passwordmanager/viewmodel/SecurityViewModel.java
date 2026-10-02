package com.example.passwordmanager.viewmodel;

import android.app.Application;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.util.Log;
import android.view.autofill.AutofillId;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.passwordmanager.PasswordManagerApplication;
import com.example.passwordmanager.autofill.AutofillResponseHelper;
import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.security.VaultKeyStore;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;


/**
 * Quản lý xác thực CỤC BỘ (Local Authentication) và dữ liệu Vault local.
 * KHÔNG xử lý Master Password / FirebaseAuth — phần đó thuộc về AuthViewModel.
 *
 * Trách nhiệm của SecurityViewModel:
 *  - Quyết định phương thức mở khóa cục bộ phù hợp (Biometric / PIN / Master Password fallback).
 *  - Xử lý kết quả BiometricPrompt.
 *  - Xác thực PIN và khôi phục CryptoSession từ VaultKeyStore.
 *  - Xóa dữ liệu Vault + cấu hình bảo mật local khi cần (wipeLocalVault).
 */
public class SecurityViewModel extends AndroidViewModel {
    public enum UnlockDestination {
        GOTO_LOGIN,
        GOTO_MASTER_PASSWORD,
        PROMPT_BIOMETRIC,
        PROMPT_PIN,
        UNLOCKED
    }

    private static String TAG = "securityViewModel";

    private final MutableLiveData<UnlockDestination> unlockDestination
            = new MutableLiveData<>();
    private final MutableLiveData<Resource<Boolean>> vaultKeyResult
            = new MutableLiveData<>();
    private final MutableLiveData<FillResponse> autofillFillResponseResult
            = new MutableLiveData<>();
    private final MutableLiveData<Boolean> autofillErrorResult
            = new MutableLiveData<>();

    private final LocalAuthManager localAuthManager;
    private final VaultKeyStore vaultKeyStore;
    private final CredentialDao credentialDao;
    private final ExecutorService executor;

    private int biometricFailedAttempts = 0;
    private static final int MAX_BIOMETRIC_ATTEMPTS = 2;

    /**
     * Dependency được inject từ SecurityViewModelFactory (qua AppContainer)
     */
    public SecurityViewModel(
            @NonNull Application application,
            LocalAuthManager localAuthManager,
            VaultKeyStore vaultKeyStore,
            CredentialDao credentialDao,
            ExecutorService executor
    ) {
        super(application);
        this.localAuthManager = localAuthManager;
        this.vaultKeyStore = vaultKeyStore;
        this.credentialDao = credentialDao;
        this.executor = executor;
    }

    public FirebaseUser getUser() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    /// Trả về trạng thái/đích điều hướng sau khi kiểm tra điều kiện mở khóa.
    public LiveData<UnlockDestination> getUnlockDestination() {
        return unlockDestination;
    }

    /// Trả về kết quả kiểm tra Vault Key local.
    public LiveData<Resource<Boolean>> getVaultKeyResult() {
        return vaultKeyResult;
    }

    public LiveData<FillResponse> getAutofillFillResponseResult() {
        return autofillFillResponseResult;
    }

    public LiveData<Boolean> getAutofillErrorResult() {
        return autofillErrorResult;
    }

    // =========================================================
    // LOCAL AUTH
    // =========================================================

    /// Kiểm tra trạng thái bảo mật local và xác định phương thức mở khóa phù hợp.
    public void checkUnlockStatus(String uid) {

        if (uid == null || uid.isEmpty()) {
            unlockDestination.setValue(UnlockDestination.GOTO_LOGIN);
            return;
        }

        // Kiểm tra người dùng ĐÃ BẬT CÔNG TẮC trong Cài đặt hay chưa
        if (!localAuthManager.isEnabled(uid)) {
            unlockDestination.setValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            return;
        }

        //  Kiểm tra người dùng đã có Vault Key local chưa
        if (!vaultKeyStore.hasVaultKey(uid)) {
            unlockDestination.setValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            return;
        }

        /*
         * Local Auth đã bật nhưng thiết bị không có/không dùng được
         * sinh trắc học thì chuyển thẳng sang PIN nếu PIN đã được thiết lập
         * và chưa bị khóa do nhập sai quá nhiều lần.
         */
        if (!localAuthManager.isBiometricAvailable()) {
            if (localAuthManager.hasPin(uid) && !localAuthManager.isPinLocked(uid)) {
                unlockDestination.setValue(UnlockDestination.PROMPT_PIN);
            } else {
                unlockDestination.setValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            }
            return;
        }

        // Đủ cả 3 điều kiện -> Mở Prompt quét vân tay
        unlockDestination.setValue(UnlockDestination.PROMPT_BIOMETRIC);
    }

    /**
     * Khôi phục CryptoSession từ Vault Key local sau khi
     * xác thực Local Authentication thành công.
     */
    public void onBiometricSuccess(String uid) {
        biometricFailedAttempts = 0;

        if (uid == null || uid.isEmpty()) {
            unlockDestination.setValue(UnlockDestination.GOTO_LOGIN);
            return;
        }

        try {
            CryptoSession.restore(getApplication(), uid);
            Log.d("TAG", "Khôi phục CryptoSession thành công sau khi quét vân tay!");
            finishUnlock();

        } catch (Exception e) {
            Log.e(TAG, "LỖI KHÔI PHỤC CRYPTOSESSION BẰNG VÂN TAY: " + e.getMessage(), e);

            if (localAuthManager.hasPin(uid) && !localAuthManager.isPinLocked(uid)) {
                unlockDestination.setValue(UnlockDestination.PROMPT_PIN);
            } else {
                unlockDestination.setValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            }
        }
    }

    /**
     * Ghi nhận một lần xác thực sinh trắc học thất bại.
     *
     * Sau 3 lần sai, app chủ động chuyển sang PIN riêng
     * thay vì để BiometricPrompt tiếp tục đến ngưỡng
     * lockout của Android.
     */
    public boolean onBiometricFail(String uid) {

        if (uid == null || uid.isEmpty()) {
            unlockDestination.setValue(
                    UnlockDestination.GOTO_LOGIN
            );
            return false;
        }

        biometricFailedAttempts++;

        Log.d(
                TAG,
                "Biometric thất bại lần "
                        + biometricFailedAttempts
        );

        // Đủ 3 lần sai -> chuyển sang PIN app.
        if (biometricFailedAttempts >= MAX_BIOMETRIC_ATTEMPTS) {

            biometricFailedAttempts = 0;

            if (localAuthManager.hasPin(uid)
                    && !localAuthManager.isPinLocked(uid)) {

                unlockDestination.setValue(
                        UnlockDestination.PROMPT_PIN
                );

            } else {

                unlockDestination.setValue(
                        UnlockDestination.GOTO_MASTER_PASSWORD
                );
            }

            return true;
        }

        return false;
    }



    /**
     * Xử lý khi Local Authentication thất bại hoặc không thể tiếp tục
     * và chuyển sang phương thức mở khóa bằng Master Password.
     */
    public void onBiometricError(String uid, int errorCode) {
        if (uid == null || uid.isEmpty()) {
            unlockDestination.setValue(UnlockDestination.GOTO_LOGIN);
            return;
        }

        if (errorCode == BiometricPrompt.ERROR_LOCKOUT
                || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || errorCode == BiometricPrompt.ERROR_USER_CANCELED
        ) {

            if (localAuthManager.hasPin(uid) && !localAuthManager.isPinLocked(uid)) {
                unlockDestination.setValue(UnlockDestination.PROMPT_PIN);
            } else {
                unlockDestination.setValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            }

            return;
        }
        /*
         * ERROR_CANCELED và các lỗi không phải lockout (bao gồm việc người dùng
         * bấm nút "Chuyển sang PIN" trên System Prompt) không tự động khóa
         * bằng sinh trắc học, mà cho phép chuyển sang PIN nếu có thể.
         */
        Log.d(
                TAG,
                "Biometric kết thúc. code=" + errorCode
        );

        if (localAuthManager.hasPin(uid) ) {
            if (!localAuthManager.isPinLocked(uid)) {
                unlockDestination.setValue(UnlockDestination.PROMPT_PIN);
            } else {
                unlockDestination.setValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            }

        } else {
            unlockDestination.setValue(UnlockDestination.GOTO_LOGIN);
        }
    }

    // =========================================================
    // PIN
    // =========================================================

    /**
     * Mở khóa Vault bằng PIN.
     * PIN chỉ xác thực người dùng local; Vault Key vẫn được khôi phục
     * từ VaultKeyStore sau khi PIN hợp lệ.
     */
    public void unlockWithPin(String uid, String pin) {

        if (uid == null || uid.isEmpty()) {
            unlockDestination.postValue(UnlockDestination.GOTO_LOGIN);
            return;
        }

        if (pin == null || pin.isEmpty()) {
            vaultKeyResult.postValue(Resource.error("Vui lòng nhập PIN"));
            return;
        }

        if (localAuthManager.isPinLocked(uid)) {
            unlockDestination.postValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            return;
        }

        boolean valid = localAuthManager.verifyPin(uid, pin);

        if (valid) {
            try {
                CryptoSession.restore(getApplication(), uid);
                finishUnlock();

            } catch (Exception e) {
                unlockDestination.postValue(UnlockDestination.GOTO_MASTER_PASSWORD);
            }

            return;
        }

        if (localAuthManager.isPinLocked(uid)) {
            unlockDestination.postValue(UnlockDestination.GOTO_MASTER_PASSWORD);

        } else {
            vaultKeyResult.postValue(Resource.error(
                            "PIN không đúng. Còn " + (
                                    localAuthManager.getPinMaxFailedAttempts()
                                            - localAuthManager.getPinFailedAttempts(uid)
                            )
                                    + " lần thử."
                    )
            );
        }
    }

    // =========================================================
    // LOCAL DATA
    // =========================================================

    ///  Xóa toàn bộ dữ liệu Vault và thông tin bảo mật local của người dùng.
    public void wipeLocalVault(String uid) {
        executor.execute(() -> {
            if (uid != null && !uid.isEmpty()) {
                credentialDao.deleteAll(uid);
                vaultKeyStore.clear(uid);
                localAuthManager.clear(uid);
            }

            CryptoSession.clear();
        });
    }

    // =========================================================
    // AUTOFILL
    // =========================================================

    public void prepareAutofillFillResponse(
            String uid,
            String webDomain,
            String packageName,
            AutofillId usernameId,
            AutofillId passwordId
    ) {
        executor.execute(() -> {
            FillResponse response = AutofillResponseHelper.buildUnlockedFillResponse(
                    getApplication(),
                    uid,
                    webDomain,
                    packageName,
                    usernameId,
                    passwordId
            );

            if (response != null) {
                autofillFillResponseResult.postValue(response);
            } else {
                autofillErrorResult.postValue(true);
            }
        });
    }

    // =========================================================
    // INTERNAL
    // =========================================================

    private void finishUnlock() {
        PasswordManagerApplication application = getApplication();
        application.getAppLockManager().unlockFinished();
        unlockDestination.postValue(UnlockDestination.UNLOCKED);
    }
}

