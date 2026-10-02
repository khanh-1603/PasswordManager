package com.example.passwordmanager.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.di.AppContainer;

/**
 * Factory dựng SecurityViewModel với dependency lấy từ AppContainer.
 */
public class SecurityViewModelFactory implements ViewModelProvider.Factory {
    private final Application application;

    public SecurityViewModelFactory(Application application) {
        this.application = application;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(SecurityViewModel.class)) {
            AppContainer container = AppContainer.getInstance(application);

            return modelClass.cast(new SecurityViewModel(
                    application,
                    container.getLocalAuthManager(),
                    container.getVaultKeyStore(),
                    container.getCredentialDao(),
                    container.getBackgroundExecutor()
            ));
        }

        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}
