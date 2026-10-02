package com.example.passwordmanager.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.databinding.ActivityRegisterBinding;
import com.example.passwordmanager.ui.security.LocalAuthIntroActivity;
import com.example.passwordmanager.viewmodel.AuthViewModel;
import com.example.passwordmanager.viewmodel.AuthViewModelFactory;


public class RegisterActivity extends AppCompatActivity {
    private ActivityRegisterBinding binding;
    private EditText etFullName;
    private EditText edtEmail;
    private EditText edtPassword;
    private EditText edtConfirmPassword;

    private AuthViewModel authViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        etFullName = binding.etFullName;
        edtEmail = binding.edtEmail;
        edtPassword = binding.edtPassword;
        edtConfirmPassword = binding.edtConfirmPassword;

        Button btnRegister = binding.btnRegister;
        TextView tvBackToLogin = binding.tvBackToLogin;

        authViewModel = new ViewModelProvider(
                this,
                new AuthViewModelFactory(getApplication())
        ).get(AuthViewModel.class);

        tvBackToLogin.setOnClickListener(v -> finish());

        // Bấm nút Đăng ký -> Ủy quyền hoàn toàn cho AuthViewModel
        btnRegister.setOnClickListener(v -> {
            String fullName = etFullName.getText().toString().trim();
            String email = edtEmail.getText().toString().trim();
            String password = edtPassword.getText().toString();
            String confirmPassword = edtConfirmPassword.getText().toString();

            authViewModel.register(fullName, email, password, confirmPassword);
        });

        observeRegisterResult();
    }

    // Lắng nghe Đăng ký Firebase mạng
    private void  observeRegisterResult() {
        authViewModel.getRegisterResult().observe(this, result -> {
            if (result == null) {
                return;
            }

            switch (result.getStatus()) {
                case LOADING:
                    binding.btnRegister.setEnabled(false);
                    break;

                case SUCCESS:
                    Toast.makeText(
                            this,
                            "Đăng ký thành công",
                            Toast.LENGTH_SHORT
                    ).show();

                    startActivity(new Intent(
                            this,
                            LocalAuthIntroActivity.class
                    ));
                    finish();
                    break;

                case ERROR:
                    binding.btnRegister.setEnabled(true);
                    String message = result.getMessage();

                    if (message != null && message.contains("email address is already in use")) {
                        message = "Email này đã được đăng ký, vui lòng đăng nhập!";
                    }

                    Toast.makeText(
                            this,
                            message,
                            Toast.LENGTH_LONG
                    ).show();

                    // Xóa kết quả lỗi để không hiển thị lại nếu activity bị xoay màn hình (recreate)
                    authViewModel.clearRegisterResult();
                    break;

            }
        });
    }
}
