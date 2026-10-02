
package com.example.passwordmanager.data.repository;

import androidx.lifecycle.LiveData;

import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.model.CredentialItem;

import java.util.List;

/**
 * Interface trừu tượng cho CredentialRepository — cho phép CredentialViewModel
 * phụ thuộc vào abstraction, và cho phép viết unit test bằng mock/fake.
 */
public interface ICredentialRepository {

    LiveData<List<CredentialItem>> getCredentials();

    void getCredential(
            String id,
            OnCredentialLoadedListener successListener,
            OnErrorListener errorListener
    );

    void addCredential(
            CredentialItem item,
            OnSuccessListener successListener,
            OnErrorListener errorListener
    );

    void updateCredential(
            CredentialItem item,
            OnSuccessListener successListener,
            OnErrorListener errorListener
    );

    void deleteCredential(
            String id,
            OnSuccessListener successListener,
            OnErrorListener errorListener
    );

    /// CredentialViewModel dùng DAO này để dựng SyncManager (đồng bộ Firestore).
    CredentialDao getCredentialDao();

    interface OnSuccessListener {
        void onSuccess();
    }

    interface OnCredentialLoadedListener {
        void onSuccess(CredentialItem credential);
    }

    interface OnErrorListener {
        void onError(String message);
    }
}