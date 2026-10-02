# Hướng dẫn hoàn thiện Autofill với phần "Tài khoản gần đây"

Tài liệu này hướng dẫn cách cấu trúc lại `FillRequestHelper.java` và `UnlockActivity.java` để:
1. Khi chưa xác thực: Hiển thị duy nhất một nút **"Mở khóa Vault"**.
2. Khi đã xác thực: Sắp xếp tài khoản dùng gần nhất (`lastUsedId`) lên đầu tiên, hiển thị header phân mục **"Tài khoản gần đây"**, và liệt kê các tài khoản khác ngay bên dưới.

---

## 1. Cập nhật `FillRequestHelper.java`

Thay thế toàn bộ đoạn xử lý trong `FillRequestHelper.handle()` từ phần `if (credentials == null || credentials.isEmpty())` cho đến hết vòng lặp thành:

```java
                // ====================================
                // CHƯA XÁC THỰC -> Hiển thị 1 nút "Mở khóa Vault"
                // ====================================
                if (!AutofillAuthSession.isAuthenticated()) {
                    IntentSender authenticationIntent =
                            createUnlockVaultAuthenticationIntent(
                                    service,
                                    uid,
                                    webDomain,
                                    packageName,
                                    usernameId,
                                    passwordId,
                                    filledUsername
                            );

                    Dataset.Builder datasetBuilder = new Dataset.Builder();

                    RemoteViews authPresentation =
                            new RemoteViews(
                                    service.getPackageName(),
                                    R.layout.autofill_dataset
                            );

                    authPresentation.setTextViewText(
                            R.id.tvUsername,
                            "Mở khóa Vault"
                    );

                    authPresentation.setTextViewText(
                            R.id.tvSubtitle,
                            "Xác thực để điền mật khẩu"
                    );
                    authPresentation.setViewVisibility(R.id.tvHeader, android.view.View.GONE);

                    if (usernameId != null) {
                        datasetBuilder.setValue(usernameId, null, authPresentation);
                    }
                    if (passwordId != null) {
                        datasetBuilder.setValue(passwordId, null, authPresentation);
                    }

                    datasetBuilder.setAuthentication(authenticationIntent);
                    responseBuilder.addDataset(datasetBuilder.build());

                    FillResponse response = responseBuilder.build();
                    Log.d(TAG, "Gửi yêu cầu Mở khóa Vault cho Android OS");
                    callback.onSuccess(response);
                    return;
                }

                // ====================================
                // ĐÃ XÁC THỰC -> Sắp xếp Last Used lên đầu & Build các Dataset thật
                // ====================================
                if (!CryptoSession.isActive()) {
                    try {
                        CryptoSession.restore(service, uid);
                    } catch (Exception e) {
                        Log.e(TAG, "Không thể khôi phục CryptoSession", e);
                        AutofillAuthSession.clear();
                        callback.onSuccess(null);
                        return;
                    }
                }

                CryptoManager cryptoManager = CryptoSession.get();

                String contextKey = webDomain != null ? webDomain : packageName;
                String lastUsedId = AutofillAuthSession.getLastUsedCredentialId(contextKey);

                // Sắp xếp: Đưa tài khoản gần đây lên đầu
                credentials.sort((c1, c2) -> {
                    if (c1.getId().equals(lastUsedId)) return -1;
                    if (c2.getId().equals(lastUsedId)) return 1;
                    return 0;
                });

                for (CredentialEntity entity : credentials) {
                    if (cancellationSignal.isCanceled()) return;

                    try {
                        String username = cryptoManager.decrypt(entity.getUsername());
                        String password = cryptoManager.decrypt(entity.getPassword());

                        if (username == null || password == null) {
                            continue;
                        }

                        boolean isLastUsed = entity.getId().equals(lastUsedId);

                        RemoteViews presentation =
                                new RemoteViews(
                                        service.getPackageName(),
                                        R.layout.autofill_dataset
                                );

                        presentation.setTextViewText(
                                R.id.tvUsername,
                                username
                        );

                        if (isLastUsed) {
                            presentation.setViewVisibility(R.id.tvHeader, android.view.View.VISIBLE);
                            presentation.setTextViewText(
                                    R.id.tvSubtitle,
                                    "Đã dùng gần đây • Vault Logic"
                            );
                        } else {
                            presentation.setViewVisibility(R.id.tvHeader, android.view.View.GONE);
                            presentation.setTextViewText(
                                    R.id.tvSubtitle,
                                    "Vault Logic"
                            );
                        }

                        Dataset.Builder datasetBuilder = new Dataset.Builder();

                        if (usernameId != null) {
                            datasetBuilder.setValue(
                                    usernameId,
                                    AutofillValue.forText(username),
                                    presentation
                            );
                        }

                        if (passwordId != null) {
                            datasetBuilder.setValue(
                                    passwordId,
                                    AutofillValue.forText(password),
                                    presentation
                            );
                        }

                        responseBuilder.addDataset(datasetBuilder.build());
                        datasetCount++;

                    } catch (Exception e) {
                        Log.e(TAG, "Lỗi xử lý credential: " + entity.getId(), e);
                    }
                }

                if (datasetCount == 0) {
                    callback.onSuccess(null);
                    return;
                }
```

