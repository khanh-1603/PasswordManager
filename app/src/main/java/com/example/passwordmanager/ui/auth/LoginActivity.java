package com.example.passwordmanager.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.MainActivity;
import com.example.passwordmanager.R;

import com.example.passwordmanager.databinding.ActivityLoginBinding;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.ui.security.LocalAuthIntroActivity;
import com.example.passwordmanager.ui.security.UnlockActivity;
import com.example.passwordmanager.viewmodel.AuthViewModel;
import com.example.passwordmanager.viewmodel.AuthViewModelFactory;
import com.example.passwordmanager.viewmodel.Resource;
import com.example.passwordmanager.viewmodel.SecurityViewModel;
import com.example.passwordmanager.viewmodel.SecurityViewModelFactory;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseUser;


public class LoginActivity  extends AppCompatActivity {
    private ActivityLoginBinding binding;
    private EditText edtEmail;
    private EditText edtPassword;
    private MaterialCardView btnBiometricQuickLogin;

    private boolean masterPasswordUnlock;
    /**
     * Extra do MainActivity gửi khi phát hiện đã có Vault Key local
     * (CryptoSession chết do process bị kill) — LoginActivity sẽ tự
     * bắn UnlockActivity lên trên NGAY khi vừa hiện, để màn hình Login
     * (trung tính) luôn nằm dưới lớp trong suốt của Unlock, thay vì lộ
     * MainActivity đang finish.
     */
    public static final String EXTRA_AUTO_UNLOCK = "auto_unlock";

    /**
     * Extra bật chế độ "Mở khóa bằng Master Password" cho tài khoản đang bị
     * khóa (Local Auth tắt / PIN bị khóa...). Chuỗi này trùng với literal mà
     * UnlockActivity đang dùng.
     */
    public static final String EXTRA_MASTER_PASSWORD_UNLOCK = "master_password_unlock";

    private static final String STATE_MASTER_MODE = "state_master_mode";

    private AuthViewModel authViewModel;
    private SecurityViewModel securityViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        edtEmail = binding.edtEmail;
        edtPassword = binding.edtPassword;
        btnBiometricQuickLogin = binding.btnBiometricQuickLogin;

        Button btnLogin = binding.btnLogin;
        Button btnRegister = binding.btnRegister;

        authViewModel = new ViewModelProvider(
                this,
                new AuthViewModelFactory(getApplication())
        ).get(AuthViewModel.class);

        securityViewModel = new ViewModelProvider(
                this,
                new SecurityViewModelFactory(getApplication())
        ).get(SecurityViewModel.class);

        boolean requestedMasterMode = getIntent().getBooleanExtra(
                EXTRA_MASTER_PASSWORD_UNLOCK,
                false
        );

        if (savedInstanceState != null) {
            // Xoay màn hình: giữ đúng chế độ người dùng đang xem (vd: đã bấm
            // "Đăng nhập bằng tài khoản khác" thì không bị đẩy ngược về chế độ khóa).
            requestedMasterMode = savedInstanceState.getBoolean(
                    STATE_MASTER_MODE,
                    requestedMasterMode
            );
        }

        // Chế độ Mở khóa chỉ có nghĩa khi vẫn còn tài khoản đang bị khóa.
        if (requestedMasterMode && authViewModel.getUser() != null) {
            showMasterPasswordMode();
        } else {
            showNormalMode(false);
        }

