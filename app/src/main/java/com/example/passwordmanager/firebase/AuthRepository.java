package com.example.passwordmanager.firebase;

import com.example.passwordmanager.security.CryptoSession;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

public class AuthRepository implements IAuthRepository {
    private  final FirebaseAuth auth;

    public AuthRepository() {
        auth = FirebaseAuth.getInstance();
    }

    public FirebaseUser getCurrentUser() {
        return auth.getCurrentUser();
    }

    public void register(
            String username,
            String email,
            String password,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    ) {
        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(authResult -> {
                    if (!authResult.isSuccessful()) {
                        String message = authResult.getException() != null
                                ? authResult.getException().getMessage()
                                : "Đăng ký thất bại";
                        errorListener.onError(message);
                        return;
                    }

                    FirebaseUser user = auth.getCurrentUser();

                    if (user == null) {
                        errorListener.onError("Không thể tạo tài khoản");
                        return;
                    }

                    /*
                        * Firebase Auth không có trường "username" riêng.
                        * Ở project này username được lưu vào displayName.
                    */
                    UserProfileChangeRequest profileUpdates =
                            new UserProfileChangeRequest.Builder()
                                    .setDisplayName(username)
                                    .build();

                    user.updateProfile(profileUpdates)
                            .addOnSuccessListener(unused -> {
                                // Trả user về sau khi displayName đã được lưu
                                successListener.onSuccess(user);
                            })

                            .addOnFailureListener(e ->
                                    errorListener.onError(
                                            "Tạo tài khoản thành công nhưng "
                                                    + "không thể lưu tên người dùng: "
                                                    + e.getMessage()
                                    )
                            );
                })

                .addOnFailureListener(e ->
                        errorListener.onError(
                                e.getMessage()
                        )
                );
    }

    public void login(
            String email,
            String password,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    ) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = auth.getCurrentUser();

                        if (user != null) {
                            successListener.onSuccess(user);
                        }
                    } else {
                        String message = task.getException() != null
                                ? task.getException().getMessage()
                                : "Đăng nhập thất bại";

                        errorListener.onError(message);
                    }
                });
    }

    public void logout() {
        CryptoSession.clear();
        auth.signOut();
    }

    public void updateDisplayName(
            String newName,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            errorListener.onError("Người dùng chưa đăng nhập");
            return;
        }

        UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                .setDisplayName(newName)
                .build();

        user.updateProfile(profileUpdates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser updatedUser = auth.getCurrentUser();
                        if (updatedUser != null) {
                            successListener.onSuccess(updatedUser);

                        } else {
                            errorListener.onError("Không thể lấy thông tin người dùng sau khi cập nhật");
                        }

                    } else {
                        String message = task.getException() != null
                                ? task.getException().getMessage()
                                : "Cập nhật tên thất bại";
                        errorListener.onError(message);
                    }
                });
    }

    public void deleteAccount(
            String password,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getEmail() == null) {
            errorListener.onError("Người dùng chưa đăng nhập");
            return;
        }

        AuthCredential credential =
                EmailAuthProvider.getCredential(user.getEmail(), password);

        user.reauthenticate(credential)
                .addOnSuccessListener(authResult -> {
                    String uid = user.getUid();

                    deleteFirestoreData(uid, new OnFirestoreDeleteListener() {
                        @Override
                        public void onSuccess() {
                            user.delete()
                                    .addOnSuccessListener(unused ->
                                            successListener.onSuccess(null))
                                    .addOnFailureListener(e ->
                                            errorListener
                                                    .onError("Xóa tài khoản Firebase thất bại: "
                                                    + e.getMessage()));
                        }

                        @Override
                        public void onError(String message) {
                            errorListener
                                    .onError("Xóa dữ liệu Cloud thất bại: "
                                    + message);
                        }
                    });
                })

                .addOnFailureListener(e ->
                        errorListener
                                .onError("Xác thực lại thất bại: "
                                        + e.getMessage()));
    }

    private void deleteFirestoreData(String uid, OnFirestoreDeleteListener listener) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users")
                .document(uid)
                .collection("credentials")
                .get()

                .addOnSuccessListener(querySnapshot -> {
                    WriteBatch batch = db.batch();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        batch.delete(doc.getReference());
                    }

                    // Xóa security crypto doc
                    batch.delete(db.collection("users")
                            .document(uid)
                            .collection("security")
                            .document("crypto"));

                    // Xóa user root doc
                    batch.delete(db.collection("users")
                            .document(uid));

                    batch.commit()
                            .addOnSuccessListener(unused ->
                                    listener.onSuccess())
                            .addOnFailureListener(e ->
                                    listener.onError(e.getMessage()));
                })

                .addOnFailureListener(e ->
                        listener.onError(e.getMessage()));
    }

    private interface OnFirestoreDeleteListener {
        void onSuccess();
        void onError(String message);
    }

    public void changePassword(
            String currentPassword,
            String newPassword,
            OnAuthSuccessListener successListener,
            OnAuthErrorListener errorListener
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getEmail() == null) {
            errorListener.onError("Người dùng chưa đăng nhập");
            return;
        }

        AuthCredential credential = EmailAuthProvider
                .getCredential(user.getEmail(), currentPassword);

        user.reauthenticate(credential)
                .addOnSuccessListener(authResult -> {
                    user.updatePassword(newPassword)
                            .addOnSuccessListener(unused ->
                                    successListener.onSuccess(user)
                            )

                            .addOnFailureListener(e ->
                                    errorListener.onError(
                                            "Đổi mật khẩu Firebase thất bại: "
                                                    + e.getMessage())
                            );
                })

                .addOnFailureListener(e ->
                        errorListener.onError(
                                "Xác thực lại thất bại (Mật khẩu hiện tại không đúng?): "
                                        + e.getMessage())
                );
    }
}
