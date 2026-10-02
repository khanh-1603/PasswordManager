package com.example.passwordmanager.autofill;

import android.content.Context;
import android.content.IntentSender;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.local.CredentialEntity;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.utils.AutofillFieldUtil;
import com.example.passwordmanager.utils.AutofillUIUtil;

import java.util.List;

/**
 * Helper chuyên trách xử lý quy trình dữ liệu Autofill (Query DB -> Decrypt -> Tạo FillResponse/Dataset).
 * Tách khỏi ViewModel và Activity để tuân thủ MVVM / Single Responsibility Principle.
 */
public final class AutofillResponseHelper {

    private AutofillResponseHelper() {
        // Utility class không tạo instance
    }

    /**
     * Dựng FillResponse chứa danh sách các credential đã giải mã sau khi mở khóa Vault thành công.
     */
    public static FillResponse buildUnlockedFillResponse(
            Context context,
            String uid,
            String webDomain,
            String packageName,
            AutofillId usernameId,
            AutofillId passwordId
    ) {
        try {
            CryptoManager cryptoManager = CryptoSession.get();
            CredentialDao credentialDao = AppDatabase.getInstance(context).credentialDao();

            List<CredentialEntity> credentials;

            if (webDomain != null && !webDomain.isEmpty()) {
                String normalizedDomain = AutofillFieldUtil.normalizeDomain(webDomain);
                credentials = credentialDao.getByAutofillDomain(uid, normalizedDomain);
            } else if (packageName != null && !packageName.isEmpty()) {
                credentials = credentialDao.getByAutofillPackage(uid, packageName);
            } else {
                credentials = credentialDao.getAllList(uid);
            }

            if (credentials == null || credentials.isEmpty()) {
                return null;
            }

            String contextKey;
            if (webDomain != null && !webDomain.isEmpty()) {
                contextKey = AutofillFieldUtil.normalizeDomain(webDomain);
            } else {
                contextKey = packageName;
            }

            String lastUsedId = AutofillAuthSession.getLastUsedCredentialId(context, contextKey);

            if (lastUsedId != null) {
                credentials.sort((c1, c2) -> {
                    if (c1.getId().equals(lastUsedId)) return -1;
                    if (c2.getId().equals(lastUsedId)) return 1;
                    return 0;
                });
            }

            FillResponse.Builder responseBuilder = new FillResponse.Builder();
            Bundle clientState = new Bundle();
            clientState.putString("autofill_context_key", contextKey);

            responseBuilder.setClientState(clientState);

            int count = 0;

            for (CredentialEntity entity : credentials) {
                try {
                    String username = cryptoManager.decrypt(entity.getUsername());
                    String password = cryptoManager.decrypt(entity.getPassword());

                    if (username == null || password == null) {
                        continue;
                    }

                    boolean isLastUsed = entity.getId().equals(lastUsedId);

                    RemoteViews usernamePresentation = AutofillUIUtil.createDatasetPresentation(
                            context, username, isLastUsed
                    );
                    RemoteViews passwordPresentation = AutofillUIUtil.createDatasetPresentation(
                            context, username, isLastUsed
                    );

                    Dataset.Builder datasetBuilder = new Dataset.Builder();
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
                    count++;

                } catch (Exception e) {
                    Log.e("AUTOFILL_AUTH", "Lỗi xử lý credential: " + entity.getId(), e);
                }
            }

            if (count == 0) {
                return null;
            }

            return responseBuilder.build();

        } catch (Exception e) {
            Log.e("AUTOFILL_AUTH", "Không thể tạo FillResponse sau xác thực", e);
            return null;
        }
    }

    /**
     * Dựng Dataset thật cho một credential cụ thể mà người dùng vừa chọn.
     */
//    public static Dataset buildSelectedCredentialDataset(
//            Context context,
//            String uid,
//            String credentialId,
//            String contextKey,
//            AutofillId usernameId,
//            AutofillId passwordId
//    ) {
//        try {
//            if (!AutofillAuthSession.isAuthenticated()) {
//                return null;
//            }
//
//            if (!CryptoSession.isActive()) {
//                CryptoSession.restore(context, uid);
//            }
//
//            CryptoManager cryptoManager = CryptoSession.get();
//            CredentialDao credentialDao = AppDatabase.getInstance(context).credentialDao();
//
//            CredentialEntity entity = credentialDao.getById(uid, credentialId);
//            if (entity == null) {
//                return null;
//            }
//
//            String username = cryptoManager.decrypt(entity.getUsername());
//            String password = cryptoManager.decrypt(entity.getPassword());
//
//            if (username == null || password == null) {
//                return null;
//            }
//
//            String lastUsedContextKey = contextKey;
//            if (lastUsedContextKey != null && !lastUsedContextKey.isEmpty() && lastUsedContextKey.contains(".")) {
//                lastUsedContextKey = AutofillFieldUtil.normalizeDomain(lastUsedContextKey);
//            }
//
//            Log.d("AUTOFILL_SELECT", "setLastUsed: context=" + lastUsedContextKey + ", credential=" + credentialId);
//
//            AutofillAuthSession.setLastUsed(lastUsedContextKey, credentialId);
//
//            RemoteViews usernamePresentation = AutofillUIUtil.createDatasetPresentation(
//                    context, username, true
//            );
//            RemoteViews passwordPresentation = AutofillUIUtil.createDatasetPresentation(
//                    context, username, true
//            );
//
//            Dataset.Builder datasetBuilder = new Dataset.Builder();
//
//            if (usernameId != null) {
//                datasetBuilder.setValue(
//                        usernameId,
//                        AutofillValue.forText(username),
//                        usernamePresentation
//                );
//            }
//
//            if (passwordId != null) {
//                datasetBuilder.setValue(
//                        passwordId,
//                        AutofillValue.forText(password),
//                        passwordPresentation
//                );
//            }
//
//            return datasetBuilder.build();
//
//        } catch (Exception e) {
//            Log.e("AUTOFILL_SELECT", "Không thể trả credential đã chọn", e);
//            return null;
//        }
//    }
}