package com.example.passwordmanager.ui.credential;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.databinding.ActivityProfileManagementBinding;
import com.example.passwordmanager.databinding.DialogConfirmDeleteAccountBinding;
import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.viewmodel.AuthViewModel;
import com.example.passwordmanager.viewmodel.AuthViewModelFactory;
import com.example.passwordmanager.viewmodel.Resource;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;

public class ProfileManagementActivity extends AppCompatActivity {
    private ActivityProfileManagementBinding binding;
    private ImageView btnBack;
    private TextView tvHeaderDisplayName, tvHeaderEmail;
    private TextInputEditText etFullName, etEmail;
    private TextInputEditText etCurrentPassword,
            etNewPassword, etConfirmPassword;
    private MaterialButton btnSaveTop,
            btnSaveProfileBottom,
            btnDeleteAccountForever;

    private AuthViewModel authViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileManagementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authViewModel = new ViewModelProvider(
                this,
                new AuthViewModelFactory(getApplication())
        ).get(AuthViewModel.class);

        initViews();
        loadUserData();
        observeViewModel();
        setupListeners();
    }

    private void initViews() {
        tvHeaderDisplayName = binding.tvHeaderDisplayName;
        tvHeaderEmail = binding.tvHeaderEmail;
        etFullName = binding.etFullName;
        etEmail = binding.etEmail;
        etCurrentPassword = binding.etCurrentPassword;
        etNewPassword = binding.etNewPassword;
        etConfirmPassword = binding.etConfirmPassword;

        btnSaveTop = binding.btnSaveTop;
        btnSaveProfileBottom = binding.btnSaveProfileBottom;
        btnDeleteAccountForever = binding.btnDeleteAccountForever;
        btnBack = binding.btnBack;

        etEmail.setFocusable(false);
        etEmail.setClickable(false);
        etEmail.setEnabled(false);
    }

    private void loadUserData() {
        FirebaseUser user = authViewModel.getUser();

        if (user != null) {
            String name = user.getDisplayName();
            String email = user.getEmail();

            if (name != null) {
                tvHeaderDisplayName.setText(name);
                etFullName.setText(name);
            }
            if (email != null) {
                tvHeaderEmail.setText(email);
                etEmail.setText(email);
            }
        }
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        View.OnClickListener saveListener = v -> {
            String newName = etFullName.getText() != null ?
                    etFullName.getText().toString()
                    : "";

            String currentPass = etCurrentPassword.getText() != null
                    ? etCurrentPassword.getText().toString()
                    : "";

            String newPass = etNewPassword.getText() != null
                    ? etNewPassword.getText().toString()
                    : "";

            String confirmPass = etConfirmPassword.getText() != null
                    ? etConfirmPassword.getText().toString()
                    : "";

            // Nếu người dùng có nhập mật khẩu mới -> Gọi hàm đổi mật khẩu chính
            if (!newPass.isEmpty() || !currentPass.isEmpty()) {
                authViewModel.changeMasterPassword(currentPass, newPass, confirmPass);
            } else {
                // Chỉ đổi tên hiển thị
                authViewModel.updateDisplayName(newName);
            }
        };

        btnSaveTop.setOnClickListener(saveListener);
        btnSaveProfileBottom.setOnClickListener(saveListener);

        btnDeleteAccountForever.setOnClickListener(v ->
                showDeleteAccountConfirmationDialog());


    }

    private void showDeleteAccountConfirmationDialog() {
        DialogConfirmDeleteAccountBinding dialogBinding =
                DialogConfirmDeleteAccountBinding.inflate(LayoutInflater.from(this));

        TextInputEditText etConfirmDeleteInput =
                dialogBinding.etConfirmDeleteInput;

        MaterialButton btnCancelDelete =
                dialogBinding.btnCancelDelete;

        MaterialButton btnConfirmDeleteForever =
                dialogBinding.btnConfirmDeleteForever;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogBinding.getRoot())
                .create();

        btnCancelDelete.setOnClickListener(v -> dialog.dismiss());

        btnConfirmDeleteForever.setOnClickListener(v -> {
            String password = etConfirmDeleteInput.getText() != null
                    ? etConfirmDeleteInput.getText().toString().trim()
                    : "";

            if (password.isEmpty()) {
                etConfirmDeleteInput.setError("Vui lòng nhập mật khẩu");
                return;
            }

            dialog.dismiss();
            authViewModel.deleteAccount(password);
        });

        dialog.show();
    }

    private void observeViewModel() {
        authViewModel.getUpdateProfileResult()
                .observe(this, resource -> {
                    if (resource == null) return;

                    if (resource.getStatus() == Resource.Status.LOADING) {
                        Toast.makeText(
                                this,
                                "Đang lưu thay đổi...",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else if (resource.getStatus() == Resource.Status.SUCCESS) {
                        Toast.makeText(
                                this,
                                "Đã cập nhật hồ sơ thành công!",
                                Toast.LENGTH_SHORT
                        ).show();

                        loadUserData();
                        authViewModel.clearUpdateProfileResult();

                    } else if (resource.getStatus() == Resource.Status.ERROR) {
                        Toast.makeText(
                                this,
                                resource.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();

                        authViewModel.clearUpdateProfileResult();
                    }
        });

        authViewModel.getDeleteAccountResult()
                .observe(this, resource -> {
                    if (resource == null) return;

                    if (resource.getStatus() == Resource.Status.LOADING) {
                        Toast.makeText(
                                this,
                                "Đang tiến hành tiêu hủy tài khoản...",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else if (resource.getStatus() == Resource.Status.SUCCESS) {
                        Toast.makeText(
                                this,
                                "Tài khoản đã được xóa vĩnh viễn.",
                                Toast.LENGTH_LONG
                        ).show();

                        authViewModel.clearDeleteAccountResult();
                        Intent intent = new Intent(
                                this,
                                LoginActivity.class
                        );

                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        );

                        startActivity(intent);
                        finish();

                    } else if (resource.getStatus() == Resource.Status.ERROR) {
                        Toast.makeText(
                                this,
                                resource.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();

                        authViewModel.clearDeleteAccountResult();
                    }
        });

        authViewModel.getChangePasswordResult()
                .observe(this, resource -> {
                    if (resource == null) return;
                    if (resource.getStatus() == Resource.Status.LOADING) {
                        Toast.makeText(
                                this,
                                "Đang mã hóa lại kho mật khẩu bằng khóa AES mới...",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else if (resource.getStatus() == Resource.Status.SUCCESS) {
                        Toast.makeText(
                                this,
                                "Đổi mật khẩu chính và mã hóa lại kho thành công!",
                                Toast.LENGTH_LONG
                        ).show();

                        authViewModel.clearChangePasswordResult();
                        // Xóa sạch text trong các ô mật khẩu
                        etCurrentPassword.setText("");
                        etNewPassword.setText("");
                        etConfirmPassword.setText("");

                    } else if (resource.getStatus() == Resource.Status.ERROR) {
                        Toast.makeText(
                                this,
                                resource.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();

                        authViewModel.clearChangePasswordResult();
                    }
        });
    }
}