---

## 2. Cập nhật `UnlockActivity.java` (Method `returnAutofillFillResponse`)

Đảm bảo khi trả về `FillResponse` sau khi xác thực thành công, method cũng sắp xếp và đánh dấu `lastUsedId`:

```java
    private void returnAutofillFillResponse() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                CryptoManager cryptoManager = CryptoSession.get();

                CredentialDao credentialDao =
                        AppDatabase
                                .getInstance(getApplicationContext())
                                .credentialDao();

                List<CredentialEntity> credentials;
                if (autofillWebDomain != null && !autofillWebDomain.isEmpty()) {
                    String normalizedDomain = AutofillFieldUtil.normalizeDomain(autofillWebDomain);
                    credentials = credentialDao.getByAutofillDomain(uid, normalizedDomain);
                } else if (autofillPackageName != null && !autofillPackageName.isEmpty()) {
                    credentials = credentialDao.getByAutofillPackage(uid, autofillPackageName);
                } else {
                    credentials = credentialDao.getAllList(uid);
                }

                if (credentials == null || credentials.isEmpty()) {
                    runOnUiThread(() -> {
                        setResult(RESULT_CANCELED);
                        finish();
                    });
                    return;
                }

                String contextKey = autofillWebDomain != null ? autofillWebDomain : autofillPackageName;
                String lastUsedId = AutofillAuthSession.getLastUsedCredentialId(contextKey);

                credentials.sort((c1, c2) -> {
                    if (c1.getId().equals(lastUsedId)) return -1;
                    if (c2.getId().equals(lastUsedId)) return 1;
                    return 0;
                });

                FillResponse.Builder responseBuilder = new FillResponse.Builder();
                int count = 0;

                for (CredentialEntity entity : credentials) {
                    try {
                        String username = cryptoManager.decrypt(entity.getUsername());
                        String password = cryptoManager.decrypt(entity.getPassword());

                        if (username == null || password == null) {
                            continue;
                        }

                        // Nếu chưa có lastUsed, mặc định gán tài khoản đầu tiên làm gần đây nhất
                        if (count == 0 && lastUsedId == null) {
                            AutofillAuthSession.setLastUsed(contextKey, entity.getId());
                        }

                        boolean isLastUsed = entity.getId().equals(lastUsedId) || (count == 0 && lastUsedId == null);

                        RemoteViews presentation =
                                new RemoteViews(
                                        getPackageName(),
                                        R.layout.autofill_dataset
                                );

                        presentation.setTextViewText(
                                R.id.tvUsername,
                                username
                        );

                        if (isLastUsed) {
                            presentation.setViewVisibility(R.id.tvHeader, android.view.View.VISIBLE);
                            presentation.setTextViewText(
                                    R.id.tvSubtitle,
                                    "Đã dùng gần đây • Vault Logic"
                            );
                        } else {
                            presentation.setViewVisibility(R.id.tvHeader, android.view.View.GONE);
                            presentation.setTextViewText(
                                    R.id.tvSubtitle,
                                    "Vault Logic"
                            );
                        }

                        Dataset.Builder datasetBuilder = new Dataset.Builder();

                        if (autofillUsernameId != null) {
                            datasetBuilder.setValue(
                                    autofillUsernameId,
                                    AutofillValue.forText(username),
                                    presentation
                                );
                        }

                        if (autofillPasswordId != null) {
                            datasetBuilder.setValue(
                                    autofillPasswordId,
                                    AutofillValue.forText(password),
                                    presentation
                                );
                        }

                        responseBuilder.addDataset(datasetBuilder.build());
                        count++;

                    } catch (Exception e) {
                        Log.e("AUTOFILL_AUTH", "Lỗi decrypt credential", e);
                    }
                }

                if (count == 0) {
                    runOnUiThread(() -> {
                        setResult(RESULT_CANCELED);
                        finish();
                    });
                    return;
                }

                FillResponse fillResponse = responseBuilder.build();

                Intent resultIntent = new Intent();
                resultIntent.putExtra(
                        AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                        fillResponse
                );

                runOnUiThread(() -> {
                    setResult(RESULT_OK, resultIntent);
                    finished = true;
                    finish();
                });

            } catch (Exception e) {
                Log.e(
                        "AUTOFILL_AUTH",
                        "Không thể trả FillResponse sau xác thực",
                        e
                );

                runOnUiThread(() -> {
                    setResult(RESULT_CANCELED);
                    finish();
                });
            }
        });
    }
```
