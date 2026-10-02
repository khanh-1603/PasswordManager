package com.example.passwordmanager.viewmodel;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.data.repository.ICredentialRepository;
import com.example.passwordmanager.di.AppContainer;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class CredentialViewModelFactory implements ViewModelProvider.Factory {
    private final ICredentialRepository repository;

    public CredentialViewModelFactory(ICredentialRepository repository) {
        this.repository = repository;
    }

    /**
     * Helper tập trung: lấy uid hiện tại từ FirebaseAuth và CredentialRepository
     * tương ứng từ AppContainer, thay cho việc mỗi Activity/Fragment tự
     * "new CredentialRepository(...).
     * Ném IllegalStateException nếu chưa đăng nhập hoặc CryptoSession chưa
     * sẵn sàng — nơi gọi tự quyết định xử lý (thường là finish()).
     */
    public static CredentialViewModelFactory create(Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            throw new IllegalStateException("Người dùng chưa đăng nhập");
        }

        ICredentialRepository repository = AppContainer
                .getInstance(context)
                .getCredentialRepository(user.getUid());

        return new CredentialViewModelFactory(repository);
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(CredentialViewModel.class)) {
            return modelClass.cast(new CredentialViewModel(repository));
        }

        throw new IllegalArgumentException(
                "Unknown ViewModel class"
        );
    }
}
