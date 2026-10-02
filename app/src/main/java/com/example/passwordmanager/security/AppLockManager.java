package com.example.passwordmanager.security;


import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.ui.auth.RegisterActivity;
import com.example.passwordmanager.ui.security.LocalAuthIntroActivity;
import com.example.passwordmanager.ui.security.UnlockActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Quản lý trạng thái khóa local theo vòng đời toàn bộ ứng dụng.
 *
 * Khi ứng dụng thực sự chuyển xuống background, trạng thái đang mở khóa
 * được giữ ở mức lifecycle và khi quay lại foreground sẽ yêu cầu xác thực.
 *
 * Nếu Local Auth được bật:
 *  -> mở UnlockActivity.
 *
 * Nếu Local Auth tắt:
 *  -> mở LoginActivity để đăng nhập lại.
 *
 * Không tự mở MainActivity sau khi xác thực. Activity xác thực sẽ finish()
 * để trả người dùng về đúng màn hình đang nằm phía dưới.
 */
public class AppLockManager
        implements Application.ActivityLifecycleCallbacks {

    private final Application application;
    private final LocalAuthManager localAuthManager;

    private int startedActivities = 0;
    private boolean enteredBackground = false;
    private boolean unlocking = false;

    public AppLockManager(Application application) {
        this.application = application;
        this.localAuthManager = new LocalAuthManager(application);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        startedActivities++;

        if (startedActivities == 1 && enteredBackground) {
            requestUnlock(activity);
        }

            enteredBackground = false;
    }

    private void requestUnlock(Activity activity) {
        if (unlocking) {
            return;
        }

        if (activity instanceof UnlockActivity
                || activity instanceof LoginActivity
                || activity instanceof RegisterActivity
                || activity instanceof LocalAuthIntroActivity) {
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null || !CryptoSession.isActive()) {
            return;
        }

        String uid = user.getUid();

        // CHỈ mở UnlockActivity khi LocalAuth ĐƯỢC BẬT.
        // Nếu LocalAuth TẮT, giữ nguyên phiên làm việc ngầm không bắt đăng nhập lại.
        if (localAuthManager.isEnabled(uid)) {
            unlocking = true;
            Intent intent = new Intent(
                    activity,
                    UnlockActivity.class
            );
            activity.startActivity(intent);
        }
    }

    /**
     * Được gọi khi quá trình xác thực local thành công.
     */
    public void unlockFinished() {
        unlocking = false;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        startedActivities--;

        if (startedActivities <= 0) {
            startedActivities = 0;
            enteredBackground = true;
            unlocking = false;
        }
    }

    @Override
    public void onActivityCreated(
            @NonNull Activity activity,
            @Nullable Bundle savedInstanceState
    ) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(
            @NonNull Activity activity,
            @NonNull Bundle outState
    ) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
