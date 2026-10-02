package com.example.passwordmanager.data.model;

public class CredentialItem {
    private  String id;
    private  String title;
    private String username;
    private String password;

    private String autofillDomain;
    private String autofillPackage;

    private String category;
    private String note;
    private long createdAt;
    private long updatedAt;

    public CredentialItem() {
        // Firestore yêu cầu constructor rỗng
    }

    public CredentialItem(
            String title,
            String username,
            String password,
            String autofillDomain,
            String autofillPackage,
            String category,
            String note
    ) {
        this.title = title;
        this.username = username;
        this.password = password;
        this.autofillDomain = autofillDomain;
        this.autofillPackage = autofillPackage;
        this.category = category;
        this.note = note;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getAutofillPackage() {
        return autofillPackage;
    }

    public String getAutofillDomain() {
        return autofillDomain;
    }

    public String getCategory() {
        return category;
    }

    public String getNote() {
        return note;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setAutofillPackage(String autofillPackage) {
        this.autofillPackage = autofillPackage;
    }

    public void setAutofillDomain(String autofillDomain) {
        this.autofillDomain = autofillDomain;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public void setCreatedAt(long createAt) {
        this.createdAt = createAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
