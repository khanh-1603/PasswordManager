package com.example.passwordmanager.autofill;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillEventHistory;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.example.passwordmanager.R;
import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.local.CredentialEntity;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.ui.security.UnlockActivity;
import com.example.passwordmanager.utils.AutofillFieldUtil;
import com.example.passwordmanager.utils.AutofillUIUtil;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Xử lý Fill request; không chứa logic nhận diện field/domain dùng chung. */
public final class FillRequestHelper {
    // Các package hệ thống chắc chắn không phải form đăng nhập của app nào
    // (màn hình đặt PIN/khoá màn hình, SystemUI, IME...). Nếu không loại
    // trừ, một field password/numberPassword đơn lẻ trên các màn này (vd:
    // ChooseLockPin) sẽ bị coi là field cần Autofill/Save như bình thường.
    private static final Set<String> SYSTEM_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.android.systemui",
            "com.android.keyguard",
            "android"
    ));

    private static boolean isSystemPackage(String packageName) {
        return packageName != null && SYSTEM_PACKAGES.contains(packageName);
    }
    private static final String TAG = "PasswordAutofill";

    private FillRequestHelper() {}

    /** Xử lý một FillRequest. */
    public static void handle(
            @NonNull PasswordAutofillService service,
            @NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull FillCallback callback
    ) {

        Log.d(TAG, "========== AUTOFILL REQUEST ==========");
        AppContainer.getInstance(service).getBackgroundExecutor().execute(() -> {
            try {
                if (cancellationSignal.isCanceled()) {
                    Log.d(TAG, "Request đã bị cancel");
                    return;
                }

                // ====================================
                // User
                // ====================================

                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

                if (user == null) {
                    Log.d(TAG, "Không có user đăng nhập");
                    callback.onSuccess(null);
                    return;
                }

                String uid = user.getUid();

                // ====================================
                // AssistStructure
                // ====================================

                if (request.getFillContexts() == null
                        ||request.getFillContexts().isEmpty()) {

                    Log.d(TAG, "Không có FillContext");
                    callback.onSuccess(null);
                    return;
                }

                AssistStructure structure = request.getFillContexts()
                        .get(request.getFillContexts().size() - 1)
                        .getStructure();

                ComponentName activityComponent = structure.getActivityComponent();

                String packageName = activityComponent != null
                        ? activityComponent.getPackageName()
                        : null;

                Log.d(TAG, "Activity: " + activityComponent);
                Log.d(TAG, "Window count: " + packageName);

                // Không tự autofill/save cho chính app của mình (vd:
                // dialog nhập Master Password trong UnlockActivity, màn
                // Login/Register/ChangeMasterPassword...). Nếu không loại
                // trừ, các EditText password nội bộ này sẽ bị nhận diện
                // như field của app lạ -> tạo SaveInfo thừa -> Android tự
                // hỏi "Lưu mật khẩu?" ngay trong app quản lý mật khẩu.
                if (service.getPackageName().equals(packageName)) {
                    Log.d(TAG, "Bỏ qua vì đây là cửa sổ của chính app");
                    callback.onSuccess(null);
                    return;
                }

                // Danh sách package hệ thống KHÔNG BAO GIỜ nên autofill/save
                // (màn hình cài PIN, vân tay, khoá màn hình... không phải
                // form đăng nhập của bất kỳ tài khoản nào).
                if (isSystemPackage(packageName)) {
                    Log.d(TAG, "Bỏ qua vì đây là package hệ thống: " + packageName);
                    callback.onSuccess(null);
                    return;
                }

                // ====================================
                // Tìm field
                // ====================================

                AutofillId usernameId = null;
                AutofillId passwordId = null;

                for (int i = 0; i < structure.getWindowNodeCount(); i++) {
                    AssistStructure.WindowNode windowNode = structure.getWindowNodeAt(i);
                    AutofillId[] fields = AutofillFieldUtil.findAutofillFields(windowNode.getRootViewNode());

                    if (usernameId == null && fields.length > 0) {
                        usernameId = fields[0];
                    }

                    if (passwordId == null && fields.length > 1) {
                        passwordId = fields[1];
                    }
                }

                if (usernameId == null && passwordId == null) {
                    Log.d(TAG, "NO AUTOFILL FIELDS");
                    callback.onSuccess(null);

                    return;
                }

                // ====================================
                // Tìm domain website
                // ====================================

                String webDomain = AutofillFieldUtil.findWebDomain(structure);
                Log.d(TAG, "Web domain: " + webDomain);

                // ====================================
                // Xác định loại Autofill
                // ====================================

                List<CredentialEntity> credentials;
                CredentialDao credentialDao =
                        AppDatabase.getInstance(service).credentialDao();

                if (webDomain != null && !webDomain.isEmpty()) {
                    String normalizedDomain = AutofillFieldUtil.normalizeDomain(webDomain);
                    Log.d(TAG, "Query domain: " + normalizedDomain);
                    credentials = credentialDao.getByAutofillDomain(uid, normalizedDomain);

                } else if (packageName != null && !packageName.isEmpty()) {
                    Log.d(TAG, "Query package: " + packageName);
                    credentials = credentialDao.getByAutofillPackage(uid, packageName);

                } else {
                    Log.d(TAG, "Không xác định được domain/package");
                    callback.onSuccess(null);
                    return;
                }

                // ====================================
                // Không có credential
                // ====================================

                if (credentials == null || credentials.isEmpty()) {
                    if (usernameId != null || passwordId != null) {
                        AutofillId[] requiredIds;

                        if (usernameId != null && passwordId != null) {
                            requiredIds = new AutofillId[]{usernameId, passwordId};
                        } else if (passwordId != null) {
                            requiredIds = new AutofillId[]{passwordId};
                        } else {
                            requiredIds = new AutofillId[]{usernameId};
                        }

                        SaveInfo saveInfo = new SaveInfo.Builder(
                                SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                                requiredIds
                        )
                                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                                .build();

                        FillResponse response = new FillResponse.Builder()
                                .setSaveInfo(saveInfo)
                                .build();

                        Log.d(TAG, "Đã gửi SaveInfo cho Android OS (Sẵn sàng hiển thị Dialog hỏi lưu)");
                        callback.onSuccess(response);
                        return;
                    }

                    callback.onSuccess(null);
                    return;
                }

                Log.d(TAG,"Tìm thấy " + credentials.size() + " credential");

                // Builder dùng chung cho toàn bộ FillResponse của request.
                FillResponse.Builder responseBuilder =
                        new FillResponse.Builder();

                // Đếm số Dataset credential hợp lệ đã tạo.
                int datasetCount = 0;

                // ====================================
                // Chưa xác thực
                // ====================================

                if (!AutofillAuthSession.isAuthenticated()) {
                    IntentSender authenticationIntent = createUnlockVaultAuthenticationIntent(
                            service,
                            uid,
                            webDomain,
                            packageName,
                            usernameId,
                            passwordId
                    );

                    Dataset.Builder datasetBuilder = new Dataset.Builder();
                    RemoteViews authPresentation = new RemoteViews(
                            service.getPackageName(),
                            R.layout.autofill_dataset
                    );

                    authPresentation.setTextViewText( R.id.tvUsername, "Mở khóa Vault" );
                    authPresentation.setTextViewText( R.id.tvSubtitle, "Xác thực để điền mật khẩu" );

                    authPresentation.setViewVisibility( R.id.tvHeader, android.view.View.GONE );

                    /* Dataset authentication bắt buộc phải có ít nhất
                    * một field được setValue().
                    * Giá trị null là cố ý:
                    * credential thật chưa được decrypt ở bước này.
                    */
                    if (usernameId != null) {
                        datasetBuilder.setValue( usernameId, null, authPresentation );
                    }

                    if (passwordId != null) {
                        datasetBuilder.setValue( passwordId, null, authPresentation );
                    }

                    datasetBuilder.setAuthentication(authenticationIntent);
                    responseBuilder.addDataset( datasetBuilder.build() );

                    // LUÔN BẬT SAVE REQUEST NGAY CẢ KHI CHƯA MỞ KHÓA
                    if (usernameId != null || passwordId != null) {
                        AutofillId[] requiredIds;

                        if (usernameId != null && passwordId != null) {
                            requiredIds = new AutofillId[]{usernameId, passwordId};
                        } else if (passwordId != null) {
                            requiredIds = new AutofillId[]{passwordId};
                        } else {
                            requiredIds = new AutofillId[]{usernameId};
                        }

                        SaveInfo saveInfo = new SaveInfo.Builder(
                                SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                                requiredIds
                        )
                                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                                .build();

                        responseBuilder.setSaveInfo(saveInfo);
                    }

                    callback.onSuccess(responseBuilder.build());

                    Log.d(TAG, "Đã gửi Dataset Mở khóa Vault");
                    return;
                }

                // ====================================
                // Đã xác thực
                // ====================================

                // Lúc này mới được phép khôi phục CryptoSession
                // và decrypt credential.

                if (!CryptoSession.isActive()) {
                    try {
                        CryptoSession.restore(service, uid);
                    } catch (Exception e) {
                        Log.e(TAG, "Không thể khôi phục CryptoSession", e);

                        // CryptoSession không khôi phục được thì
                        // phiên Autofill hiện tại cũng không còn hợp lệ.
                        AutofillAuthSession.clear();
                        callback.onSuccess(null);
                        return;
                    }
                }

                CryptoManager cryptoManager = CryptoSession.get();

                // ====================================
                // Tài khoản gần đây
                // ====================================

                // Domain được ưu tiên làm context key.
                // Nếu không có domain thì dùng package.

                String contextKey;

                if (webDomain != null && !webDomain.isEmpty()) {
                    contextKey = AutofillFieldUtil.normalizeDomain(webDomain);
                } else {
                    contextKey = packageName;
                }

                // Lưu contextKey vào FillResponse.
                // Khi người dùng chọn Dataset, Android sẽ đưa clientState
                // trở lại trong FillEventHistory.
                // Nhờ đó PasswordAutofillService biết Dataset vừa chọn
                // thuộc domain/package nào.
                Bundle clientState = new Bundle();
                clientState.putString("autofill_context_key", contextKey);

                responseBuilder.setClientState(clientState);

                String lastUsedId = AutofillAuthSession.getLastUsedCredentialId(service ,contextKey);

                Log.d(
                        TAG,
                        "LAST USED: context="
                                + contextKey
                                + ", credential="
                                + lastUsedId
                );

                // Đưa credential được sử dụng gần đây lên đầu.
                if (lastUsedId != null) {
                    credentials.sort((c1, c2) -> {
                        if (c1.getId().equals(lastUsedId)) {
                            return -1;
                        }

                        if (c2.getId().equals(lastUsedId)) {
                            return 1;
                        }

                        return 0;
                    });
                }

                // ====================================
                // Tạo các Dataset thật
                // ====================================

                for (CredentialEntity entity : credentials) {
                    if (cancellationSignal.isCanceled()) {
                        Log.d(TAG, "Request bị cancel");
                        return;
                    }

                    try {
                        String username = cryptoManager.decrypt(entity.getUsername());
                        String password = cryptoManager.decrypt(entity.getPassword());

                        if (username == null || password == null) {
                            continue;
                        }

                        boolean isLastUsed = entity.getId().equals(lastUsedId);

                        // ====================================
                        // Presentation cho từng field
                        // ====================================

                        RemoteViews usernamePresentation =
                                AutofillUIUtil.createDatasetPresentation(
                                        service,
                                        username,
                                        isLastUsed
                                );

                        RemoteViews passwordPresentation =
                                AutofillUIUtil.createDatasetPresentation(
                                        service,
                                        username,
                                        isLastUsed
                                );

                        // ====================================
                        // Dataset
                        // ====================================

                        Dataset.Builder datasetBuilder = new Dataset.Builder();

                        /*
                         * ID của Dataset chính là ID của credential.
                         *
                         * Android sử dụng ID này trong FillEventHistory khi
                         * người dùng chọn Dataset.
                         *
                         * PasswordAutofillService sẽ đọc Dataset ID từ history
                         * và cập nhật AutofillAuthSession.lastUsed.
                         */
                        datasetBuilder.setId(entity.getId());

                        if (usernameId != null) {
                            datasetBuilder.setValue(
                                    usernameId,
                                    AutofillValue.forText(username),
                                    usernamePresentation
                            );
                        }

                        if (passwordId != null) {
                            datasetBuilder.setValue(
                                    passwordId,
                                    AutofillValue.forText(password),
                                    passwordPresentation
                            );
                        }

                        responseBuilder.addDataset(datasetBuilder.build());
                        datasetCount++;

                    } catch (Exception e) {
                        /* Một credential lỗi không được làm hỏng
                        * toàn bộ Autofill request.
                        */
                        Log.e( TAG, "Lỗi xử lý credential: " + entity.getId(), e );
                    }
                }

                // Không có credential hợp lệ để tạo Dataset.
                if (datasetCount == 0) {
                    callback.onSuccess(null);
                    return;
                }

                // ====================================
                // Đăng ký SaveInfo với Android OS
                // ====================================

                if (usernameId != null || passwordId != null) {
                    AutofillId[] requiredIds;

                    if (usernameId != null && passwordId != null) {
                        requiredIds = new AutofillId[]{usernameId, passwordId};
                    } else if (passwordId != null) {
                        requiredIds = new AutofillId[]{passwordId};
                    }else {
                        requiredIds = new AutofillId[]{usernameId};
                    }

                    SaveInfo saveInfo = new SaveInfo.Builder(
                            SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                            requiredIds
                    )
                            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                            .build();

                    // Gán SaveInfo vào Response
                    responseBuilder.setSaveInfo(saveInfo);
                }

                FillResponse response = responseBuilder.build();
                Log.d(TAG, "RETURNING " + datasetCount + " DATASETS WITH SAVEINFO");
                callback.onSuccess(response);

            } catch (Exception e) {
                Log.e(TAG,"AUTOFILL SERVICE CRASHED", e);
                callback.onFailure("Autofill error: " + e.getMessage());

            } finally {
                Log.d(TAG, "========== END REQUEST ==========");
            }
        });
    }

    private static IntentSender createUnlockVaultAuthenticationIntent(
            Context context,
            String uid,
            String webDomain,
            String packageName,
            AutofillId usernameId,
            AutofillId passwordId
    ) {
        Intent intent = new Intent(context, UnlockActivity.class);
        intent.putExtra("source", "AUTOFILL_UNLOCK_ALL");
        intent.putExtra("uid", uid);
        intent.putExtra("web_domain", webDomain);
        intent.putExtra("package_name", packageName);
        intent.putExtra("username_id", usernameId);
        intent.putExtra("password_id", passwordId);

        String contextKey = webDomain != null ? webDomain : packageName;

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                contextKey != null ? contextKey.hashCode() : 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        return pendingIntent.getIntentSender();
    }
}
