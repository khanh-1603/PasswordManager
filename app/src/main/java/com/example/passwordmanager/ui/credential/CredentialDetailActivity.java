package com.example.passwordmanager.ui.credential;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.data.model.CredentialItem;
import com.example.passwordmanager.data.repository.CredentialRepository;
import com.example.passwordmanager.databinding.ActivityCredentialDetailBinding;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.ui.security.UnlockActivity;
import com.example.passwordmanager.utils.CredentialUIUtils;
import com.example.passwordmanager.viewmodel.CredentialViewModel;
import com.example.passwordmanager.viewmodel.CredentialViewModelFactory;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;


/**
 * Activity hiển thị và chỉnh sửa chi tiết tài khoản (Credential Detail & Edit).
 * Nhận credentialId từ Intent, quan sát CredentialViewModel để hiển thị dữ liệu và cập nhật.
 */
public class CredentialDetailActivity extends AppCompatActivity {
    private ActivityCredentialDetailBinding binding;
    private FrameLayout flBigIcon;
    private ImageView ivBigAppIcon;
    private TextView tvDetailAppName;
    private TextView tvLastUpdated;
    private AutoCompleteTextView actvEditCategory;
    private TextInputEditText edtEditAppName;
    private TextView tvEditAutofillTarget;
    private String autofillDomain;
    private String autofillPackage;
    private TextInputEditText edtEditUsername;
    private TextInputEditText edtEditPassword;
    private TextInputEditText edtEditNotes;
    private MaterialButton btnDecryptPassword;

    private CredentialItem currentCredential;
    private String credentialId;

    private CredentialViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCredentialDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        credentialId = getIntent().getStringExtra("credential_id");
        if (credentialId == null || credentialId.isEmpty()) {
            Toast.makeText(
                    this,
                    "Không tìm thấy credential",
                    Toast.LENGTH_SHORT
            ).show();

            finish();
            return;
        }

        initViews();
        setupViewModel();
        setupListeners();

