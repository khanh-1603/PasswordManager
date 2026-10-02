package com.example.passwordmanager.usecase;

import com.example.passwordmanager.security.AuthAttemptManager;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.security.LocalUnlockAttemptHandler;
import com.example.passwordmanager.security.VaultKeyStore;
import com.google.firebase.auth.FirebaseAuth;

import java.util.concurrent.ExecutorService;

import javax.crypto.SecretKey;

/**
 * Mở khóa Vault bằng Master Password và Salt đã lưu local (không cần mạng).
 * Dùng khi Local Auth (biometric/PIN) bị tắt hoặc đã sai quá nhiều lần.
 * Nếu sai quá số lần cho phép (AuthAttemptManager), yêu cầu xóa toàn bộ
 * dữ liệu Vault local để chống dò quét mật khẩu (brute-force) qua
 * callback onVaultWipeRequired — việc xóa thật sự vẫn do SecurityViewModel
 * đảm nhiệm (như thiết kế gốc), UseCase chỉ báo hiệu.
 *
 * Trước đây nằm trong AuthViewModel.unlockWithMasterPassword()
 * + handleMasterPasswordFailure().
 */
public class UnlockWithMasterPasswordUseCase {

    public interface Callback extends LocalUnlockAttemptHandler.Callback {
        void onSuccess();
    }

    private final VaultKeyStore vaultKeyStore;
    private final AuthAttemptManager authAttemptManager;
    private final LocalAuthManager localAuthManager;
    private final LocalUnlockAttemptHandler attemptHandler;
    private final ExecutorService executor;

    public UnlockWithMasterPasswordUseCase(
            VaultKeyStore vaultKeyStore,
            AuthAttemptManager authAttemptManager,
            LocalAuthManager localAuthManager,
            ExecutorService executor
    ) {
        this.vaultKeyStore = vaultKeyStore;
        this.authAttemptManager = authAttemptManager;
        this.localAuthManager = localAuthManager;
        this.executor = executor;
        this.attemptHandler = new LocalUnlockAttemptHandler(authAttemptManager);

    }

    public void execute(String uid, String masterPassword, Callback callback) {
        if (uid == null || uid.isEmpty()) {
            callback.onError("Không xác định được tài khoản");
            return;
        }

        if (masterPassword == null || masterPassword.isEmpty()) {
            callback.onError("Vui lòng nhập Master Password");
            return;
        }

        executor.execute(() -> {
            try {
                String saltBase64 = vaultKeyStore.loadSalt(uid);

                if (saltBase64 == null || saltBase64.isEmpty()) {
                    callback.onError("Không tìm thấy thông tin mã hóa local");
                    return;
                }

                byte[] salt = CryptoManager.decodeSalt(saltBase64);
                CryptoManager cryptoManager = new CryptoManager(masterPassword, salt);

                SecretKey savedVaultKey = vaultKeyStore.loadVaultKey(uid);

                if (savedVaultKey == null) {
                    callback.onError("Không tìm thấy Vault Key local");
                    return;
                }

                if (!CryptoManager.isSameKey(
                        cryptoManager.getSecretKey().getEncoded(),
                        savedVaultKey.getEncoded()
                )) {
                    attemptHandler.handleFailure(uid, new LocalUnlockAttemptHandler.Callback() {
                        @Override public void onError(String message) {
                            callback.onError(message);
                        }

                        @Override public void onVaultWipeRequired(String wipedUid) {
                            callback.onVaultWipeRequired(wipedUid);
                        }
                    });

                    return;
                }

                authAttemptManager.reset(uid);
                localAuthManager.resetPinFailedAttempts(uid);
                CryptoSession.start(cryptoManager);
                callback.onSuccess();

            } catch (Exception e) {
                CryptoSession.clear();
                callback.onError("Không thể mở khóa: " + e.getMessage());
            }
        });
    }

    /**
     * Ghi nhận 1 lần đăng nhập (Master Password) sai và gắn kèm số lần thử
     * còn lại vào thông báo lỗi, dùng chung bộ đếm với màn Master Password
     * fallback (AuthAttemptManager).
     */
    private void handleFailure(String uid, Callback callback) {
        int attempts = authAttemptManager.recordFailure(uid);
        int remaining = AuthAttemptManager.MAX_ATTEMPTS - attempts;

        if (authAttemptManager.reachedLimit(uid)) {
            authAttemptManager.reset(uid);
            FirebaseAuth.getInstance().signOut();

            callback.onError(
                    "Sai Master Password quá nhiều lần. "
                            + "Đã xóa dữ liệu cục bộ, vui lòng đăng nhập lại."
            );

            callback.onVaultWipeRequired(uid);
            return;
        }

        callback.onError(
                "Master Password không đúng. Còn " + remaining + " lần thử."
        );
    }
}