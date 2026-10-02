package com.example.passwordmanager.autofill;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.service.autofill.FillContext;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.local.CredentialEntity;
import com.example.passwordmanager.data.sync.NetworkManager;
import com.example.passwordmanager.data.sync.SyncManager;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.utils.AutofillFieldUtil;
import com.example.passwordmanager.utils.CredentialUIUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;
import java.util.UUID;

/** Xử lý Save request; dùng chung field detection với Fill. */
public final class SaveRequestHelper {
    private static final String TAG = "PasswordAutofillSave";

    private SaveRequestHelper() {}

    /** Xử lý một SaveRequest. */
    public static void handle(
            @NonNull PasswordAutofillService service,
            @NonNull SaveRequest request,
            @NonNull SaveCallback callback
    ) {

        Log.d(TAG, "========== SAVE AUTOFILL REQUEST ==========");
        AppContainer.getInstance(service).getBackgroundExecutor().execute(() -> {

            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

                if (user == null) {
                    callback.onSuccess();
                    return;
                }

                String uid = user.getUid();

                if (!CryptoSession.isActive()) {
                    try {
                        CryptoSession.restore(service, uid);
                    } catch (Exception e) {
                        Log.e(TAG, "Không thể khôi phục CryptoSession để Save", e);
                        callback.onSuccess();
                        return;
                    }
                }

                CryptoManager cryptoManager = CryptoSession.get();
                List<FillContext> contexts = request.getFillContexts();

                if (contexts == null || contexts.isEmpty()) {
                    callback.onSuccess();
                    return;
                }

                AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
                String webDomain = AutofillFieldUtil.findWebDomain(structure);
                ComponentName componentName = structure.getActivityComponent();

                String packageName = componentName != null
                        ? componentName.getPackageName()
                        : null;

                // Trích xuất giá trị đã gõ trong ô Username & Password
                String[] extractedCredentials = AutofillFieldUtil.extractFilledCredentials(structure);
                String username = extractedCredentials[0];
                String password = extractedCredentials[1];

                if (username == null || username.trim().isEmpty()
                        || password == null || password.trim().isEmpty()
                ) {
                    Log.d(TAG, "Không tìm thấy dữ liệu nhập hợp lệ để lưu");
                    callback.onSuccess();
                    return;
                }

                // Xác định Domain / Package & Tên tiêu đề
                String domain = AutofillFieldUtil.normalizeDomain(webDomain);
                String appName = CredentialUIUtils.getAppNameFromPackage(service, packageName);
                String title = domain != null
                        ? domain
                        : (appName != null ? appName : packageName);
                Log.d(TAG, "Đang lưu tài khoản mới: " + title + " | " + username);

                // Mã hóa và lưu vào Room Database
                long now = System.currentTimeMillis();
                CredentialEntity entity = new CredentialEntity(
                        uid,
                        UUID.randomUUID().toString(),
                        cryptoManager.encrypt(title),
                        domain,
                        domain != null ? null : packageName,
                        cryptoManager.encrypt(username),
                        cryptoManager.encrypt(password),
                        cryptoManager.encrypt("Khác"),
                        cryptoManager.encrypt("Tự động lưu từ Autofill"),
                        now,
                        now,
                        CredentialEntity.PENDING_CREATE
                );

                // Kiểm tra xem đã có tài khoản với domain/package và username này chưa để tránh lưu trùng lặp
                CredentialDao credentialDao = AppDatabase.getInstance(service).credentialDao();
                List<CredentialEntity> existingCredentials;

                if (domain != null && !domain.isEmpty()) {
                    existingCredentials = credentialDao.getByAutofillDomain(uid, domain);
                } else if (packageName != null && !packageName.isEmpty()) {
                    existingCredentials = credentialDao.getByAutofillPackage(uid, packageName);
                } else {
                    existingCredentials = null;
                }

                boolean alreadyExists = false;

                if (existingCredentials != null) {
                    for (CredentialEntity existingEntity : existingCredentials) {
                        try {
                            String decryptedUsername = cryptoManager.decrypt(existingEntity.getUsername());

                            if (decryptedUsername != null && decryptedUsername.equalsIgnoreCase(username)) {
                                alreadyExists = true;
                                Log.d(TAG, "Tài khoản với username này đã tồn tại, bỏ qua save request trùng lặp.");
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (alreadyExists) {
                    callback.onSuccess();
                    return;
                }

                // Nếu chưa có mới tiến hành insert vào database như bình thường
                credentialDao.insert(entity);
                Log.d(TAG, "LƯU TÀI KHOẢN THÀNH CÔNG!");

                // Tài khoản vừa lưu tự động tính là vừa dùng gần nhất cho context này.
                String contextKey = domain != null ? domain : packageName;
                AutofillAuthSession.setLastUsed(service, contextKey, entity.getId());

                // Đẩy lên Firestore ngay (không chờ đổi mạng / lần sửa kế tiếp).
                syncNow(service, credentialDao);

                callback.onSuccess();

            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi thực hiện Save Autofill", e);
                callback.onSuccess();
            }
        });
    }

    /**
     * Đẩy tài khoản vừa lưu lên Firestore ngay (giống CredentialRepository.syncNow()).
     *
     * - Offline thì bỏ qua: NetworkManager sẽ sync khi có mạng lại.
     * - Không được ném ngoại lệ: lỗi sync không được ảnh hưởng kết quả Save.
     */
    private static void syncNow(
            @NonNull PasswordAutofillService service,
            @NonNull CredentialDao credentialDao
    ) {
        try {
            if (!NetworkManager.isNetworkConnected(service)) {
                Log.d(TAG, "Offline -> để NetworkManager sync khi có mạng");
                return;
            }

            new SyncManager(
                    credentialDao,
                    AppContainer.getInstance(service).getBackgroundExecutor()
            ).sync();

        } catch (Exception e) {
            Log.e(TAG, "Không thể kích hoạt sync", e);
        }
    }
}
