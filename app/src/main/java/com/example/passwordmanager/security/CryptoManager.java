package com.example.passwordmanager.security;



import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts/decrypts các trường nhập cảm trong credential bằng AES-GCM.
 *
 * Khóa AES được tạo từ PBKDF2 và lưu ở trong Android Keystore.
 * Mỗi lần encrypt sẽ tạo 1 khóa IV 12-byte mới. Khóa IV được lưu cùng
 * với bản ciphertext để giải mã.
 */
public class CryptoManager {
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int AES_KEY_SIZE = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int GCM_TAG_SIZE = 128;
    private final SecretKey aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    // Constructor cho việc tạo khóa khi login lần đầu
    public CryptoManager(String masterPassword, byte[] salt) {
        if (masterPassword == null || masterPassword.isEmpty()) {
            throw new IllegalArgumentException("Master password không hợp lệ");
        }

        if (salt == null || salt.length != SALT_SIZE) {
            throw new IllegalArgumentException("Salt không hợp lệ");
        }

        this.aesKey = deriveKey(masterPassword, salt);
    }

    // Constructor cho việc xác thực bằng biometric và pin
    public CryptoManager(SecretKey aesKey) {
        if (aesKey == null) {
            throw new IllegalArgumentException("AES key không được null");
        }

        this.aesKey = aesKey;
    }

    /**
     * Tạo salt mới cho tài khoản/vault.
     */

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_SIZE];

        new SecureRandom().nextBytes(salt);

        return salt;
    }

    /**
     * Master Password
     *      ↓
     * PBKDF2
     *      ↓
     * AES-256 key
     */

    private SecretKey deriveKey(String masterPassword, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    masterPassword.toCharArray(),
                    salt,
                    PBKDF2_ITERATIONS,
                    AES_KEY_SIZE
            );

            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);

            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();

            return new SecretKeySpec(keyBytes, "AES");

        } catch (Exception e) {
            throw new IllegalStateException("Không thể tạo AES key", e);
        }
    }

    /**
     * Plaintext → AES-GCM → ciphertext
     *
     * Kết quả lưu:
     *
     * Base64(IV + ciphertext + authentication tag)
     */

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_SIZE];

            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);

            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_SIZE, iv);

            cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[iv.length + ciphertext.length];

            System.arraycopy(iv, 0, result, 0 ,iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

            return Base64.encodeToString(result, Base64.NO_WRAP);

        } catch (Exception e) {
            throw new IllegalStateException("Không thể mã hóa dữ liệu", e);
        }
    }

    /**
     * Base64(IV + ciphertext)
     *      ↓
     * AES-GCM
     *      ↓
     * plaintext
     */

    public String decrypt(String encrypted) {
        if (encrypted == null) {
            return  null;
        }

        try {
            byte[] data = Base64.decode(encrypted, Base64.NO_WRAP);
            int minPayloadSize = IV_SIZE + (GCM_TAG_SIZE / 8); // 12 + 16 = 28 bytes

            if (data == null || data.length < minPayloadSize) {
                return encrypted; // Plaintext or raw value fallback
            }

            byte[] iv = new byte[IV_SIZE];
            byte[] ciphertext = new byte[data.length - IV_SIZE];

            System.arraycopy(data, 0, iv, 0, IV_SIZE);
            System.arraycopy(data, IV_SIZE, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            GCMParameterSpec spec =
                    new GCMParameterSpec(GCM_TAG_SIZE, iv);

            cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return encrypted; // Return original string if plaintext or bad decrypt key mismatch

        } catch (Exception e) {
            return encrypted;
        }
    }

    public static String generateSaltBase64() {
        byte[] salt = generateSalt();

        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }

    public static byte[] decodeSalt(String saltBase64) {
        if (saltBase64 == null || saltBase64.isEmpty()) {
            throw new IllegalArgumentException("Salt không hợp lệ");
        }

        return Base64.decode(saltBase64, Base64.NO_WRAP);
    }

    /**
     * So sánh 2 khóa AES bằng constant-time comparison (chống timing attack),
     * dùng khi kiểm tra Master Password nhập vào có khớp Vault Key đã lưu không.
     * Chuyển từ AuthViewModel sang đây vì được dùng chung bởi cả LoginUseCase
     * và UnlockWithMasterPasswordUseCase.
     */
    public static boolean isSameKey(byte[] first, byte[] second) {
        if (first == null || second == null) {
            return false;
        }

        return MessageDigest.isEqual(first, second);
    }

    public static int getSaltSize() {
        return SALT_SIZE;
    }

    public static int getPbkdf2Iterations() {
        return PBKDF2_ITERATIONS;
    }

    public SecretKey getSecretKey() {
        return aesKey;
    }
}
