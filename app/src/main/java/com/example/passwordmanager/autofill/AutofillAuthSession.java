package com.example.passwordmanager.autofill;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Quản lý trạng thái xác thực cục bộ của Autofill (RAM, mất khi timeout/kill app)
 * và trạng thái "credential dùng gần nhất" theo từng context — domain hoặc package
 * (lưu bền trong SharedPreferences, sống qua reboot/kill app).
 *
 * Không lưu password vào đây, kể cả username plaintext.
 */
public final class AutofillAuthSession {

    // Thời gian giữ trạng thái đã xác thực: 5 phút.
    private static final long AUTH_TIMEOUT = 5 * 60 * 1000L;
    private static final String PREF_NAME = "autofill_last_used";
    private static boolean authenticated = false;
    private static long authenticatedAt = 0L;

    private AutofillAuthSession() {
        // Không cho tạo object.
    }

    /**
     * Đánh dấu Autofill đã được xác thực thành công.
     */
    public static synchronized void authenticate() {
        authenticated = true;
        authenticatedAt = System.currentTimeMillis();
    }

    /**
     * Kiểm tra Autofill hiện còn trong trạng thái đã xác thực hay không.
     */
    public static synchronized boolean isAuthenticated() {

        if (!authenticated) {
            return false;
        }

        // Hết thời gian xác thực → tự khóa lại.
        if (System.currentTimeMillis() - authenticatedAt > AUTH_TIMEOUT) {
            clear();
            return false;
        }

        return true;
    }

    /**
     * Xóa trạng thái xác thực.
     */
    public static synchronized void clear() {
        authenticated = false;
        authenticatedAt = 0L;
        // Không đụng tới last_used ở đây — last_used đã lưu bền,
        // không phụ thuộc phiên xác thực biometric.
    }

    public static synchronized void setLastUsed(
            Context context,
            String contextKey,
            String credentialId
    ) {
        if (contextKey == null || credentialId == null) return;

        getPreferences(context).edit()
                .putString(contextKey, credentialId)
                .apply();    }

    public static synchronized String getLastUsedCredentialId(
            Context context,
            String contextKey
    ) {
        if (contextKey == null) return null;
        return getPreferences(context).getString(contextKey, null);
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}