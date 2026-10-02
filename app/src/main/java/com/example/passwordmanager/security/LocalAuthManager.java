package com.example.passwordmanager.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.concurrent.Executor;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Quản lý Xác thực Cục bộ (Local Authentication - Biometrics).
 *
 * Chịu trách nhiệm:
 * 1. Kiểm tra phần cứng thiết bị có hỗ trợ Vân tay / PIN hay không (`isAvailable`).
 * 2. Lưu/Đọc cài đặt Bật/Tắt công tắc xác thực cục bộ của từng User (`isEnabled`, `setEnabled`).
 * 3. Hiển thị hộp thoại System Prompt xác thực Vân tay/PIN (`authenticate`).
 */
public class LocalAuthManager {
    private static final String PREF_NAME = "vault_security";

    private static final String BIOMETRIC_ENABLED_PREFIX = "biometric_enabled_";

    private static final String KEY_PIN_FAILED_ATTEMPTS = "failed_attempts_";

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private static final int PBKDF2_ITERATIONS = 10_000;

    private static final int KEY_LENGTH = 256;

    private static final String INTRO_SHOWN_PREFIX = "intro_shown_";

    private final VaultKeyStore vaultKeyStore;

    private final Context context;
    private final SharedPreferences preferences;

    public LocalAuthManager(Context context) {
        Context appContext = context.getApplicationContext();
        this.context = appContext;
        preferences=
                this.context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );
        vaultKeyStore = new VaultKeyStore(appContext);
    }

    /// Kiểm tra người dùng đã xem màn hình Intro LocalAuth lần nào chưa
    public boolean isIntroShown(String uid) {
        return preferences.getBoolean(
                INTRO_SHOWN_PREFIX + uid,
                false
        );
    }

    /// Đánh dấu đã hiển thị màn hình Intro cho UID này
    public void setIntroShown(String uid, boolean shown) {
        preferences.edit()
                .putBoolean(INTRO_SHOWN_PREFIX + uid, shown)
                .apply();
    }

    /**
     * Kiểm tra phần cứng thiết bị có sẵn sàng cho xác thực sinh trắc học hoặc PIN hay không.
     * @return true nếu thiết bị có phần cứng hỗ trợ và đã đăng ký vân tay/PIN trong Settings hệ thống.
     */
    public boolean isBiometricAvailable() {
        BiometricManager manager = BiometricManager.from(context);

        int authenticators = getAllowedBiometricAuthenticators();
        int result = manager.canAuthenticate(authenticators);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

     /// Bật hoặc tắt công tắc Mở khóa cục bộ cho UID người dùng tương ứng.
    public void setEnabled(String uid, boolean enabled) {
        preferences.edit()
                .putBoolean(BIOMETRIC_ENABLED_PREFIX + uid, enabled)
                .apply();
    }

    /**
     * Kiểm tra xem người dùng hiện tại đã BẬT công tắc Xác thực cục bộ trong Cài đặt ứng dụng hay chưa.
     * @return true nếu người dùng đã bật công tắc trong LocalAuthManagementActivity.
     */
    public boolean isEnabled(String uid) {
        boolean biometric =  preferences
                .getBoolean(BIOMETRIC_ENABLED_PREFIX + uid, false);

        return biometric && hasPin(uid);
    }

    /// Xóa cờ cài đặt mở khóa cục bộ của người dùng khi đăng xuất hoặc xóa tài khoản.
    public void clear(String uid) {
        preferences.edit()
                .remove(BIOMETRIC_ENABLED_PREFIX + uid)
                .remove(KEY_PIN_FAILED_ATTEMPTS + uid)
                .apply();

        vaultKeyStore.clear(uid);
    }

    /// Hiển thị hộp thoại System BiometricPrompt của Android để xác thực người dùng bằng Vân tay / PIN.
    public BiometricPrompt BiometricAuthenticate(
            FragmentActivity activity,
            @NonNull BiometricPrompt.AuthenticationCallback callback
    ) {
        Executor executor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt prompt = new BiometricPrompt(
                activity,
                executor,
                callback
        );

        BiometricPrompt.PromptInfo promptInfo =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Mở khóa Vault")
                        .setSubtitle(
                                "Xác thực bằng sinh trắc học hoặc mã khóa thiết bị"
                        )
                        .setAllowedAuthenticators(
                                getAllowedBiometricAuthenticators()
                        )
                        .setNegativeButtonText("Chuyển sang PIN")
                        .build();

        prompt.authenticate(promptInfo);
        return prompt;
    }

    /**
     * Lấy các cơ chế xác thực được phép sử dụng.
     * BIOMETRIC_STRONG.
     */
    public int getAllowedBiometricAuthenticators() {
        return BiometricManager.Authenticators.BIOMETRIC_STRONG;
    }

    // ==================================
    // PIN
    // ==================================


    /**
     * Kiểm tra người dùng đã thiết lập PIN trong VaultKeyStore hay chưa.
     */
    public boolean hasPin(@NonNull String uid) {
        return vaultKeyStore.hasPinHash(uid);
    }

    /**
     * Lưu PIN: Tạo PIN Salt riêng, băm PBKDF2 rồi mã hóa và lưu vào VaultKeyStore.
     */
    public void savePin(
            @NonNull String uid,
            @NonNull String pin
    ) {

        // Tạo PIN Salt riêng biệt (16 bytes Base64)
        String pinSaltBase64 = CryptoManager.generateSaltBase64();
        byte[] salt = CryptoManager.decodeSalt(pinSaltBase64);

        // Băm PIN thô bằng PBKDF2
        String pinHash = derivePinHash(pin, salt);

        try {
            // Mã hóa & Lưu PIN Hash + PIN Salt vào VaultKeyStore
            vaultKeyStore.savePinSalt(uid, pinSaltBase64);
            vaultKeyStore.savePinHash(uid, pinHash);

            // 4. Đặt lại số lần nhập sai về 0
            resetPinFailedAttempts(uid);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Không thể lưu PIN mã hóa vào VaultKeyStore", e
            );
        }
    }

    /**
     * Kiểm tra PIN nhập vào bằng cách giải mã PIN Hash & PIN Salt từ VaultKeyStore.
     */
    public boolean verifyPin(
            @NonNull String uid,
            @NonNull String pin
    ) {
        if (isPinLocked(uid)) {
            return false;
        }

        try {
            String savedHash = vaultKeyStore.loadPinHash(uid);
            String pinSaltBase64 = vaultKeyStore.loadPinSalt(uid);

            if (savedHash == null || pinSaltBase64 == null) {
                return false;
            }

            byte[] salt = CryptoManager.decodeSalt(pinSaltBase64);
            String inputHash = derivePinHash(pin, salt);

            boolean matched = MessageDigest.isEqual(
                    savedHash.getBytes(StandardCharsets.UTF_8),
                    inputHash.getBytes(StandardCharsets.UTF_8)
            );

            if (matched) {
                resetPinFailedAttempts(uid);
                return true;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        increasePinFailedAttempts(uid);
        return false;
    }

    /**
     * Trả về số lần nhập PIN sai hiện tại.
     */
    public int getPinFailedAttempts(@NonNull String uid) {
        return preferences.getInt(
                KEY_PIN_FAILED_ATTEMPTS + uid,
                0
        );
    }

    /**
     * Kiểm tra PIN đã bị khóa do nhập sai quá số lần cho phép hay chưa.
     */
    public boolean isPinLocked(@NonNull String uid) {
        return getPinFailedAttempts(uid) >= MAX_FAILED_ATTEMPTS;
    }

    /**
     * Trả về số lần nhập sai tối đa cho phép.
     */
    public int getPinMaxFailedAttempts() {
        return MAX_FAILED_ATTEMPTS;
    }

    /**
     * Đặt lại số lần nhập PIN sai về 0.
     */
    public void resetPinFailedAttempts(@NonNull String uid) {
        preferences.edit()
                .putInt(
                        KEY_PIN_FAILED_ATTEMPTS + uid,
                        0
                )
                .apply();
    }

    /// Tăng số lần nhập sai từ người dùng
    private void increasePinFailedAttempts(@NonNull String uid) {
        int attempts = getPinFailedAttempts(uid);

        preferences.edit()
                .putInt(
                        KEY_PIN_FAILED_ATTEMPTS + uid,
                        attempts + 1
                )
                .apply();
    }

    /// Tạo pin từ PBDKF2 + salt
    private String derivePinHash(
            @NonNull String pin,
            @NonNull byte[] salt
    ) {
        PBEKeySpec spec = new PBEKeySpec(
                pin.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_LENGTH
        );

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance(
                            "PBKDF2WithHmacSHA256"
                    );

            byte[] derivedKey = factory
                    .generateSecret(spec)
                    .getEncoded();

            return bytesToHex(derivedKey);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Không thể tạo hash PIN",
                    e
            );

        } finally {
            spec.clearPassword();
        }
    }

    private String bytesToHex(@NonNull byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }

        return result.toString();
    }
}
