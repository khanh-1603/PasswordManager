package com.example.passwordmanager.usecase;

import android.content.Context;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.local.CredentialEntity;
import com.example.passwordmanager.data.sync.SyncManager;
import com.example.passwordmanager.firebase.AuthRepository;
import com.example.passwordmanager.firebase.IAuthRepository;
import com.example.passwordmanager.firebase.ISecurityRepository;
import com.example.passwordmanager.firebase.SecurityRepository;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.VaultKeyStore;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Đổi Master Password: giải mã toàn bộ vault bằng khóa cũ, mã hóa lại bằng
 * khóa mới, đổi mật khẩu trên Firebase, rồi lưu Salt/Vault Key/CryptoSession mới.
 *
 * Được tách riêng khỏi AuthViewModel vì đây là luồng phức tạp và rủi ro nhất của
 * app (thao tác trực tiếp trên toàn bộ dữ liệu đã mã hóa) — tách ra giúp
 * đọc, test và bảo trì dễ hơn, độc lập với các luồng
 * Login/Register/Unlock khác trong AuthViewModel.
 */
public class ChangeMasterPasswordUseCase {

    public interface Callback {
        void onLoading();
        void onSuccess();
        void onError(String message);
    }

    private final Context appContext;
    private final IAuthRepository authRepository;
    private final ISecurityRepository securityRepository;
    private final VaultKeyStore vaultKeyStore;
    private final ExecutorService executor;

    public ChangeMasterPasswordUseCase(
            Context appContext,
            IAuthRepository authRepository,
            ISecurityRepository securityRepository,
            VaultKeyStore vaultKeyStore,
            ExecutorService executor
    ) {
        this.appContext = appContext.getApplicationContext();
        this.authRepository = authRepository;
        this.securityRepository = securityRepository;
        this.vaultKeyStore = vaultKeyStore;
        this.executor = executor;
    }

    public void execute(
            String currentPassword,
            String newPassword,
            String confirmPassword,
            Callback callback
    ) {
        final String safeCurrentPassword = currentPassword == null
                ? ""
                : currentPassword;

        final String safeNewPassword = newPassword == null
                ? ""
                : newPassword;

        final String safeConfirmPassword = confirmPassword == null
                ? ""
                : confirmPassword;

        if (safeCurrentPassword.isEmpty()) {
            callback.onError("Vui lòng nhập mật khẩu hiện tại");
            return;
        }

        if (safeNewPassword.length() < 8) {
            callback.onError("Mật khẩu mới phải có ít nhất 8 ký tự");
            return;
        }

        if (!safeNewPassword.equals(safeConfirmPassword)) {
            callback.onError("Mật khẩu xác nhận không khớp");
            return;
        }

        FirebaseUser user = authRepository.getCurrentUser();
        if (user == null) {
            callback.onError("Người dùng chưa đăng nhập");
            return;
        }

        callback.onLoading();
        String uid = user.getUid();

        // Chạy giải mã và kiểm tra local trên Background Thread TRƯỚC KHI đổi mật khẩu Firebase
        executor.execute(() -> {
            try {
                CredentialDao dao = AppDatabase
                        .getInstance(appContext).credentialDao();

                List<CredentialEntity> oldEntities = dao.getAllList(uid);

                final CryptoManager oldCrypto;

                if (CryptoSession.isActive()) {
                    oldCrypto = CryptoSession.get();

                } else {
                    String oldSaltBase64 = vaultKeyStore.loadSalt(uid);
                    byte[] oldSalt = CryptoManager.decodeSalt(oldSaltBase64);
                    oldCrypto = new CryptoManager(safeCurrentPassword, oldSalt);
                }

                // Tạo Salt mới & CryptoManager mới
                String newSaltBase64 = CryptoManager.generateSaltBase64();
                byte[] newSalt = CryptoManager.decodeSalt(newSaltBase64);
                CryptoManager newCrypto =
                        new CryptoManager(safeNewPassword, newSalt);

                // Mã hóa lại toàn bộ credential
                List<CredentialEntity> newEntities = new ArrayList<>();
                long now = System.currentTimeMillis();

                for (CredentialEntity old : oldEntities) {
                    String title = oldCrypto.decrypt(old.getTitle());
                    String username = oldCrypto.decrypt(old.getUsername());
                    String password = oldCrypto.decrypt(old.getPassword());
                    String category = oldCrypto.decrypt(old.getCategory());
                    String notes = oldCrypto.decrypt(old.getNotes());

                    CredentialEntity updated = new CredentialEntity(
                            uid,
                            old.getId(),
                            newCrypto.encrypt(title),
                            old.getAutofillDomain(),
                            old.getAutofillPackage(),
                            newCrypto.encrypt(username),
                            newCrypto.encrypt(password),
                            newCrypto.encrypt(category),
                            newCrypto.encrypt(notes),
                            old.getCreatedAt(),
                            now,
                            CredentialEntity.PENDING_UPDATE
                    );

                    newEntities.add(updated);
                }

                // Giải mã local OK -> Tiến hành đổi mật khẩu trên Firebase
                authRepository.changePassword(
                        safeCurrentPassword,
                        safeNewPassword,
                        firebaseUser -> executor.execute(() -> {
                                    try {
                                        // Cập nhật Room DB trong Background Thread
                                        AppDatabase.getInstance(appContext)
                                                .runInTransaction(() -> {
                                                    dao.insertAll(newEntities);
                                        });

                                        // Lưu Salt mới & Vault Key mới
                                        securityRepository.saveSalt(uid, newSaltBase64)
                                                .addOnSuccessListener(unused -> {
                                                    vaultKeyStore.saveSalt(uid, newSaltBase64);

                                        try {
                                            vaultKeyStore.saveVaultKey(
                                                    uid,
                                                    newCrypto.getSecretKey()
                                            );

                                        } catch (Exception e) {
                                            callback.onError(
                                                    "Không thể lưu khóa bảo mật vào thiết bị: "
                                                            + e.getMessage()
                                            );
                                            return;
                                        }

                                        // Cập nhật CryptoSession & Sync
                                        CryptoSession.start(newCrypto);
                                        SyncManager syncManager = new SyncManager(dao, executor);
                                        syncManager.sync();

                                        callback.onSuccess();
                                    })

                                    .addOnFailureListener(e -> {
                                    callback.onError(
                                            "Lưu Salt mới thất bại: "
                                                    + e.getMessage()
                                    );
                                });

                        } catch (Exception e) {
                                        callback.onError(
                                                "Cập nhật dữ liệu local thất bại:"
                                                        + e.getMessage()
                                        );
                                    }
                        }),

                        message -> callback.onError(message)
                );

            } catch (Exception e) {
                callback.onError("Mật khẩu hiện tại không đúng hoặc giải mã thất bại: " + e.getMessage());
            }
        });
    }
}
