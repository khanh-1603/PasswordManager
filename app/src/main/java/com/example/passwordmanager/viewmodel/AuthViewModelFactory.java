package com.example.passwordmanager.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.di.AppContainer;

/**
 * Factory dựng AuthViewModel với dependency lấy từ AppContainer,
 * thay cho việc dựa vào AndroidViewModelFactory mặc định của Android
 * (chỉ truyền được Application, khiến AuthViewModel phải tự "new" Repository).
 */
public class AuthViewModelFactory implements ViewModelProvider.Factory {
    private final Application application;

    public AuthViewModelFactory(Application application) {
        this.application = application;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(AuthViewModel.class)) {
            AppContainer container = AppContainer.getInstance(application);

            return modelClass.cast(new AuthViewModel(
                    application,
                    container.getAuthRepository(),
                    container.getSecurityRepository(),
                    container.getVaultKeyStore(),
                    container.getAuthAttemptManager(),
                    container.getLocalAuthManager(),
                    container.getBackgroundExecutor()
            ));
        }

        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}
