package com.example.passwordmanager.data.sync;


import android.util.Log;

import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.local.CredentialEntity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SyncManager {
    private static final String TAG = "SyncManager";

    private final ExecutorService executor;

    private final CredentialDao credentialDao;
    private final FirebaseFirestore firestore;

    // static: app có nhiều nơi tự "new SyncManager(...)" (CredentialViewModel,
    // CredentialRepository, LoginUseCase, ChangeMasterPasswordUseCase). Nếu cờ
    // nằm trong từng instance thì các instance không biết nhau và có thể sync
    // song song cùng một dòng dữ liệu.
    private static final AtomicBoolean isSyncing = new AtomicBoolean(false);

    // Có yêu cầu sync mới đến trong lúc đang chạy -> chạy lại 1 vòng khi xong,
    // để thay đổi ghi vào Room đúng lúc đang sync không bị bỏ sót.
    private static final AtomicBoolean syncRequested = new AtomicBoolean(false);

    // Tăng mỗi khi Firestore xác nhận một lần đẩy lên. pullFromFirebase() dùng
    // để biết snapshot vừa tải về có thể đã cũ so với Room hay chưa
    // (vd: mục vừa đẩy xong đã SYNCED nhưng chưa có trong snapshot).
    private static final AtomicLong pushEpoch = new AtomicLong(0);

    private static final long TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    public SyncManager(
            CredentialDao credentialDao,
            ExecutorService executor
    ) {
        this.credentialDao = credentialDao;
        this.firestore = FirebaseFirestore.getInstance();
        this.executor = executor;
    }

    // ====================================
    // Start Sync
    // ====================================

    public void sync() {
        // Đánh dấu "có việc cần làm" TRƯỚC khi tranh quyền chạy. Nếu đang có một
        // vòng sync khác thì vòng đó sẽ thấy cờ này ở bước kết thúc (finishSync).
        syncRequested.set(true);

        executor.execute(() -> {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

            if (user == null) {
                Log.d(TAG, "Không có người dùng đăng nhập, bỏ qua sync");
                return;
            }

            if (!isSyncing.compareAndSet(false, true)) {
                Log.d(TAG, "Sync đang chạy, sẽ chạy lại 1 vòng khi xong");
                return;
            }

            // Bắt đầu vòng mới: mọi thay đổi đã ghi vào Room từ đây trở đi đều
            // được getPendingCredentials() nhìn thấy.
            syncRequested.set(false);
            syncNext(user.getUid());
        });
    }

    /// Kết thúc 1 vòng sync; nếu trong lúc chạy có yêu cầu mới thì chạy lại.
    private void finishSync() {
        isSyncing.set(false);

        if (syncRequested.get()) {
            sync();
        }
    }

    // ====================================
    // Sync Next
    // ====================================

    private void syncNext(String uid) {
        List<CredentialEntity> pending =
                credentialDao.getPendingCredentials(uid);

        if (pending == null || pending.isEmpty()) {
            Log.d(TAG, "Đồng bộ hoàn tất");
            finishSync();
            return;
        }

        /**
         * Luôn lấy lại dữ liệu mới nhất từ Room.
         *
         * Không giữ một List cũ xuyên suốt
         * toàn bộ quá trình sync.
         */

        CredentialEntity entity = pending.get(0);
        String status = entity.getSyncStatus();

        if (CredentialEntity.PENDING_CREATE.equals(status)
                || CredentialEntity.PENDING_UPDATE.equals(status)
                || CredentialEntity.PENDING_DELETE.equals(status)
        ) {
            pushOne(uid, entity);

        } else {
            Log.d(TAG,"Bỏ qua tài khoản: " + entity.getId() + " status=" + status);
            syncNext(uid);
        }
    }

    private void pushOne(String uid, CredentialEntity entity) {
        final String id = entity.getId();
        final long localUpdatedAt = entity.getUpdatedAt();
        final String expectedStatus = entity.getSyncStatus();
        final boolean isDelete = CredentialEntity.PENDING_DELETE.equals(expectedStatus);

        final Map<String, Object> data = isDelete
                ? toTombstoneData(entity)
                : toFirestoreData(entity);

        final DocumentReference ref = firestore.collection("users")
                .document(uid).collection("credentials").document(id);

        firestore.runTransaction(transaction -> {
                    DocumentSnapshot cloud = transaction.get(ref);

                    if (cloud.exists()
                            && !incomingWins(localUpdatedAt, updatedAtOf(cloud))) {
                        return cloud;   // cloud thắng -> không ghi
                    }

                    transaction.set(ref, data);
                    return null;        // local thắng -> đã ghi
                })
                .addOnSuccessListener(cloudWinner -> {
                    pushEpoch.incrementAndGet();

                    executor.execute(() -> {
                        if (cloudWinner == null) {
                            markPushed(uid, id, expectedStatus, localUpdatedAt, isDelete);
                        } else {
                            Log.d(TAG, "Push bị từ chối vì cloud mới hơn: " + id);
                            applyCloud(uid, cloudWinner);
                        }
                        syncNext(uid);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Sync thất bại: " + id, e);
                    stopSync();
                });
    }

    private void markPushed(String uid, String id, String expectedStatus,
                            long expectedUpdatedAt, boolean isDelete) {
        boolean ok = isDelete
                ? credentialDao.deleteAfterSyncIfUnchanged(uid, id, expectedUpdatedAt) > 0
                : credentialDao.markAsSyncedIfUnchanged(uid, id, expectedStatus, expectedUpdatedAt) > 0;

        Log.d(TAG, ok ? "Push thành công: " + id
                : "Push thành công nhưng Room đã thay đổi: " + id);
    }

    public void pullFromFirebase() {
        executor.execute(() -> {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                Log.d(TAG, "Không có người dùng đăng nhập, bỏ qua pull");
                return;
            }

            String uid = user.getUid();
            final long epochAtStart = pushEpoch.get();

            firestore.collection("users")
                    .document(uid)
                    .collection("credentials")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        executor.execute(() -> {
                            Set<String> cloudIds = new HashSet<>();
                            final boolean fromCache = snapshot.getMetadata().isFromCache();

                            for (DocumentSnapshot document : snapshot.getDocuments()) {
                                cloudIds.add(document.getId());
                                pullCredential(uid, document, fromCache, epochAtStart);
                                purgeOldTombstone(document);
                            }

                            pruneDeletedOnCloud(uid, cloudIds, fromCache, epochAtStart);
                            Log.d(TAG, "Pull Firebase → Room hoàn tất");
                        });
                    })
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Pull Firebase thất bại", e)
                    );
        });
    }

    /**
     * Xóa khỏi Room những bản ghi đã SYNCED nhưng không còn trên Firestore,
     * nghĩa là đã bị xóa từ thiết bị khác.
     *
     * Bản ghi đang PENDING_* luôn được giữ lại (còn thay đổi chưa đẩy lên).
     * Bỏ qua bước này (để lần pull sau làm) khi không chắc snapshot còn mới.
     */
    private void pruneDeletedOnCloud(
            String uid,
            Set<String> cloudIds,
            boolean fromCache,
            long epochAtStart
    ) {
        // Snapshot lấy từ cache (thường do offline) có thể thiếu/cũ.
        if (fromCache) {
            Log.d(TAG, "Snapshot từ cache, bỏ qua bước dọn bản ghi đã xóa");
            return;
        }

        // Đang/vừa đẩy dữ liệu lên: mục vừa đẩy xong đã SYNCED trong Room nhưng
        // chưa có trong snapshot -> nếu dọn lúc này sẽ xóa nhầm.
        if (isSyncing.get() || pushEpoch.get() != epochAtStart) {
            Log.d(TAG, "Đang/vừa đẩy dữ liệu, bỏ qua bước dọn để tránh xóa nhầm");
            return;
        }

        List<String> syncedIds = credentialDao.getSyncedIds(uid);

        if (syncedIds == null) {
            return;
        }

        for (String id : syncedIds) {
            if (cloudIds.contains(id)) {
                continue;
            }

            // Kiểm tra lại: nếu có lần đẩy bắt đầu giữa chừng thì dừng.
            if (isSyncing.get() || pushEpoch.get() != epochAtStart) {
                Log.d(TAG, "Có lần đẩy mới bắt đầu, dừng bước dọn");
                return;
            }

            if (credentialDao.deleteIfSynced(uid, id) > 0) {
                Log.d(TAG, "Xóa local vì đã bị xóa trên cloud: " + id);
            }
        }
    }

    private void pullCredential(String uid, DocumentSnapshot document,
                                boolean fromCache, long epochAtStart) {
        String id = document.getId();
        CredentialEntity local = credentialDao.getById(uid, id);

        if (local == null) {
            // Chỉ tin "không có ở local" khi snapshot chắc chắn còn mới
            boolean snapshotFresh = !fromCache && pushEpoch.get() == epochAtStart;
            if (!snapshotFresh) {
                Log.d(TAG, "Snapshot có thể cũ, bỏ qua: " + id);
                return;
            }
            applyCloud(uid, document);   // tombstone -> no-op, bản sống -> insert
            return;
        }

        // Local (kể cả PENDING) mới hơn hoặc hòa -> giữ local, push sẽ quyết định
        if (!incomingWins(updatedAtOf(document), local.getUpdatedAt())) {
            return;
        }

        applyCloud(uid, document);
    }

    private void applyCloud(String uid, DocumentSnapshot document) {
        final String id = document.getId();
        final long cloudUpdatedAt = updatedAtOf(document);

        if (isTombstone(document)) {
            if (credentialDao.deleteIfNotNewer(uid, id, cloudUpdatedAt) > 0) {
                Log.d(TAG, "Xóa local theo tombstone: " + id);
            }
            return;
        }

        CredentialEntity e = fromFirestore(uid, document);

        if (credentialDao.insertIfAbsent(e) != -1L) {
            Log.d(TAG, "Pull mới: " + id);
            return;
        }

        int n = credentialDao.applyRemoteIfNotNewer(uid, id, e.getTitle(),
                e.getAutofillDomain(), e.getAutofillPackage(), e.getUsername(),
                e.getPassword(), e.getCategory(), e.getNotes(),
                e.getCreatedAt(), cloudUpdatedAt);

        if (n > 0) Log.d(TAG, "Cập nhật Room từ cloud: " + id);
    }

    private void purgeOldTombstone(DocumentSnapshot document) {
        if (isTombstone(document)
                && System.currentTimeMillis() - updatedAtOf(document) > TOMBSTONE_TTL_MS) {
            document.getReference().delete();
        }
    }

    private static boolean isTombstone(DocumentSnapshot d) {
        return Boolean.TRUE.equals(d.getBoolean("deleted"));
    }

    private static long updatedAtOf(DocumentSnapshot d) {
        Long v = d.getLong("updatedAt");
        return v != null ? v : 0L;
    }

    // ====================================
    // Stop Sync
    // ====================================

    public void stopSync() {
        isSyncing.set(false);
    }

    // ====================================
    // Firestore Data
    // ====================================

    private Map<String, Object> toFirestoreData(CredentialEntity entity) {
        Map<String, Object> data = new HashMap<>();

        data.put("id", entity.getId());
        data.put("title", entity.getTitle());
        data.put("username", entity.getUsername());
        data.put("password", entity.getPassword());
        data.put("autofillDomain", entity.getAutofillDomain());
        data.put("autofillPackage", entity.getAutofillPackage());
        data.put("category", entity.getCategory());
        data.put("notes", entity.getNotes());
        data.put("createdAt", entity.getCreatedAt());
        data.put("updatedAt", entity.getUpdatedAt());
        data.put("deleted", false);

        return data;
    }

    private Map<String, Object> toTombstoneData(CredentialEntity entity) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", entity.getId());
        data.put("deleted", true);
        data.put("createdAt", entity.getCreatedAt());
        data.put("updatedAt", entity.getUpdatedAt());
        return data;
    }

    private CredentialEntity fromFirestore(
            String uid,
            DocumentSnapshot document
    ) {
        // Dùng document.getId() làm fallback nếu trường "id" trong doc bị null để tránh crash Room PrimaryKey
        String id = document.getString("id");
        if (id == null || id.isEmpty()) {
            id = document.getId();
        }

        return new CredentialEntity(
                uid,
                id,
                document.getString("title"),
                document.getString("autofillDomain"),
                document.getString("autofillPackage"),
                document.getString("username"),
                document.getString("password"),
                document.getString("category"),
                document.getString("notes"),
                document.getLong("createdAt") != null
                        ? document.getLong("createdAt")
                        : 0L,
                document.getLong("updatedAt") != null
                        ? document.getLong("updatedAt")
                        : 0L,
                CredentialEntity.SYNCED
        );
    }

    /** LUẬT DUY NHẤT của LWW, dùng chung cho push và pull. Hòa: cloud là chuẩn. */
    static boolean incomingWins(long incomingUpdatedAt, long existingUpdatedAt) {
        return incomingUpdatedAt > existingUpdatedAt;
    }
}
