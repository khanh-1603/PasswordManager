package com.example.passwordmanager.usecase;

import android.util.Patterns;

import com.example.passwordmanager.firebase.IAuthRepository;
import com.example.passwordmanager.firebase.ISecurityRepository;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.VaultKeyStore;
import com.google.firebase.auth.FirebaseUser;

/**
 * Đăng ký tài khoản mới bằng Email/Mật khẩu, tạo Salt mới trên Firestore,
 * và khởi tạo CryptoSession cho vault trống ban đầu (không cần kéo dữ liệu
 * từ Firebase như Login vì tài khoản chưa có credential nào).
 *
 * Trước đây nằm trong AuthViewModel.register() + startRegisterCryptoSession().
 */
public class RegisterUseCase {

    public interface Callback {
        void onLoading();
        void onSuccess(FirebaseUser user);
        void onError(String message);
    }

    private final IAuthRepository authRepository;
    private final ISecurityRepository securityRepository;
    private final VaultKeyStore vaultKeyStore;

    public RegisterUseCase(
            IAuthRepository authRepository,
            ISecurityRepository securityRepository,
            VaultKeyStore vaultKeyStore
    ) {
        this.authRepository = authRepository;
        this.securityRepository = securityRepository;
        this.vaultKeyStore = vaultKeyStore;
    }

    public void execute(
            String username,
            String email,
            String password,
            String confirmPassword,
            Callback callback
    ) {
        final String safeUsername = username == null ? "" : username.trim();
        final String safeEmail = email == null ? "" : email.trim();
        final String safePassword = password == null ? "" : password;
        final String safeConfirmPassword = confirmPassword == null ? "" : confirmPassword;

        if (safeUsername.isEmpty()) {
            callback.onError("Vui lòng nhập tên người dùng");
            return;
        }

        if (safeUsername.length() < 2) {
            callback.onError("Tên người dùng phải từ 2 ký tự trở lên");
            return;
        }

        if (safeEmail.isEmpty()) {
            callback.onError("Vui lòng nhập email");
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(safeEmail).matches()) {
            callback.onError("Email không hợp lệ");
            return;
        }

        if (safePassword.length() < 8) {
            callback.onError("Mật khẩu tối thiểu 8 ký tự");
            return;
        }

        if (!safePassword.equals(safeConfirmPassword)) {
            callback.onError("Mật khẩu xác nhận không khớp");
            return;
        }

        callback.onLoading();

        authRepository.register(
                safeUsername,
                safeEmail,
                safePassword,
                user -> {
                    if (user == null) {
                        callback.onError("Không thể đăng ký");
                        return;
                    }

                    String uid = user.getUid();
                    String saltBase64 = CryptoManager.generateSaltBase64();

                    securityRepository.saveSalt(uid, saltBase64)
                            .addOnSuccessListener(unused -> {
                                vaultKeyStore.saveSalt(uid, saltBase64);

                                if (startRegisterCryptoSession(
                                        user,
                                        safeConfirmPassword,
                                        saltBase64,
                                        callback
                                )) {
                                    callback.onSuccess(user);
                                }
                            })
                            .addOnFailureListener(e -> callback.onError(
                                    "Không thể lưu thông tin mã hóa: " + e.getMessage()
                            ));
                },
                callback::onError
        );
    }

    private boolean startRegisterCryptoSession(
            FirebaseUser user,
            String password,
            String saltBase64,
            Callback callback
    ) {
        try {
            byte[] salt = CryptoManager.decodeSalt(saltBase64);
            CryptoManager cryptoManager = new CryptoManager(password, salt);

            // Tạo CryptoSession trong RAM
            CryptoSession.start(cryptoManager);

            /*
             * Lưu wrapped AES key bằng Android Keystore.
             * Raw AES key không được lưu plaintext.
             */
            vaultKeyStore.saveVaultKey(user.getUid(), cryptoManager.getSecretKey());

            return true;

        } catch (Exception e) {
            CryptoSession.clear();
            callback.onError("Không thể khởi tạo mã hóa: " + e.getMessage());
            return false;
        }
    }
}