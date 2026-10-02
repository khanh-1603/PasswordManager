package com.example.passwordmanager.firebase;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentSnapshot;

/**
 * Interface trừu tượng cho SecurityRepository (lưu/lấy Salt trên Firestore).
 */
public interface ISecurityRepository {
    Task<DocumentSnapshot> getSecurityData(String uid);
    Task<Void> saveSalt(String uid, String salt);
}
