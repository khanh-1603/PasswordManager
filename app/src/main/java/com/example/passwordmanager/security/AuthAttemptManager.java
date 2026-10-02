package com.example.passwordmanager.security;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Quản lý số lần nhập sai Master Password của ứng dụng.
 *
 * LƯU Ý PHÂN BIỆT:
 * - Vân tay / PIN Hệ thống (Android OS Keyguard): Tự động bị đếm ngược phạt bởi Android OS khi nhập sai.
 * - Master Password của App: Do AuthAttemptManager theo dõi. Khi người dùng nhập sai Master Password
 *   đủ 5 lần (MAX_ATTEMPTS), ứng dụng sẽ tự động kích hoạt xóa dữ liệu cục bộ (wipeLocalVault)
 *   để chống lại các cuộc tấn công dò quét mật khẩu (Brute-force Attack).
 */
public class AuthAttemptManager {
    private static final String PREF_NAME = "security_attempts";
    private static final String FAILED_ATTEMPTS_PREFIX = "master_password_failed_attempts_";

    public static final int MAX_ATTEMPTS = 5;

    private final SharedPreferences preferences;

    public AuthAttemptManager(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Ghi nhận 1 lần nhập sai Master Password vào SharedPreferences.
     * @return Số lần đã nhập sai tích lũy hiện tại (ví dụ: 1, 2, 3...).
     */
    public int recordFailure(String uid) {
        int attempts = getFailedAttempts(uid) + 1;

        preferences.edit()
                .putInt(FAILED_ATTEMPTS_PREFIX +uid, attempts)
                .apply();

        return attempts;
    }

    /**
     * Lấy số lần nhập sai Master Password hiện tại.
     * @return Số lần sai đã lưu (mặc định là 0).
     */
    public int getFailedAttempts(String uid) {
        return preferences.getInt(FAILED_ATTEMPTS_PREFIX + uid, 0);
    }

    /**
     * Tái lập (Reset) đếm số lần sai về 0
     * khi người dùng nhập đúng Master Password hoặc mở khóa Vân tay thành công.
     */
    public void reset(String uid) {
        preferences.edit()
                .remove(FAILED_ATTEMPTS_PREFIX + uid)
                .apply();
    }

    /**
     * Kiểm tra xem số lần nhập sai đã vượt quá giới hạn tối đa (5 lần) hay chưa.
     * @return true nếu đã nhập sai >= 5 lần, ngược lại false.
     */
    public boolean reachedLimit(String uid) {
        return getFailedAttempts(uid) >= MAX_ATTEMPTS;
    }
}
