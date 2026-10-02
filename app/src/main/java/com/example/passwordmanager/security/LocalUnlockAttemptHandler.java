package com.example.passwordmanager.security;

import com.google.firebase.auth.FirebaseAuth;

/**
 * Xử lý dùng chung khi 1 lần thử mở khóa Vault cục bộ (Master Password) bị sai.
 * Dùng cả trong UnlockWithMasterPasswordUseCase và nhánh Fast Local của
 * LoginUseCase để 2 luồng luôn tính chung 1 bộ đếm/1 ngưỡng — tránh lặp lại
 * logic ở 2 nơi rồi quên đồng bộ (đúng bug vừa xảy ra).
 */
public final class LocalUnlockAttemptHandler {

    public interface Callback {
        void onError(String message);
        void onVaultWipeRequired(String uid);
    }

    private final AuthAttemptManager authAttemptManager;

    public LocalUnlockAttemptHandler(AuthAttemptManager authAttemptManager) {
        this.authAttemptManager = authAttemptManager;
    }

    public void handleFailure(String uid, Callback callback) {
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