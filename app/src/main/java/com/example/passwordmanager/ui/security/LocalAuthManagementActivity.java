package com.example.passwordmanager.ui.security;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.databinding.ActivityLocalAuthManagementBinding;
import com.example.passwordmanager.databinding.ActivityPinEntryBinding;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.utils.PinKeypadController;
import com.example.passwordmanager.viewmodel.SecurityViewModel;
import com.example.passwordmanager.viewmodel.SecurityViewModelFactory;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Màn hình Cài đặt bảo mật cục bộ (bật/tắt Local Auth).
 *
 * Lưu ý: PIN LUÔN LÀ ĐIỀU KIỆN BẮT BUỘC để bật công tắc, bất kể
 * thiết bị có hỗ trợ sinh trắc học hay không — sinh trắc học chỉ là
 * lối tắt, PIN mới là fallback bắt buộc khi sinh trắc học lỗi/khóa.
 */
public class LocalAuthManagementActivity extends AppCompatActivity {
    private LocalAuthManager localAuthManager;
    private ActivityLocalAuthManagementBinding binding;
    private ActivityPinEntryBinding pinEntryBinding;
    private SecurityViewModel securityViewModel;
    private String uid;

    private SwitchMaterial swtichLocalAuth;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocalAuthManagementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        securityViewModel = new ViewModelProvider(
                this,
                new SecurityViewModelFactory(getApplication())
        ).get(SecurityViewModel.class);

        if (securityViewModel.getUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        uid = securityViewModel.getUser().getUid();

        localAuthManager = AppContainer.getInstance(this)
                .getLocalAuthManager();

        swtichLocalAuth = binding.switchLocalAuth;
        swtichLocalAuth.setChecked(localAuthManager.isEnabled(uid));

        swtichLocalAuth.setOnCheckedChangeListener(
                ((buttonView, isChecked) -> {
                    if (isChecked) {
                        onSwitchTurnedOn();
                    } else {
                        localAuthManager.setEnabled(uid, false);

                        Toast.makeText(
                                this,
                                "Đã tắt xác thực cục bộ",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
        );

        binding.topBarBioSettings.setOnClickListener(v -> finish());
    }

    /**
     * PIN là điều kiện bắt buộc để bật công tắc: nếu chưa có PIN,
     * bắt thiết lập trước rồi mới thực sự bật công tắc.
     */
    private void onSwitchTurnedOn() {
        if (!localAuthManager.isBiometricAvailable()) {
            Toast.makeText(
                    this,
                    "Thiết bị chưa thiết lập sinh trắc học. "
                            + "Sẽ dùng PIN để mở khóa nhanh.",
                    Toast.LENGTH_SHORT
            ).show();
        }

        if (!localAuthManager.hasPin(uid)) {
            showPinSetupScreen();
            return;
        }

        localAuthManager.setEnabled(uid, true);

        Toast.makeText(
                this,
                "Đã bật xác thực cục bộ",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showPinSetupScreen() {
        pinEntryBinding = ActivityPinEntryBinding.inflate(getLayoutInflater());
        setContentView(pinEntryBinding.getRoot());

        pinEntryBinding.tvPinTitle.setText("Thiết lập PIN");
        pinEntryBinding.tvPinSubtitle.setText("Tạo mã PIN gồm 4 chữ số để mở khóa Vault khi không dùng sinh trắc học");

        TextView btnCancel = pinEntryBinding.btnForgotPin;
        btnCancel.setText("Hủy");
        btnCancel.setOnClickListener(v -> recreate());

        new PinKeypadController(pinEntryBinding, pin -> {
            try {
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

            // Quay lại màn hình Cài đặt, giờ isEnabled(uid) đã true -> switch tự bật.
            recreate();
        });
    }
}
