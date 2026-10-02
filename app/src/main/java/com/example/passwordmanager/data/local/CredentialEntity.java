package com.example.passwordmanager.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "credentials")
public class CredentialEntity {

    public static final String SYNCED = "SYNCED";
    public static final String PENDING_CREATE = "PENDING_CREATE";
    public static final String PENDING_UPDATE = "PENDING_UPDATE";
    public static final String PENDING_DELETE = "PENDING_DELETE";

    @NonNull
    private  String uid;

    @PrimaryKey
    @NonNull
    private String id;

    private String title;
    private String autofillDomain;
    private String autofillPackage;

    private String username;
    private String password;
    private String category;
    private String notes;
    private long createdAt;
    private long updatedAt;
    private String syncStatus;



    public CredentialEntity() {
    }

    public CredentialEntity(
            String uid,
            String id,
            String title,
            String autofillDomain,
            String autofillPackage,
            String username,
            String password,
            String category,
            String notes,
            long createdAt,
            long updatedAt,
            String syncStatus
    ) {
        this.uid = uid;
        this.id = id;
        this.title = title;
        this.username = username;
        this.password = password;
        this.autofillDomain = autofillDomain;
        this.autofillPackage = autofillPackage;
        this.category = category;
        this.notes = notes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.syncStatus = syncStatus;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAutofillPackage() {
        return autofillPackage;
    }

    public void setAutofillPackage(String autofillPackage) {
        this.autofillPackage = autofillPackage;
    }

    public String getAutofillDomain() {
        return autofillDomain;
    }

    public void setAutofillDomain(String autofillDomain) {
        this.autofillDomain = autofillDomain;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }
}
