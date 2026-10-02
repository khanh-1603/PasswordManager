package com.example.passwordmanager.viewmodel;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.passwordmanager.data.model.CredentialItem;
import com.example.passwordmanager.data.repository.ICredentialRepository;
import com.example.passwordmanager.data.sync.NetworkManager;
import com.example.passwordmanager.data.sync.SyncManager;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.utils.PasswordGenerator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


/**
 * ViewModel trung tâm quản lý dữ liệu Tài khoản (Credential).
 * Phục vụ cho VaultFragment (Danh sách), AddCredentialActivity (Thêm mới)
 * và CredentialDetailActivity (Xem/Chỉnh sửa chi tiết).
 */
public class CredentialViewModel extends ViewModel {
    private final ICredentialRepository repository;

    // Đồng bộ Room <-> Firestore. Được sở hữu bởi ViewModel vì
    // đây là business logic, và onCleared() là hook dọn dẹp đáng tin cậy hơn
    // onDestroyView (không bị gọi lặp lại mỗi lần View bị tạo lại).
    private NetworkManager networkManager;

    // ====================================
    // Credentials từ Room
    // ====================================

    // Dữ liệu danh sách tài khoản LiveData từ Room Database
    private final LiveData<List<CredentialItem>> allCredentialsLiveData;

    // Danh sách sau khi đã lọc theo Từ khóa Tìm kiếm + Danh mục
    private final MediatorLiveData<List<CredentialItem>>
            filteredCredentials = new MediatorLiveData<>();

    // Tài khoản chi tiết được chọn
    private final MutableLiveData<CredentialItem>
            selectedCredential = new MutableLiveData<>();

    // Trạng thái thông báo lỗi UI
    private final MutableLiveData<String>
            errorMessage = new MutableLiveData<>();

    public enum Operation {
        NONE,
        ADD,
        UPDATE,
        DELETE,
    }
    private enum PendingAction {
        NONE,
        VIEW_PASSWORD,
        COPY_PASSWORD
    }

    // Event bắn yêu cầu mở UnlockActivity cho View
    private final MutableLiveData<Boolean> requireUnlockEvent =
            new MutableLiveData<>(false);

    // Trạng thái hành động đang chờ
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingCopyLabel;
    private String pendingCopyText;

    // Trạng thái thao tác CRUD (ADD, UPDATE, DELETE)
    private final MutableLiveData<Operation>
            operation = new MutableLiveData<>(Operation.NONE);

    // Trạng thái Ẩn/Hiện Mật khẩu (UI State)
    private final MutableLiveData<Boolean>
            isPasswordVisible = new MutableLiveData<>(false);

    // Trạng thái Đánh giá độ mạnh Mật khẩu (Điểm 0 -> 4)
    private final MutableLiveData<Integer>
            passwordStrength = new MutableLiveData<>(0);

    private String searchQuery = "";
    private String selectedCategory = "Tất cả";

    public CredentialViewModel(ICredentialRepository repository) {
        this.repository = repository;

        // Tự động cập nhật danh sách khi Room Database có thay đổi (INSERT/UPDATE/DELETE)
        allCredentialsLiveData = repository.getCredentials();

        // Khi Room phát dữ liệu mới -> filter lại
        filteredCredentials.addSource(
                allCredentialsLiveData,
                this::applyFilter
        );
    }

    // ====================================
    // Sync (Room <-> Firestore)
    // ====================================

    /**
     * Bắt đầu theo dõi mạng và tự động sync khi có kết nối trở lại.
     * Idempotent — gọi nhiều lần (vd: View bị tạo lại khi xoay màn hình)
     * chỉ đăng ký callback đúng 1 lần cho đến khi ViewModel bị onCleared().
     */
    public void startNetworkSync(Context appContext) {
        if (networkManager != null) {
            return;
        }

        SyncManager syncManager =
                new SyncManager(
                        repository.getCredentialDao(),
                        AppContainer.getInstance(appContext).getBackgroundExecutor()
                );

        networkManager = new NetworkManager(
                appContext.getApplicationContext(),
                syncManager
        );
        networkManager.startMonitoring();
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        if (networkManager != null) {
            networkManager.stopMonitoring();
            networkManager = null;
        }
    }

    // ====================================
    // LiveData Getters
    // ====================================

    public LiveData<Boolean> getRequireUnlockEvent() {
        return requireUnlockEvent;
    }

