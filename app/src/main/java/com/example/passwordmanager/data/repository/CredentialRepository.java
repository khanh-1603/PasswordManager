package com.example.passwordmanager.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.local.CredentialEntity;
import com.example.passwordmanager.data.model.CredentialItem;
import com.example.passwordmanager.data.sync.NetworkManager;
import com.example.passwordmanager.data.sync.SyncManager;
import com.example.passwordmanager.security.CryptoManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Repository local cho user hiện tại.
 *
 * Chỉ giao tiếp với Room.
 * SyncManager sẽ quản lý Firebase; Repository chỉ "gõ cửa" SyncManager
 * ngay sau khi Add/Update/Delete ghi vào Room thành công.
 *
 * Repository chịu trách nhiệm:
 * - CRUD dữ liệu local
 * - Encrypt trước khi lưu Room
 * - Decrypt sau khi đọc Room
 * - Quản lý syncStatus
 */

public class CredentialRepository implements ICredentialRepository{
    private static final String TAG = "CredentialRepository";
    private final Context appContext;
    private final SyncManager syncManager;
    private final CredentialDao credentialDao;
    private final ExecutorService databaseExecutor;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final String uid;

    private final CryptoManager cryptoManager;

    public CredentialRepository(
            Context context,
            String uid,
            CryptoManager cryptoManager,
            ExecutorService databaseExecutor
    ) {
        if (uid == null || uid.isEmpty()) {
            throw new IllegalArgumentException("Người dùng chưa đăng nhập");
        }

        if (cryptoManager == null) {
            throw new IllegalArgumentException("CryptoManager không được null");
        }

        this.databaseExecutor = databaseExecutor;
        this.appContext = context.getApplicationContext();
        credentialDao = AppDatabase.getInstance(context).credentialDao();
        this.uid = uid;
        this.syncManager = new SyncManager(credentialDao, databaseExecutor);
        this.cryptoManager = cryptoManager;
    }

    // ====================================
    // Get All
    // ====================================

    public LiveData<List<CredentialItem>> getCredentials() {
        LiveData<List<CredentialEntity>> source =
                credentialDao.getAll(uid);

        return Transformations.map(source, entities -> {
            List<CredentialItem> items = new ArrayList<>();

            for (CredentialEntity entity : entities) {
                items.add((toItem(entity)));
            }

            return items;
        });
    }

    // ====================================
    // Get One
    // ====================================
    public void getCredential(
            String id,
            OnCredentialLoadedListener successListener,
            OnErrorListener errorListener
    ) {
        databaseExecutor.execute(() -> {
            try {
                if (id == null || id.isEmpty()) {
                    postError(errorListener, "Tài khoản không có ID");
                    return;
                }

                CredentialEntity entity = credentialDao.getById(uid, id);

                if (entity == null) {
                    postError(errorListener,"Không tìm thấy tài khoản");
                    return;
                }

                CredentialItem item = toItem(entity);

                mainHandler.post(() -> successListener.onSuccess(item));

            } catch (Exception e) {
                postError(errorListener, getErrorMessage(e));
            }
        });
    }

    // ====================================
    // Add
    // ====================================
    public void addCredential(
            CredentialItem item,
            OnSuccessListener successListener,
            OnErrorListener errorListener
    ) {
        databaseExecutor.execute(() -> {
            try {
                if (item == null) {
                    postError(errorListener, "Dữ liệu tài khoản không hợp lệ");
                    return;
                }

                if (item.getId() == null || item.getId().isEmpty()) {
                    item.setId(UUID.randomUUID().toString());
                }

                long now = System.currentTimeMillis();

                if (item.getCreatedAt() <= 0) {
                    item.setCreatedAt(now);
                }

                if (item.getUpdatedAt() <= 0) {
                    item.setUpdatedAt(now);
                }

                CredentialEntity entity = toEntity(
                        item,
                        CredentialEntity.PENDING_CREATE
                );

                credentialDao.insert(entity);
                mainHandler.post(successListener::onSuccess);
                syncNow();

            } catch (Exception e) {
                postError(errorListener, getErrorMessage(e));
            }
        });
    }

    // ====================================
    // Update
    // ====================================

