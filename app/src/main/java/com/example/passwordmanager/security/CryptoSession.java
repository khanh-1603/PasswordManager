package com.example.passwordmanager.security;


import android.content.Context;

import javax.crypto.SecretKey;

/**
 * Quản lý phiên cryto đề khởi tạo CryptoManager.
 * Kết hợp với Login và Register để tạo CryptoMnager của phiên đó.
 * Tránh việc phải truyền password và salt khi gọi
 * CryptoManager ở các class khác.
**/

public class CryptoSession {
    private static CryptoManager cryptoManager;

    private CryptoSession() {
        //Không cho tạo object
    }

    public static void start(CryptoManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("CryptoManager không được null");
        }

        cryptoManager = manager;
    }

    public static void restore(Context context, String uid) throws Exception {
        VaultKeyStore vaultKeyStore = new VaultKeyStore(context);

        SecretKey vaultKey = vaultKeyStore.loadVaultKey(uid);

        if (vaultKey == null) {
            throw new IllegalStateException("Không tìm thấy vault key");
        }

        cryptoManager = new CryptoManager(vaultKey);
    }

    public static CryptoManager get() {
        if (cryptoManager == null) {
            throw new IllegalStateException("CrytoSession chưa được khởi tạo");
        }

        return cryptoManager;
    }

    public static void clear() {
        cryptoManager = null;
    }

    public static boolean isActive() {
        return cryptoManager != null;
    }
}