    public LiveData<List<CredentialItem>> getCredentials() {
        return filteredCredentials;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    public LiveData<Operation> getOperation() {
        return operation;
    }

    public LiveData<CredentialItem> getSelectedCredential() {
        return selectedCredential;
    }

    public LiveData<Boolean> getIsPasswordVisible() {
        return isPasswordVisible;
    }

    public LiveData<Integer> getPasswordStrength() {
        return passwordStrength;
    }

    // ====================================
    // Search & Category Filter
    // ====================================

    /// Cập nhật từ khóa tìm kiếm và lọc lại danh sách tài khoản.
    public void setSearchQuery(String query) {
        searchQuery = query == null ? "" : query.trim();
        applyFilter(allCredentialsLiveData.getValue());
    }

    /// Cập nhật danh mục lọc (Ví dụ: "Ngân hàng", "Mạng xã hội"...)
    public void setSelectedCategory(String category) {
        selectedCategory = category == null ? "Tất cả" : category;
        applyFilter(allCredentialsLiveData.getValue());
    }

    private void applyFilter(List<CredentialItem> source) {
        List<CredentialItem> filtered = new ArrayList<>();

        // Khi Room chưa trả dữ liệu
        if (source == null) {
            filteredCredentials.setValue(filtered);
            return;
        }

        String query = searchQuery.toLowerCase(Locale.getDefault());

        for (CredentialItem item : source) {
            if (item == null) {
               continue;
            }

            String title = item.getTitle() == null
                    ? ""
                    : item.getTitle().toLowerCase(Locale.getDefault());

            String username = item.getUsername() == null
                    ? ""
                    : item.getUsername().toLowerCase(Locale.getDefault());

           boolean matchQuery = searchQuery.isEmpty()
                   || title.contains(query)
                   || username.contains(query);

            String category = item.getCategory() == null
                    ? "Khác"
                    : item.getCategory();

           boolean matchCategory =
                   selectedCategory.equals("Tất cả")
                   || selectedCategory.equals(category);

           if (matchCategory && matchQuery) {
               filtered.add(item);
           }
        }

        filteredCredentials.setValue(filtered);
    }

    // ====================================
    // CRUD Operations
    // ====================================

    //// Thêm tài khoản mới vào Room Database.
    public void addCredential(CredentialItem credential) {
        repository.addCredential(
                credential,
                () -> operation.setValue(Operation.ADD),
                errorMessage::setValue
        );
    }

    /// Cập nhật tài khoản hiện có trong Room Database.
    public void updateCredential(CredentialItem credential) {
        repository.updateCredential(
                credential,
                () -> operation.setValue(Operation.UPDATE),
                errorMessage::setValue
        );
    }

    /// Xóa tài khoản theo ID khỏi Room Database.
    public void deleteCredential(String id) {
        repository.deleteCredential(
                id,
                () -> operation.setValue(Operation.DELETE),
                errorMessage::setValue
        );
    }


    /// Tải thông tin tài khoản chi tiết từ Room Database theo ID.
    public void loadCredential(String id) {
        repository.getCredential(
                id,
                selectedCredential::setValue,
                errorMessage::setValue
        );
    }

    /// Đặt lại trạng thái thao tác về NONE sau khi Activity xử lý xong.
    public void resetOperation() {
        operation.setValue(Operation.NONE);
    }

    // ====================================
    // Password UI State & Helpers
    // ====================================

    /**
     * Đánh giá độ mạnh của Mật khẩu dựa trên độ dài và độ phức tạp ký tự.
     * Điểm từ 0 (Yếu nhất) đến 4 (Rất mạnh).
     */
    public void evaluatePasswordStrength(String password) {
        if (password == null || password.isEmpty()) {
            passwordStrength.setValue(0);
            return;
        }

        int score = 0;
        if (password.length() >= 8) score++;
        if (password.length() >= 12) score++;

        if (password.matches(".*[A-Z].*")
                && password.matches(".*[a-z].*")
        ) score++;

        if (password.matches(".*[0-9].*")
                && password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")
        ) score++;

        passwordStrength.setValue(score);
    }

    /// Sinh chuỗi mật khẩu ngẫu nhiên an toàn có độ dài chỉ định.
    public String generateRandomPassword(int length) {
        String generated = PasswordGenerator.generatePassword(length);
        evaluatePasswordStrength(generated);
        return generated;
    }

    /// Chuẩn hóa domain website (Ví dụ: "https://www.facebook.com/login" -> "facebook.com")
    public String normalizeDomain(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {
            String domain = value.trim().toLowerCase(java.util.Locale.US);

            // Cắt bỏ giao thức http://, https://, www.
            if (domain.startsWith("https://")) {
                domain = domain.substring(8);
            }

            if (domain.startsWith("http://")) {
                domain = domain.substring(7);
            }

            if (domain.startsWith("www.")) {
                domain = domain.substring(4);
            }

            // Cắt bỏ cổng port (:8000) và đường dẫn (/login)
            int colonIndex = domain.indexOf(':');
            if (colonIndex >= 0) {
                domain = domain.substring(0, colonIndex);
            }

            int slashIndex = domain.indexOf('/');
            if (slashIndex >= 0) {
                domain = domain.substring(0, slashIndex);
            }

            // Nếu là địa chỉ IP (vd: 10.0.2.2 hoặc 172.16.64.74) -> Giữ nguyên
            if (domain.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return domain;
            }

            // Trích xuất Domain gốc (Tách theo dấu chấm)
            String[] parts = domain.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }

            return domain;

        } catch (Exception e) {
            return value.trim().toLowerCase(java.util.Locale.US);
        }
    }

