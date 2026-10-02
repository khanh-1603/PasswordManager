package com.example.passwordmanager.firebase;

import com.google.firebase.auth.FirebaseUser;

/**
 * Interface trừu tượng cho AuthRepository — cho phép AuthViewModel/UseCase
 * phụ thuộc vào abstraction thay vì implementation cụ thể (Dependency
 * Inversion), và cho phép viết unit test bằng mock/fake thay vì phải
 * gọi Firebase thật.
 */
public interface IAuthRepository {

    FirebaseUser getCurrentUser();

    void register(
            String username,
            String email,
            String password,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    );

    void login(
            String email,
            String password,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    );

    void logout();

    void updateDisplayName(
            String newName,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    );

    void deleteAccount(
            String password,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    );

    void changePassword(
            String currentPassword,
            String newPassword,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    );

    /// Interface callback khi đăng nhập/đăng ký/thao tác tài khoản thành công
    interface OnAuthSuccessListener {
        void onSuccess(FirebaseUser user);
    }

    /// Interface callback khi có lỗi
    interface OnAuthErrorListener {
        void onError(String message);
    }
}