    public void updateCredential(
            CredentialItem item,
            OnSuccessListener successListener,
            OnErrorListener errorListener
    ) {
        databaseExecutor.execute(() -> {
            try {
                if (item == null) {
                    postError(
                            errorListener,
                            "Dữ liệu tài khoản không hợp lệ"
                    );
                    return;
                }

                if(item.getId() == null || item.getId().isEmpty()) {
                    postError(errorListener, "Tài khoản không có ID");
                    return;
                }

                item.setUpdatedAt(System.currentTimeMillis());

                CredentialEntity entity = toEntity(
                        item,
                        CredentialEntity.PENDING_UPDATE
                );

                credentialDao.update(entity);
                mainHandler.post(successListener::onSuccess);
                syncNow();

            } catch (Exception e) {
                postError(errorListener, getErrorMessage(e));
            }
        });
    }

    // ====================================
    // Delete
    // ====================================

    public void deleteCredential(
            String id,
            OnSuccessListener successListener,
            OnErrorListener errorListener
    ) {
        databaseExecutor.execute(() -> {
            try {
                if (id == null || id.isEmpty()) {
                    postError(errorListener, "Tài khoản không có ID");
                    return;
                }

                CredentialEntity entity = credentialDao.getById(uid, id);

                if (entity == null) {
                    postError(errorListener, "Không tìm thấy tài khoản");
                    return;
                }

                entity.setSyncStatus(CredentialEntity.PENDING_DELETE);
                entity.setUpdatedAt(System.currentTimeMillis());
                credentialDao.update(entity);
                mainHandler.post(successListener::onSuccess);
                syncNow();

            } catch (Exception e) {
                postError(errorListener, getErrorMessage(e));
            }
        });
    }

    // ====================================
    // Sync
    // ====================================

    /**
     * Đẩy thay đổi vừa ghi vào Room lên Firestore ngay.
     *
     * - Offline thì bỏ qua: Firestore .set()/.delete() lúc mất mạng không trả
     *   kết quả, sẽ làm kẹt vòng sync. Khi có mạng lại, NetworkManager tự sync.
     * - Nuốt mọi ngoại lệ: lỗi sync không được biến một lần lưu local đã thành
     *   công thành báo lỗi (dữ liệu vẫn nằm an toàn ở Room với trạng thái PENDING).
     */
    private void syncNow() {
        try {
            if (!NetworkManager.isNetworkConnected(appContext)) {
                Log.d(TAG, "Offline -> để NetworkManager sync khi có mạng");
                return;
            }

            syncManager.sync();

        } catch (Exception e) {
            Log.e(TAG, "Không thể kích hoạt sync", e);
        }
    }

    // ====================================
    // Convert
    // ====================================
    private CredentialEntity toEntity(CredentialItem item, String syncStatus) {
        return new CredentialEntity(
                uid,
                item.getId(),
                cryptoManager.encrypt(item.getTitle()),
                item.getAutofillDomain(),
                item.getAutofillPackage(),
                cryptoManager.encrypt(item.getUsername()),
                cryptoManager.encrypt(item.getPassword()),
                cryptoManager.encrypt(item.getCategory()),
                cryptoManager.encrypt(item.getNote()),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                syncStatus
        );
    }

    private CredentialItem toItem(CredentialEntity entity) {
        CredentialItem item = new CredentialItem();

        item.setId(entity.getId());
        item.setTitle(cryptoManager.decrypt(entity.getTitle()));
        item.setUsername(cryptoManager.decrypt(entity.getUsername()));
        item.setPassword(cryptoManager.decrypt(entity.getPassword()));
        item.setAutofillDomain(entity.getAutofillDomain());
        item.setAutofillPackage(entity.getAutofillPackage());
        item.setCategory(cryptoManager.decrypt(entity.getCategory()));
        item.setNote(cryptoManager.decrypt(entity.getNotes()));
        item.setCreatedAt(entity.getCreatedAt());
        item.setUpdatedAt(entity.getUpdatedAt());

        return item;
    }


    // =========================
    // Error
    // =========================

    private void postError(OnErrorListener errorListener, String message) {
        mainHandler.post(() -> errorListener.onError(message));
    }

    // =========================
    // Error Helper
    // =========================

    private String getErrorMessage(Exception e) {
        if (e.getMessage() != null && !e.getMessage().isEmpty()) {
            return e.getMessage();
        }

        return "Đã có lỗi khi thao tác dữ liệu";
    }

    public CredentialDao getCredentialDao() {
        return credentialDao;
    }
}