        // btnRegister LUÔN là "Đăng ký" ở mọi chế độ. Không còn dùng lại nút này
        // cho việc đổi tài khoản nữa (đã có btnSwitchAccount riêng).
        btnRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class))
        );

        // Chỉ chuyển giao diện sang đăng nhập thường, KHÔNG đăng xuất tài khoản cũ.
        // Phiên cũ chỉ bị thay thế khi đăng nhập tài khoản mới thành công, nên
        // đang offline hoặc nhập sai thì tài khoản cũ vẫn mở khóa lại được.
        binding.btnSwitchAccount.setOnClickListener(v -> showNormalMode(true));

        observeAuthResult();
        observeMasterPasswordUnlockResult();
        observeVaultWipeEvent();

        // MainActivity đã biết có Vault Key local -> tự động mở Unlock ngay,
        // không cần người dùng bấm nút. Chỉ bắn 1 lần lúc tạo Activity
        // (bỏ qua khi Activity được recreate do xoay màn hình...).
        if (savedInstanceState == null
                && !masterPasswordUnlock
                && getIntent().getBooleanExtra(EXTRA_AUTO_UNLOCK, false)
        ) {
            startActivity(new Intent(this, UnlockActivity.class));
        }

        /*
         * LoginActivity không còn tự xử lý Biometric.
         * UnlockActivity là trung tâm của Local Auth.
         */

        btnBiometricQuickLogin.setOnClickListener(v -> {
            if (masterPasswordUnlock) {
                Toast.makeText(
                        this,
                        "Không thể xác thực cục bộ. Vui lòng nhập mật khẩu",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            if (authViewModel.getUser() == null) {
                // Chưa đăng nhập tài khoản nào (vd: app mới cài) -> không mở
                // Unlock, tránh việc Unlock lại bật thêm 1 LoginActivity mới
                Toast.makeText(
                        this,
                        "Vui lòng đăng nhập trước",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            startActivity(new Intent(this, UnlockActivity.class));
        });

        btnLogin.setOnClickListener(v -> login());
    }

    private void login() {
        String email = edtEmail.getText().toString().trim();
        String password = edtPassword.getText().toString().trim();

        if (email.isEmpty()) {
            edtEmail.setError("Vui lòng nhập email");
            return;
        }

        if (password.isEmpty()) {
            edtPassword.setError(masterPasswordUnlock
                            ? "Vui lòng nhập Master Password"
                            : "Vui lòng nhập mật khẩu"
            );

            return;
        }

        if (masterPasswordUnlock) {
            FirebaseUser lockedUser = authViewModel.getUser();

            boolean sameAccount = lockedUser != null
                    && lockedUser.getEmail() != null
                    && lockedUser.getEmail().equalsIgnoreCase(email);

            if (sameAccount) {
                // Đúng tài khoản đang bị khóa -> mở khóa offline bằng Master Password.
                authViewModel.unlockWithMasterPassword(
                        lockedUser.getUid(),
                        password
                );
                return;
            }

            // Email khác tài khoản đang bị khóa -> người dùng muốn đăng nhập
            // tài khoản khác, đi tiếp xuống luồng đăng nhập Firebase bình thường.
        }

        authViewModel.login(email, password);
    }

    /**
     * Chế độ Mở khóa: điền sẵn email của tài khoản đang bị khóa nhưng VẪN cho sửa.
     * Nhập email khác + mật khẩu = đăng nhập sang tài khoản khác.
     */
    private void showMasterPasswordMode() {
        masterPasswordUnlock = true;

        FirebaseUser cachedUser = authViewModel.getUser();
        if (cachedUser != null && cachedUser.getEmail() != null) {
            edtEmail.setText(cachedUser.getEmail());
        }

        binding.btnLogin.setText("Mở khóa Vault");
        binding.btnSwitchAccount.setVisibility(View.VISIBLE);
        binding.layoutRegisterFooter.setVisibility(View.GONE);
        edtPassword.requestFocus();
    }

    /// Chế độ đăng nhập thường (email + mật khẩu qua Firebase).
    private void showNormalMode(boolean clearFields) {
        masterPasswordUnlock = false;

        if (clearFields) {
            edtEmail.setText("");
            edtPassword.setText("");
            edtPassword.setError(null);
        }

        binding.btnLogin.setText("Đăng nhập");
        binding.btnSwitchAccount.setVisibility(View.GONE);
        binding.layoutRegisterFooter.setVisibility(View.VISIBLE);

        if (clearFields) {
            edtEmail.requestFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_MASTER_MODE, masterPasswordUnlock);
    }


    // Lắng nghe Đăng nhập Firebase mạng
   private void observeAuthResult() {
        authViewModel.getLoginResult()
                .observe(this, result -> {

                    if (result == null ||
                            result.getStatus() == Resource.Status.LOADING
                    ) {
                        return;
                    }

                    if (result.getStatus() == Resource.Status.SUCCESS) {
                        Toast.makeText(
                                this,
                                "Đăng nhập thành công",
                                Toast.LENGTH_SHORT
                        ).show();

                        navigateToNextScreen(result.getData());

                    } else if (result.getStatus() == Resource.Status.ERROR) {
                        Toast.makeText(
                                this,
                                result.getMessage(),
                                Toast.LENGTH_SHORT
                        ).show();
                    }
        });
   }

    // Lắng nghe Mở khóa Fast Local
    private void observeMasterPasswordUnlockResult() {
        authViewModel.getMasterPasswordUnlockResult().observe(this, result -> {
            if (result == null || result.getStatus() == Resource.Status.LOADING) return;

            if (result.getStatus() == Resource.Status.SUCCESS) {
                Toast.makeText(this, "Mở khóa thành công", Toast.LENGTH_SHORT).show();
                FirebaseUser user = authViewModel.getUser();
                navigateToNextScreen(user);

            } else if (result.getStatus() == Resource.Status.ERROR) {
                Toast.makeText(this, result.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void observeVaultWipeEvent() {
        authViewModel.getVaultWipeEvent().observe(this, uid -> {
            if (uid == null || uid.isEmpty()) return;
            securityViewModel.wipeLocalVault(uid);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        /*
         * Nếu vừa quay lại từ UnlockActivity và CryptoSession đã được kích hoạt thành công,
         * tự động chuyển người dùng vào thẳng MainActivity.
         */
        if (CryptoSession.isActive() && !masterPasswordUnlock) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    // Điều hướng màn hình tiếp theo
    private void navigateToNextScreen(FirebaseUser user) {
        LocalAuthManager localAuthManager
                = AppContainer.getInstance(this).getLocalAuthManager();

        Log.d("LOGIN", "navigateToNextScreen:");
        String uid = user != null
                ? user.getUid()
                : null;

        // CHỈ hiển thị Intro DUY NHẤT 1 LẦN đầu tiên
        if (uid != null && !localAuthManager.isIntroShown(uid)) {
            localAuthManager.setIntroShown(uid, true);
            startActivity(new Intent(
                    this,
                    LocalAuthIntroActivity.class)
            );

        } else {
            startActivity(new Intent(
                    this,
                    MainActivity.class)
            );
        }

        finish();
    }
}
