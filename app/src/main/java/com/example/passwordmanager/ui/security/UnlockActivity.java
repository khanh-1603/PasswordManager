package com.example.passwordmanager.ui.security;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.MainActivity;
import com.example.passwordmanager.autofill.AutofillAuthSession;
import com.example.passwordmanager.databinding.ActivityPinEntryBinding;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.utils.PinKeypadController;
import com.example.passwordmanager.viewmodel.AuthViewModel;
import com.example.passwordmanager.viewmodel.AuthViewModelFactory;
import com.example.passwordmanager.viewmodel.Resource;
import com.example.passwordmanager.viewmodel.SecurityViewModel;
import com.example.passwordmanager.viewmodel.SecurityViewModelFactory;
import com.google.firebase.auth.FirebaseUser;


public class UnlockActivity extends AppCompatActivity {
    private ActivityPinEntryBinding pinEntryBinding;
    private LocalAuthManager localAuthManager;
    private SecurityViewModel securityViewModel;
    // Dùng cho Autofill khi không mở khóa được bằng PIN/vân tay (Local Auth tắt,
    // PIN bị khóa...): xác thực bằng Master Password ngay trong luồng Autofill.
    private AuthViewModel authViewModel;
    private AlertDialog masterPasswordDialog;
    private boolean awaitingMasterPassword = false;

    private String uid;
    private boolean finished = false;

    // Biến lưu trữ BiometricPrompt đang chạy
    private BiometricPrompt currentBiometricPrompt;

    // Chỉ có giá trị khi đang ở màn hình nhập PIN (activity_pin_entry đang hiển thị).
    private PinKeypadController pinKeypadController;

    // =========================================================
    // AUTOFILL - SELECT CREDENTIAL
    // =========================================================

    private boolean isAutofillUnlockAll = false;