        // Tải thông tin tài khoản từ ViewModel
        viewModel.loadCredential(credentialId);
    }

    private void initViews() {
        MaterialToolbar topBar = binding.topBarEdit;
        topBar.setNavigationOnClickListener(v -> finish());

        flBigIcon = binding.flBigIcon;
        ivBigAppIcon = binding.ivBigAppIcon;

        tvDetailAppName = binding.tvDetailAppName;
        tvLastUpdated = binding.tvLastUpdated;

        actvEditCategory = binding.actvEditCategory;
        edtEditAppName = binding.edtEditAppName;
        tvEditAutofillTarget = binding.tvEditAutofillTarget;
        edtEditUsername = binding.edtEditUsername;
        edtEditPassword = binding.edtEditPassword;
        edtEditNotes = binding.edtEditNotes;

        btnDecryptPassword = binding.btnDecryptPassword;
    }

    private void setupViewModel() {
        CredentialViewModelFactory factory;

        try {
            factory = CredentialViewModelFactory.create(this);
        } catch (IllegalStateException e) {
            finish();
            return;
        }

        viewModel = new ViewModelProvider(this, factory)
                .get(CredentialViewModel.class);

        // Lắng nghe dữ liệu tài khoản chi tiết
        viewModel.getSelectedCredential()
                .observe(this, credential -> {
            if (credential != null) {
                currentCredential = credential;
                displayCredentialData(credential);
            }
        });

        // Lắng nghe trạng thái Ẩn/Hiện Mật khẩu
        viewModel.getIsPasswordVisible()
                .observe(this, isVisible -> {
            if (isVisible) {
                edtEditPassword.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

                btnDecryptPassword.setIconResource(R.drawable.ic_visibility_off);
                btnDecryptPassword.setText("Ẩn");

            } else {
                edtEditPassword.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD);

                btnDecryptPassword.setIconResource(R.drawable.ic_visibility);
                btnDecryptPassword.setText("Giải mã");
            }
            edtEditPassword.setSelection(edtEditPassword.getText().length());
        });

        // Lắng nghe điểm số Độ mạnh mật khẩu
        viewModel.getPasswordStrength()
                .observe(this, this::updateStrengthBarsUI);

        // Lắng nghe kết quả thao tác CRUD
        viewModel.getOperation()
                .observe(this, operation -> {
            if (operation == null) return;

            switch (operation) {
                case UPDATE:
                    Toast.makeText(
                            this,
                            "Cập nhật thành công",
                            Toast.LENGTH_SHORT
                    ).show();

                    viewModel.resetOperation();
                    finish();
                    break;

                case DELETE:
                    Toast.makeText(
                            this,
                            "Đã xóa tài khoản",
                            Toast.LENGTH_SHORT
                    ).show();

                    viewModel.resetOperation();
                    finish();
                    break;
            }
        });

        // Lắng nghe thông báo lỗi
        viewModel.getErrorMessage()
                .observe(this, error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(
                        this,
                        error,
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        //  Observe sự kiện yêu cầu Unlock từ ViewModel
        viewModel.getRequireUnlockEvent()
                .observe(this, shouldUnlock -> {
                    if (Boolean.TRUE.equals(shouldUnlock)) {
                        Intent intent = new Intent(
                                this,
                                UnlockActivity.class
                        );

                        unlockLauncher.launch(intent);

                        // Báo ViewModel là đã xử lý mở Activity
                        viewModel.onUnlockEventHandled();
                    }
                });
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSaveChanges.setOnClickListener(v -> saveChanges());
        binding.btnDeleteCredential.setOnClickListener(v -> showDeleteConfirmationDialog());
        btnDecryptPassword.setOnClickListener(v -> viewModel.requestTogglePasswordVisibility());

        binding.btnCopyUsername.setOnClickListener(v -> {
            viewModel.copyToClipboard(
                    this,
                    "Username",
                    edtEditUsername.getText().toString()
            );

            Toast.makeText(
                    this,
                    "Đã sao chép tên đăng nhập",
                    Toast.LENGTH_SHORT
            ).show();
        });


        // Nút Copy Password (Lấy mật khẩu từ currentCredential)
        binding.btnCopyPassword.setOnClickListener(v -> {
            String pwd = currentCredential != null
                    ? currentCredential.getPassword()
                    : "";

            viewModel.requestCopyPassword("Mật khẩu", pwd);
        });

        edtEditPassword.addTextChangedListener(new TextWatcher() {
            @Override public void onTextChanged(
                    CharSequence s, int start, int before, int count
            ) {
                viewModel.evaluatePasswordStrength(s.toString());
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
        });

        // Khi click nút chọn Website
        binding.btnEditWebsite.setOnClickListener(v -> {
            CredentialUIUtils.showWebsiteDialog(this, autofillDomain, domain -> {
                autofillDomain = domain;
                autofillPackage = null;
                updateEditAutofillTarget();
            });
        });

// Khi click nút chọn App
        binding.btnEditApp.setOnClickListener(v -> {
            CredentialUIUtils.showAppPicker(this, (packageName, appName) -> {
                autofillPackage = packageName;
                autofillDomain = null;
                updateEditAutofillTarget();
            });
        });

        String[] categories = new String[]{"Ngân hàng", "Mạng xã hội", "Công việc", "Mua sắm", "Email", "Trò chơi", "Khác"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, categories
        );

        actvEditCategory.setOnItemClickListener(
                (parent, view, position, id) -> {
            String category = categories[position];
            updateCategoryIcon(category);
        });
        actvEditCategory.setAdapter(adapter);
    }

    private void displayCredentialData(CredentialItem credential) {
        tvDetailAppName.setText(credential.getTitle().toUpperCase());
        tvLastUpdated.setText(viewModel
                .formatUpdatedTime(credential.getUpdatedAt())
        );

        edtEditAppName.setText(credential.getTitle());
        edtEditUsername.setText(credential.getUsername());
        edtEditPassword.setText(credential.getPassword());
        edtEditNotes.setText(credential.getNote());

        String category = credential.getCategory();

        if (category == null || category.isEmpty()) {
            category = "Khác";
        }

        actvEditCategory.setText(category, false);
        updateCategoryIcon(category);

        autofillDomain = credential.getAutofillDomain();
        autofillPackage = credential.getAutofillPackage();
        updateEditAutofillTarget();

        viewModel.evaluatePasswordStrength(credential.getPassword());
    }

    private void saveChanges() {
        if (currentCredential == null) return;

        viewModel.validateAndAddOrUpdate(
                currentCredential.getId(),
                actvEditCategory.getText().toString(),
                edtEditAppName.getText().toString(),
                autofillDomain,
                autofillPackage,
                edtEditUsername.getText().toString(),
                edtEditPassword.getText().toString(),
                edtEditNotes.getText().toString()
        );
    }

    private void updateCategoryIcon(String category) {
        CredentialUIUtils.updateCategoryIcon(
                this, category, ivBigAppIcon, flBigIcon
        );
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

    private void showDeleteConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa tài khoản")
                .setMessage("Bạn có chắc chắn muốn xóa tài khoản này không?")
                .setPositiveButton("Xóa", (dialog, which) -> viewModel.deleteCredential(credentialId))
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void updateEditAutofillTarget() {
        tvEditAutofillTarget.setError(null);

        String targetText = CredentialUIUtils.getAutofillTargetText(
                this, autofillDomain, autofillPackage
        );

        tvEditAutofillTarget.setText(targetText);
    }

    /// Nhận kết quả từ UnlockActivity
    private final ActivityResultLauncher<Intent> unlockLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {

                if (result.getResultCode() == RESULT_OK) {
                    // Unlock thành công -> Báo ViewModel xử lý tiếp
                    viewModel.onUnlockSuccess(this);
                }
            });
}
