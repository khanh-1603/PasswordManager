package com.example.passwordmanager.firebase;


import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Lưu và lấy salt từ firebase.
 * Hoạt động hoàn toàn dựa vào mạng
 */
public class SecurityRepository implements ISecurityRepository {
    private final FirebaseFirestore firestore;

    public SecurityRepository() {
        firestore = FirebaseFirestore.getInstance();
    }

    /**
     users
       └── uid
            └── security
                  └── crypto
                        └── salt
     **/

    public Task<DocumentSnapshot> getSecurityData(String uid) {
        return firestore.collection("users")
                .document(uid)
                .collection("security")
                .document("crypto")
                .get();
    }

    public Task<Void> saveSalt(String uid, String salt) {
        Map<String, Object> data = new HashMap<>();

        data.put("salt", salt);

        return firestore.collection("users")
                .document(uid)
                .collection("security")
                .document("crypto")
                .set(data);
    }
}
