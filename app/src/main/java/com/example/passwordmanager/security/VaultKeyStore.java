package com.example.passwordmanager.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


 /*
 Trong VaultKeyStore của bạn có 2 loại key khác nhau:
 Vault Key (AES-256)
      │
      │ encrypt bằng
      ▼
Wrapping Key  ==> khóa giải mã wrapped key
(Android Keystore ~ Két sắt)
      │
      ▼
Wrapped Vault Key  ==>  là Vault Key đã bị mã hóa
(lưu trong SharedPreferences ~ Ngăn kéo)
  */

/**
 * Lưu trữ và quản lý Vault Key cùng Salt và PIN đã mã hóa của người dùng trên thiết bị.
 * Vault Key được lưu dưới dạng đã mã hóa bằng Android Keystore,
 * còn Salt được lưu local để hỗ trợ khôi phục Vault Key khi mở khóa offline.
 */
public class VaultKeyStore {
    private static final String KEYSTORE_NAME = "AndroidKeyStore";
    private static final String PREF_NAME = "vault_security";

    private static final String AES_MODE = "AES/GCM/NoPadding";

    private static final String KEY_PREFIX = "vault_wrap_";
    private static final String DATA_PREFIX = "wrapped_key_";

    private static final String SALT_PREFIX = "vault_salt_";

    private static final String PIN_HASH_PREFIX = "encrypted_pin_hash_";
    private static final String PIN_SALT_PREFIX = "pin_salt_";

    private static final int GCM_TAG_SIZE = 128;
    private static final int IV_SIZE = 12;

    private final Context context;

    public VaultKeyStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private String getAlias(String uid) {
        return KEY_PREFIX + uid;
    }

    private SecretKey getOrCreateWrappingKey(String uid) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_NAME);
        keyStore.load(null);

        String alias = getAlias(uid);

        if (keyStore.containsAlias(alias)) {
            return ((KeyStore.SecretKeyEntry)
                    keyStore.getEntry(alias, null))
                            .getSecretKey();
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_NAME
        );

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT
                        | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();

        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    /**
     * Mã hóa và lưu một chuỗi văn bản bằng Android KeyStore.
     */
    public void saveEncryptedString(String prefKey, String uid, String plaintext) throws Exception {
        if (uid == null || uid.isEmpty() || plaintext == null) {
            return;
        }

        SecretKey wrappingKey = getOrCreateWrappingKey(uid);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);

        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[iv.length + ciphertext.length];

        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

        String encoded = Base64.encodeToString(result, Base64.NO_WRAP);

        getPreferences().edit()
                .putString(prefKey + uid, encoded)
                .apply();
    }

    /**
     * Đọc và giải mã một chuỗi văn bản đã mã hóa trong SharedPreferences.
     */
    public String loadEncryptedString(String prefKey, String uid) throws Exception {
        String encoded = getPreferences().getString(
                prefKey + uid,
                null
        );

        if (encoded == null) {
            return null;
        }

        try {
            byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
            if (data.length <= IV_SIZE) {
                return null;
            }

            byte[] iv = new byte[IV_SIZE];
            byte[] ciphertext = new byte[data.length - IV_SIZE];

            System.arraycopy(data, 0, iv, 0, IV_SIZE);
            System.arraycopy(data, IV_SIZE, ciphertext, 0, ciphertext.length);

            SecretKey wrappingKey = getOrCreateWrappingKey(uid);
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    wrappingKey,
                    new GCMParameterSpec(GCM_TAG_SIZE, iv)
            );

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException e) {
            getPreferences().edit()
                    .remove(prefKey + uid)
                    .apply();

            return null;
        }
    }

    // =========================================================
    // VAULT KEY
    // =========================================================

    ///  Lưu Vault Key đã được mã hóa vào local storage.
    public void saveVaultKey(String uid, SecretKey vaultKey) throws Exception {
        if (uid == null || uid.isEmpty()) {
            throw new IllegalArgumentException("UID không hợp lệ");
        }

        if (vaultKey == null) {
            throw new IllegalArgumentException("Vault key không hợp lệ");
        }

        byte[] keyBytes = vaultKey.getEncoded();

        if (keyBytes == null) {
            throw new IllegalStateException("Không thể lấy dữ liệu AES key");
        }

        String keyBase64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        saveEncryptedString(DATA_PREFIX, uid, keyBase64);
    }

    /// Đọc và giải mã Vault Key đã lưu của người dùng.
    public SecretKey loadVaultKey(String uid) throws Exception {
        String keyBase64 = loadEncryptedString(DATA_PREFIX, uid);

        if (keyBase64 == null) {
            return null;
        }

        byte[] keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP);
        return new SecretKeySpec(keyBytes, "AES");
    }

    /// Kiểm tra người dùng đã có Vault Key được lưu trên thiết bị hay chưa.
    public boolean hasVaultKey(String uid) {
        return getPreferences().contains(DATA_PREFIX + uid);
    }

    // =========================================================
    // VAULT SALT & DEDICATED PIN ENCRYPTION
    // =========================================================

    /// Lưu Salt local của người dùng để hỗ trợ tạo lại Vault Key khi offline.
    public void saveSalt(String uid, String salt) {
        getPreferences()
                .edit()
                .putString(SALT_PREFIX + uid, salt)
                .apply();
    }

    /// Đọc Salt local của người dùng để sử dụng khi mở khóa offline.
    public String loadSalt(String uid) {
        return getPreferences()
                .getString(SALT_PREFIX + uid, null);
    }

    /// Lưu PIN Hash đã mã hóa bằng Android KeyStore.
    public void savePinHash(String uid, String pinHash) throws Exception {
        saveEncryptedString(PIN_HASH_PREFIX, uid, pinHash);
    }

    /// Đọc PIN Hash đã giải mã.
    public String loadPinHash(String uid) throws Exception {
        return loadEncryptedString(PIN_HASH_PREFIX, uid);
    }

    /// Kiểm tra người dùng đã lưu PIN Hash mã hóa hay chưa.
    public boolean hasPinHash(String uid) {
        return getPreferences().contains(PIN_HASH_PREFIX + uid);
    }

    /// Lưu PIN Salt độc lập của người dùng.
    public void savePinSalt(String uid, String pinSalt) {
        getPreferences().edit()
                .putString(PIN_SALT_PREFIX + uid, pinSalt)
                .apply();
    }

    /// Đọc PIN Salt độc lập của người dùng.
    public String loadPinSalt(String uid) {
        return getPreferences()
                .getString(PIN_SALT_PREFIX + uid, null);
    }

    /**
     * Xóa toàn bộ dữ liệu bảo mật local của người dùng,
     * bao gồm Vault Key, Salt và khóa wrapping trong Android Keystore.
     */
    public void clear(String uid) {
        getPreferences()
                .edit()
                .remove(DATA_PREFIX + uid)
                .remove(SALT_PREFIX + uid)
                .remove(PIN_HASH_PREFIX + uid)
                .remove(PIN_SALT_PREFIX + uid)
                .apply();

        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_NAME);
            keyStore.load(null);

            String alias = getAlias(uid);

            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private SharedPreferences getPreferences() {
        return  context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );
    }
}