    /// Validate dữ liệu khi chỉnh sửa tài khoản và tự động gọi Update nếu hợp lệ.
    public boolean validateAndAddOrUpdate(
            String id,
            String category,
            String title,
            String autofillDomain,
            String autofillPackage,
            String username,
            String password,
            String notes

    ) {
        if (category == null || category.trim().isEmpty()) {
            errorMessage.setValue("Vui lòng chọn phân loại");
            return false;
        }

        if (title == null || title.trim().isEmpty()) {
            errorMessage.setValue("Vui lòng nhập tên ứng dụng / web");
            return false;
        }

        if (autofillDomain == null && autofillPackage == null) {
            errorMessage.setValue("Vui lòng chọn website hoặc ứng dụng");
            return false;
        }

        if (username == null || username.trim().isEmpty()) {
            errorMessage.setValue("Vui lòng nhập tên đăng nhập");
            return false;
        }

        if (password == null || password.trim().isEmpty()) {
            errorMessage.setValue("Vui lòng nhập mật khẩu");
            return false;
        }

        String domain = normalizeDomain(autofillDomain);

        CredentialItem item = new CredentialItem(
                title.trim(),
                username.trim(),
                password,
                domain,
                autofillPackage,
                category,
                notes.trim()
        );

        boolean isUpdate = (id != null && !id.trim().isEmpty());

        if (isUpdate) {
            item.setId(id);
            item.setUpdatedAt(System.currentTimeMillis());
            updateCredential(item);

        } else {
            long now = System.currentTimeMillis();
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            addCredential(item);
        }

        return true;
    }

    /// Định dạng thời gian cập nhật thành chuỗi văn bản ngày tháng dễ đọc.
    public String formatUpdatedTime(long timestamp) {
        if (timestamp <= 0) return "Chưa cập nhật";
        SimpleDateFormat sdf =
                new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

        return "Cập nhật lần cuối: " + sdf.format(new Date(timestamp));
    }

    /// Sao chép văn bản vào Bộ nhớ tạm (Clipboard) của thiết bị.
    public void copyToClipboard(Context context, String label, String text) {
        if (text == null || text.trim().isEmpty()) {
            errorMessage.setValue("Không có dữ liệu để sao chép");
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) context
                .getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(label, text);

            // Đánh dấu nội dung nhạy cảm (cho Android 13 / API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }

            clipboard.setPrimaryClip(clip);

            // Tự động xóa Clipboard sau 30 giây (30000 ms)
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    ClipboardManager currentClipboard = (ClipboardManager) context
                            .getSystemService(Context.CLIPBOARD_SERVICE);

                    if (currentClipboard != null) {
                        // Xóa bộ nhớ tạm
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            currentClipboard.clearPrimaryClip();

                        } else {
                            currentClipboard.setPrimaryClip(
                                    ClipData.newPlainText("", "")
                            );
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 60000); // 60,000 miligiây = 60 giây
        }
    }

    /// User bấm Copy Password -> ViewModel xử lý
    public void requestCopyPassword(String label, String password) {
        this.pendingAction = PendingAction.COPY_PASSWORD;
        this.pendingCopyLabel = label;
        this.pendingCopyText = password;

        // Yêu cầu View mở UnlockActivity
        requireUnlockEvent.setValue(true);
    }

    /// User bấm Xem/Ẩn Password -> ViewModel xử lý
    public void requestTogglePasswordVisibility() {
        boolean currentVisible = Boolean.TRUE.equals(isPasswordVisible.getValue());

        if (currentVisible) {
            // Đang HIỆN -> Ẩn đi ngay, không cần unlock
            isPasswordVisible.setValue(false);
        } else {
            // Đang ẨN -> Đặt trạng thái chờ và yêu cầu View mở Unlock
            this.pendingAction = PendingAction.VIEW_PASSWORD;
            requireUnlockEvent.setValue(true);
        }
    }

    /// Reset event sau khi View đã nhận tín hiệu mở Activity
    public void onUnlockEventHandled() {
        requireUnlockEvent.setValue(false);
    }

    ///  Được gọi khi View báo lại UnlockActivity đã thành công (RESULT_OK)
    public void onUnlockSuccess(Context context) {
        switch (pendingAction) {
            case COPY_PASSWORD:
                copyToClipboard(context, pendingCopyLabel, pendingCopyText);
                errorMessage.setValue("Đã sao chép mật khẩu vào bộ nhớ tạm");
                break;

            case VIEW_PASSWORD:
                isPasswordVisible.setValue(true);
                break;

            case NONE:
            default:
                break;
        }

        // Reset state sau khi hoàn tất
        pendingAction = PendingAction.NONE;
        pendingCopyLabel = null;
        pendingCopyText = null;
    }
}