    private AutofillId autofillUsernameId;
    private AutofillId autofillPasswordId;
    private String autofillWebDomain;
    private String autofillPackageName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        // Chặn bấm Back
                    }
                }
        );

        // Không cần layout: Android tự hiển thị prompt hệ thống.
        localAuthManager = AppContainer.getInstance(this)
                .getLocalAuthManager();

        securityViewModel = new ViewModelProvider(
                this,
                new SecurityViewModelFactory(getApplication())
        ).get(SecurityViewModel.class);

        authViewModel = new ViewModelProvider(
                this,
                new AuthViewModelFactory(getApplication())
        ).get(AuthViewModel.class);

        FirebaseUser user = securityViewModel.getUser();

        if (user == null) {
            Toast.makeText(
                    this,
                    "Chưa đăng nhập tài khoản nào",
                    Toast.LENGTH_SHORT
            ).show();

            if (isAutofillUnlockAll) {
                setResult(RESULT_CANCELED);
                finish();
            } else {
                gotoLogin();
            }
            return;
        }

        uid = user.getUid();

        Intent intent = getIntent();

        isAutofillUnlockAll = "AUTOFILL_UNLOCK_ALL".equals(
                intent.getStringExtra("source")
        );

        if (isAutofillUnlockAll) {
            autofillWebDomain = intent.getStringExtra("web_domain");
            autofillPackageName = intent.getStringExtra("package_name");
            autofillUsernameId = intent.getParcelableExtra("username_id");
            autofillPasswordId = intent.getParcelableExtra("password_id");
        }

        // Lắng nghe quyết định điều hướng từ ViewModel
        observeSecurityResult();
        observeMasterPasswordUnlock();

        securityViewModel.checkUnlockStatus(uid);
    }

    // =========================================================
    // BIOMETRIC SCREEN
    // =========================================================

    private void authenticateLocal() {
        // Lưu lại instance của BiometricPrompt
        currentBiometricPrompt = localAuthManager.BiometricAuthenticate(
                this,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(
                            int errorCode,
                            @NonNull CharSequence errString
                    ) {
                        super.onAuthenticationError(errorCode, errString);
                        Log.d(
                                "BIOMETRIC",
                                "ERROR code=" + errorCode + " message=" + errString
                        );

                        securityViewModel.onBiometricError(uid, errorCode);
                    }

                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result
                    ) {
                        super.onAuthenticationSucceeded(result);
                        Log.d("BIOMETRIC", "SUCCESS");

                        securityViewModel.onBiometricSuccess(uid);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        boolean shouldFallback = securityViewModel.onBiometricFail(uid);

                        if (shouldFallback) {
                            if (currentBiometricPrompt != null) {
                                currentBiometricPrompt.cancelAuthentication();
                                currentBiometricPrompt = null;
                            }

                            // Hiển thị PIN do app quản lý.
                            showPinScreen();
                        }
                    }
                }
        );
    }

    // =========================================================
    // PIN SCREEN (activity_pin_entry)
    // =========================================================

    private void showPinScreen() {
        pinEntryBinding = ActivityPinEntryBinding.inflate(getLayoutInflater());
        setContentView(pinEntryBinding.getRoot());

        pinEntryBinding.btnForgotPin.setOnClickListener(v -> {
            if (isAutofillUnlockAll) {
                setResult(RESULT_CANCELED);
                finished = true;
                finish();
            } else {
                gotoLogin();
            }
        });

        pinKeypadController = new PinKeypadController(
                pinEntryBinding,
                pin -> securityViewModel.unlockWithPin(uid, pin)
        );
    }

    // =========================================================
    // NAVIGATION
    // =========================================================

    private void gotoMasterPassword() {
        if (finished) {
            return;
        }

        finished = true;
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra("master_password_unlock", true);
        startActivity(intent);
        finish();
    }

    private void gotoLogin() {
        if (finished) {
            return;
        }

        finished = true;

        CryptoSession.clear();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void observeSecurityResult() {
        securityViewModel.getUnlockDestination()
                .observe(this, destination -> {

                    if (destination == null || finished) {
                        return;
                    }

                    switch (destination) {
                        case GOTO_LOGIN:
                            if (isAutofillUnlockAll) {
                                setResult(RESULT_CANCELED);
                                finished = true;
                                finish();
                                return;
                            }
                            gotoLogin();
                            break;

                        case GOTO_MASTER_PASSWORD:
                            if (isAutofillUnlockAll) {
                                showAutofillMasterPasswordDialog();
                                return;
                            }
                            gotoMasterPassword();
                            break;

                        case PROMPT_BIOMETRIC:
                            authenticateLocal();
                            break;

                        case PROMPT_PIN:
                            showPinScreen();
                            break;

                        case UNLOCKED:
                            if (isAutofillUnlockAll) {
                                AutofillAuthSession.authenticate();
                                returnAutofillFillResponse();
                                return;
                            }

                            finished = true;
                            setResult(RESULT_OK);

                            if (isTaskRoot()) {
                                Intent mainIntent = new Intent(this, MainActivity.class);
                                mainIntent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                );
                                startActivity(mainIntent);                            }

                            finish();
                    }
                });

        securityViewModel.getVaultKeyResult()
                .observe(this, result -> {

                    if (result == null
                            || result.getStatus() == Resource.Status.LOADING
                    ) {
                        return;
                    }

                    if (result.getStatus() == Resource.Status.SUCCESS) {
                        if (isTaskRoot()) {
                            startActivity(new Intent(this, MainActivity.class));
                        }

                        finish();
                        return;
                    }

                    if (result.getStatus() == Resource.Status.ERROR) {
                        Toast.makeText(
                                this,
                                result.getMessage(),
                                Toast.LENGTH_SHORT
                        ).show();

                        // PIN sai -> xóa PIN vừa nhập để người dùng nhập lại từ đầu.
                        if (pinKeypadController != null) {
                            pinKeypadController.reset();
                        }
                    }
                });

        securityViewModel.getAutofillFillResponseResult()
                .observe(this, fillResponse -> {
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(
                            AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                            fillResponse
                    );

                    setResult(RESULT_OK, resultIntent);
                    finished = true;
                    finish();
        });

        securityViewModel.getAutofillErrorResult()
                .observe(this, error -> {
                    if (Boolean.TRUE.equals(error)) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
        });
    }

    // =========================================================
    // AUTOFILL - MASTER PASSWORD
    // =========================================================

    /**
     * Hỏi Master Password ngay trong luồng Autofill khi không dùng được
     * PIN/vân tay. Sai mật khẩu thì dialog giữ nguyên để nhập lại.
     */
    private void showAutofillMasterPasswordDialog() {
        if (finished || (masterPasswordDialog != null
                && masterPasswordDialog.isShowing())
        ) {
            return;
        }

        final int pad = (int) (20 * getResources().getDisplayMetrics().density);

        final EditText edtPassword = new EditText(this);
        edtPassword.setHint("Master Password");
        edtPassword.setSingleLine(true);
        edtPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        // Đây là Master Password của chính app, không phải field cần
        // Autofill xử lý -> loại khỏi AssistStructure để không service
        // Autofill nào (kể cả service của chính app) coi đây là field
        // cần lưu/điền.
        edtPassword.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);

        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(
                edtPassword,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                )
        );

        masterPasswordDialog = new AlertDialog.Builder(this)
                .setTitle("Mở khóa Vault")
                .setMessage(
                        "Nhập Master Password để điền mật khẩu. "
                                + "Bật PIN/vân tay trong Cài đặt để mở khóa nhanh hơn."
                )
                .setView(container)
                .setCancelable(false)
                // listener thật được gán trong OnShowListener để dialog KHÔNG tự đóng khi sai
                .setPositiveButton("Mở khóa", null)
                .setNegativeButton("Hủy", (dialog, which) -> cancelAutofill())
                .create();

        masterPasswordDialog.setOnShowListener(dialog -> {
            Button positive = masterPasswordDialog.getButton(AlertDialog.BUTTON_POSITIVE);

            positive.setOnClickListener(v -> {
                String password = edtPassword.getText().toString();

                if (password.isEmpty()) {
                    edtPassword.setError("Vui lòng nhập Master Password");
                    return;
                }

                positive.setEnabled(false);
                awaitingMasterPassword = true;
                authViewModel.unlockWithMasterPassword(uid, password);
            });

            edtPassword.requestFocus();
        });

        masterPasswordDialog.show();

        if (masterPasswordDialog.getWindow() != null) {
            masterPasswordDialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            );
        }
    }

    private void observeMasterPasswordUnlock() {
        authViewModel.getMasterPasswordUnlockResult().observe(this, result -> {
            // Chỉ xử lý kết quả của lần nhập do dialog này gửi đi
            // (LiveData giữ giá trị cũ, tránh bị phát lại khi Activity recreate).
            if (!awaitingMasterPassword
                    || result == null
                    || result.getStatus() == Resource.Status.LOADING
            ) {
                return;
            }

            awaitingMasterPassword = false;

            if (result.getStatus() == Resource.Status.SUCCESS) {
                dismissMasterPasswordDialog();

                // CryptoSession đã được UnlockWithMasterPasswordUseCase khởi tạo.
                AutofillAuthSession.authenticate();
                returnAutofillFillResponse();
                return;
            }

            // Sai mật khẩu: giữ dialog, cho nhập lại.
            Toast.makeText(this, result.getMessage(), Toast.LENGTH_SHORT).show();

            if (masterPasswordDialog != null) {
                Button positive = masterPasswordDialog.getButton(AlertDialog.BUTTON_POSITIVE);

                if (positive != null) {
                    positive.setEnabled(true);
                }
            }
        });

        // Sai quá số lần cho phép -> xóa vault local (giống LoginActivity) rồi hủy.
        authViewModel.getVaultWipeEvent().observe(this, wipedUid -> {
            if (wipedUid == null || wipedUid.isEmpty()) {
                return;
            }

            securityViewModel.wipeLocalVault(wipedUid);
            dismissMasterPasswordDialog();
            cancelAutofill();
        });
    }

    private void cancelAutofill() {
        finished = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    private void dismissMasterPasswordDialog() {
        if (masterPasswordDialog != null && masterPasswordDialog.isShowing()) {
            masterPasswordDialog.dismiss();
        }

        masterPasswordDialog = null;
    }

    @Override
    protected void onDestroy() {
        // Tránh "window leaked" nếu Activity bị hủy khi dialog còn hiện.
        dismissMasterPasswordDialog();
        super.onDestroy();
    }

    /**
     * Tạo và trả FillResponse về cho Android Autofill
     * sau khi người dùng xác thực thành công.
     *
     * Method này chỉ dùng cho luồng Autofill.
     * Không điều hướng sang MainActivity.
     */
    private void returnAutofillFillResponse() {
        securityViewModel.prepareAutofillFillResponse(
                uid,
                autofillWebDomain,
                autofillPackageName,
                autofillUsernameId,
                autofillPasswordId
        );
    }
}
