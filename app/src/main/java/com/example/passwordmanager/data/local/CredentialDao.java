package com.example.passwordmanager.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface CredentialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(CredentialEntity credential);


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<CredentialEntity> credentials);


    @Update
    void update(CredentialEntity credential);


    @Query(" SELECT * FROM credentials " +
            "WHERE uid = :uid AND syncStatus != 'PENDING_DELETE' " +
            "ORDER BY updatedAt DESC")
    LiveData<List<CredentialEntity>> getAll(String uid);


    @Query(
            "SELECT * FROM credentials " +
                    "WHERE uid = :uid " +
                    "AND syncStatus != 'PENDING_DELETE' " +
                    "AND (" +
                    " autofillDomain = :domain " +
                    " OR :domain LIKE '%' || autofillDomain " +
                    " OR autofillDomain LIKE '%' || :domain" +
                    ") " +
                    "ORDER BY updatedAt DESC"
    )
    List<CredentialEntity> getByAutofillDomain(String uid, String domain);


    @Query(
            "SELECT * FROM credentials " +
                    "WHERE uid = :uid " +
                    "AND syncStatus != 'PENDING_DELETE' " +
                    "AND autofillPackage = :packageName " +
                    "ORDER BY updatedAt DESC"
    )
    List<CredentialEntity> getByAutofillPackage(String uid, String packageName);


    @Query("SELECT * FROM credentials WHERE uid = :uid AND id = :id LIMIT 1")
    CredentialEntity getById(String uid, String id);


    @Delete
    void delete(CredentialEntity credential);


    @Query("DELETE FROM credentials WHERE uid = :uid AND id = :id")
    void deleteById(String uid, String id);


    @Query("DELETE FROM credentials WHERE uid = :uid")
    void deleteAll(String uid);


    @Query("SELECT * FROM credentials " +
            "WHERE uid = :uid AND syncStatus != 'SYNCED' " +
            "ORDER BY updatedAt ASC")
    List<CredentialEntity> getPendingCredentials(String uid);


    @Query("UPDATE credentials SET syncStatus = 'SYNCED' " +
            "WHERE uid = :uid " +
            "AND id = :id  "+
            "AND syncStatus = :expectedStatus " +
            "AND updatedAt = :expectedUpdatedAt")
    int markAsSyncedIfUnchanged(
            String uid,
            String id,
            String expectedStatus,
            long expectedUpdatedAt
    );


    @Query("DELETE FROM credentials " +
            "WHERE uid = :uid " +
            "AND id = :id " +
            "AND syncStatus = 'PENDING_DELETE' " +
            "AND updatedAt = :expectedUpdatedAt")
    int deleteAfterSyncIfUnchanged(
            String uid,
            String id,
            long expectedUpdatedAt
    );


    @Query("SELECT * FROM credentials " +
            "WHERE uid = :uid " +
            "AND syncStatus != 'PENDING_DELETE'")
    List<CredentialEntity> getAllList(String uid);


    /// Id các bản ghi đã đồng bộ xong (dùng để dọn bản ghi đã bị xóa trên cloud).
    @Query("SELECT id FROM credentials " +
            "WHERE uid = :uid AND syncStatus = 'SYNCED'")
    List<String> getSyncedIds(String uid);


    /// Chỉ xóa nếu bản ghi VẪN còn SYNCED (người dùng có thể vừa sửa xong).
    @Query("DELETE FROM credentials " +
            "WHERE uid = :uid " +
            "AND id = :id " +
            "AND syncStatus = 'SYNCED'")
    int deleteIfSynced(String uid, String id);

    /// Chỉ chèn khi chưa có dòng nào cùng id. Trả -1 nếu đã tồn tại.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIfAbsent(CredentialEntity credential);

    /// Ghi bản cloud xuống local, chỉ khi local KHÔNG mới hơn cloud.
    @Query("UPDATE credentials SET " +
            "title = :title, autofillDomain = :autofillDomain, " +
            "autofillPackage = :autofillPackage, username = :username, " +
            "password = :password, category = :category, notes = :notes, " +
            "createdAt = :createdAt, updatedAt = :cloudUpdatedAt, " +
            "syncStatus = 'SYNCED' " +
            "WHERE uid = :uid AND id = :id AND updatedAt <= :cloudUpdatedAt")
    int applyRemoteIfNotNewer(String uid, String id, String title,
                              String autofillDomain, String autofillPackage, String username,
                              String password, String category, String notes,
                              long createdAt, long cloudUpdatedAt);

    /// Áp tombstone: xóa local chỉ khi local KHÔNG mới hơn tombstone.
    @Query("DELETE FROM credentials " +
            "WHERE uid = :uid AND id = :id AND updatedAt <= :tombstoneUpdatedAt")
    int deleteIfNotNewer(String uid, String id, long tombstoneUpdatedAt);
}
