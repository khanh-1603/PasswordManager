package com.example.passwordmanager.ui.credential;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.data.repository.CredentialRepository;
import com.example.passwordmanager.databinding.ActivityAddCredentialBinding;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.utils.CredentialUIUtils;
import com.example.passwordmanager.viewmodel.CredentialViewModel;
import com.example.passwordmanager.viewmodel.CredentialViewModelFactory;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;


public class AddCredentialActivity extends AppCompatActivity {
    private ActivityAddCredentialBinding binding;
    private AutoCompleteTextView actvAddCategory;
    private TextInputEditText edtTitle;
    private MaterialButton btnSelectWebsite;
    private MaterialButton btnSelectApp;
    private TextView tvAutofillTarget;
    private String autofillDomain;
    private String autofillPackage;
    private TextInputEditText edtUsername;
    private TextInputEditText edtPassword;
    private ImageView toogleVisibility;
    private ImageView generateRandom;
    private TextInputEditText edtNotes;
    private MaterialButton btnCancelAdd;
    private MaterialButton btnSaveCredential;
    private boolean isPasswordVisibleInAdd = false;

    private CredentialViewModel viewModel;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddCredentialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // ====================================
        // View
        // ====================================

        actvAddCategory = binding.actvAddCategory;
        edtTitle = binding.edtAppName;
        edtUsername = binding.edtUsername;
        edtPassword = binding.edtPasswordAdd;
        edtNotes = binding.edtNotes;

        btnSelectWebsite = binding.btnSelectWebsite;
        btnSelectApp = binding.btnSelectApp;
        tvAutofillTarget = binding.tvAutofillTarget;
        toogleVisibility = binding.ivToggleVisibility;
        generateRandom = binding.ivGenerateRandom;
        btnCancelAdd = binding.btnCancelAdd;
        btnSaveCredential = binding.btnSaveCredential;

        // ====================================
        // ViewModel
        // ====================================

        CredentialViewModelFactory factory;

        try {
            factory = CredentialViewModelFactory.create(this);
        } catch (IllegalStateException e) {
            finish();
            return;
        }

        viewModel = new ViewModelProvider(
                this,
                factory
        ).get(CredentialViewModel.class);

        viewModel.getOperation().observe(this, operation -> {
            if (operation == null) return;
            switch (operation) {
                case ADD:
                    Toast.makeText(this, "Thêm tài khoản thành công", Toast.LENGTH_SHORT).show();
                    viewModel.resetOperation();
                    finish();
                    break;
            }
        });

        // ====================================
        // Observe operation
        // ====================================

        viewModel.getOperation().observe(this, operation -> {
            if (operation == null) {
                return;
            }

            switch (operation) {
                case ADD:
                    viewModel.resetOperation();
                    finish();
                    break;

                default:
                    break;
            }
        });

        // ====================================
        // Observe password
        // ====================================

        viewModel.getPasswordStrength()
                .observe(this, this::updateStrengthBarsUI);

        edtPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(
                    CharSequence s, int start, int before, int count
            ) {
                viewModel.evaluatePasswordStrength(s.toString());
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
        });

        generateRandom.setOnClickListener(v -> {
            String generatedPassword = viewModel.generateRandomPassword(16);
            edtPassword.setText(generatedPassword);
        });

        toogleVisibility.setOnClickListener(v -> {
            isPasswordVisibleInAdd = !isPasswordVisibleInAdd;

            if (isPasswordVisibleInAdd) {
                // Hiện mật khẩu
                edtPassword.setTransformationMethod(
                        HideReturnsTransformationMethod.getInstance()
                );
                toogleVisibility.setImageResource(R.drawable.ic_visibility_off);

            } else {
                // Ẩn mật khẩu dạng dấu chấm
                edtPassword.setTransformationMethod(
                        PasswordTransformationMethod.getInstance()
                );
                toogleVisibility.setImageResource(R.drawable.ic_visibility);
            }

            // Giữ con trỏ ở cuối câu
            if (edtPassword.getText() != null) {
                edtPassword.setSelection(edtPassword.getText().length());
            }
                });

        // ====================================
        // Observe error
        // ====================================

        viewModel.getErrorMessage().observe(
                this,
                errorMessage -> {
                    if (errorMessage != null && !errorMessage.isEmpty()) {
                        Toast.makeText(
                                this,
                                "Không thể tải dữ liệu: "
                                + errorMessage,
                                Toast.LENGTH_LONG
                        ).show();
                    }
        });

        // ====================================
        // Add
        // ====================================

        btnCancelAdd.setOnClickListener(v -> finish());

        btnSaveCredential.setOnClickListener(v -> saveCredential());


        String[] categories = new String[]{"Ngân hàng", "Mạng xã hội", "Công việc", "Mua sắm", "Email", "Trò chơi", "Khác"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, categories
        );

        actvAddCategory.setAdapter(adapter);
        actvAddCategory.setThreshold(0);
        actvAddCategory.setText(categories[0], false);


        btnSelectWebsite.setOnClickListener(v -> {
            CredentialUIUtils.showWebsiteDialog(
                    this, autofillDomain, domain -> {
                autofillDomain = domain;
                autofillPackage = null;
                updateAutofillTarget();
            });
        });

        btnSelectApp.setOnClickListener(v -> {
            CredentialUIUtils.showAppPicker(
                    this, (packageName, appName) -> {
                autofillPackage = packageName;
                autofillDomain = null;
                updateAutofillTarget();
            });
        });
    }

    private void saveCredential() {
        viewModel.validateAndAddOrUpdate(
                null,
                actvAddCategory.getText().toString(),
                edtTitle.getText().toString(),
                autofillDomain,
                autofillPackage,
                edtUsername.getText().toString(),
                edtPassword.getText().toString(),
                edtNotes.getText().toString()
        );
    }


    private void updateAutofillTarget() {
        tvAutofillTarget.setError(null);
        String targetText = CredentialUIUtils.getAutofillTargetText(
                this, autofillDomain, autofillPackage
        );

        tvAutofillTarget.setText(targetText);
    }

    private void updateStrengthBarsUI(int score) {
        CredentialUIUtils.updateStrengthBarsUI(
                this, score,
                binding.strengthBar1,
                binding.strengthBar2,
                binding.strengthBar3,
                binding.strengthBar4,
                binding.tvStrengthText
        );
    }
}
