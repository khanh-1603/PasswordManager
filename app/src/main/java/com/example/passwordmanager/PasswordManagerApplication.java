package com.example.passwordmanager;

import android.app.Application;

import com.example.passwordmanager.security.AppLockManager;

/**
 * Application-level lifecycle để phát hiện lúc toàn bộ app
 * chuyển background rồi quay lại foreground.
 */
public class PasswordManagerApplication extends Application {
    private AppLockManager appLockManager;

    @Override
    public void onCreate() {
        super.onCreate();

        appLockManager = new AppLockManager(this);
        registerActivityLifecycleCallbacks(appLockManager);
    }

    public AppLockManager getAppLockManager() {
        return appLockManager;
    }
}
