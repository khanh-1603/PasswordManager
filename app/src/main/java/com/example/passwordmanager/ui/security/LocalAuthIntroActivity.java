package com.example.passwordmanager.ui.security;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.MainActivity;

import com.example.passwordmanager.databinding.ActivityLocalAuthIntroBinding;
import com.example.passwordmanager.databinding.ActivityPinEntryBinding;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.utils.PinKeypadController;
import com.example.passwordmanager.viewmodel.SecurityViewModel;
import com.example.passwordmanager.viewmodel.SecurityViewModelFactory;
import com.google.firebase.auth.FirebaseUser;


/**
 * Màn hình giới thiệu, hiển thị 1 lần ngay sau khi Đăng ký thành công,
 * mời người dùng thiết lập Local Authentication (PIN bắt buộc + sinh
 * trắc học nếu thiết bị hỗ trợ) để lần sau mở khóa Vault không cần mạng.
 */
public class LocalAuthIntroActivity extends AppCompatActivity {
    private LocalAuthManager localAuthManager;
    private ActivityLocalAuthIntroBinding binding;
    private ActivityPinEntryBinding pinEntryBinding;
    private SecurityViewModel securityViewModel;

    private String uid;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLocalAuthIntroBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        localAuthManager = AppContainer.getInstance(this)
                .getLocalAuthManager();

        securityViewModel = new ViewModelProvider(
                this,
                new SecurityViewModelFactory(getApplication())
        ).get(SecurityViewModel.class);

        // Đánh dấu ĐÃ XEM INTRO ngay khi người dùng vào màn hình này
        FirebaseUser currentUser = securityViewModel.getUser();

        if (currentUser == null) {
            Toast.makeText(
                    this,
                    "Phiên đăng nhập không còn",
                    Toast.LENGTH_SHORT
            ).show();

            startActivity(
                    new Intent(
                            this,
                            LoginActivity.class
                    )
            );

            finish();
            return;
        }

        uid = securityViewModel.getUser().getUid();
        localAuthManager.setIntroShown(uid, true);

        Button btnSetup = binding.btnSetupBiometricNow;
        Button btnLater = binding.btnSetupLater;

        btnSetup.setOnClickListener(
                v -> setupLocalAuth()
        );

        btnLater.setOnClickListener(v -> goToMain());

    }

    private void setupLocalAuth() {
        if (!localAuthManager.isBiometricAvailable()) {
            Toast.makeText(
                    this,
                    "Thiết bị chưa thiết lập sinh trắc học. "
                            + "Bạn vẫn có thể dùng mã PIN để mở khóa nhanh.",
                    Toast.LENGTH_LONG
            ).show();
        }

        // PIN luôn là lớp xác thực bắt buộc (fallback khi sinh trắc học lỗi/khóa).
        showPinSetupScreen();
    }

    private void showPinSetupScreen() {
        pinEntryBinding = ActivityPinEntryBinding.inflate(getLayoutInflater());
        setContentView(pinEntryBinding.getRoot());

        pinEntryBinding.tvPinTitle.setText("Thiết lập PIN");
        pinEntryBinding.tvPinSubtitle.setText("Tạo mã PIN gồm 4 chữ số để mở khóa Vault khi không dùng sinh trắc học");

        TextView btnCancel = pinEntryBinding.btnForgotPin;
        btnCancel.setText("Bỏ qua");
        btnCancel.setOnClickListener(v -> goToMain());

        new PinKeypadController(pinEntryBinding, pin -> {            try {
                localAuthManager.savePin(uid, pin);
                localAuthManager.setEnabled(uid, true);

            } catch (IllegalStateException e) {
                Toast.makeText(
                        this,
                        "Không thể lưu PIN: " + e.getMessage(),
                        Toast.LENGTH_SHORT
                ).show();

                recreate();
                return;
            }

            Toast.makeText(
                    this,
                    "Đã bật xác thực cục bộ",
                    Toast.LENGTH_SHORT
            ).show();

            goToMain();
        });
    }

    private void goToMain() {
        startActivity(
                new Intent(
                        this,
                        MainActivity.class
                )
        );

        finish();
    }
}
