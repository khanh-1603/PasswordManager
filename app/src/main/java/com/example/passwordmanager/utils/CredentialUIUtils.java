package com.example.passwordmanager.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import com.example.passwordmanager.R;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Helper Utility dùng chung cho xử lý giao diện Credential (Add & Detail)
public class CredentialUIUtils {

    /// Cập nhật 4 thanh hiển thị độ mạnh mật khẩu và màu chữ
    public static void updateStrengthBarsUI(
            Context context,
            int score,
            View bar1,
            View bar2,
            View bar3,
            View bar4,
            TextView tvStrengthText
    ) {
        if (bar1 == null || tvStrengthText == null) return;

        // Reset trạng thái inactive
        bar1.setBackgroundResource(R.drawable.bg_strength_inactive);
        if (bar2 != null) bar2.setBackgroundResource(R.drawable.bg_strength_inactive);
        if (bar3 != null) bar3.setBackgroundResource(R.drawable.bg_strength_inactive);
        if (bar4 != null) bar4.setBackgroundResource(R.drawable.bg_strength_inactive);

        if (score <= 1) {
            bar1.setBackgroundResource(R.drawable.bg_strength_active_red);
            tvStrengthText.setText("Yếu");
            tvStrengthText.setTextColor(ContextCompat.getColor(context, R.color.error_red));
        } else if (score == 2) {
            bar1.setBackgroundResource(R.drawable.bg_strength_active_orange);
            if (bar2 != null) bar2.setBackgroundResource(R.drawable.bg_strength_active_orange);
            tvStrengthText.setText("Trung bình");
            tvStrengthText.setTextColor(ContextCompat.getColor(context, R.color.warning_orange));
        } else if (score == 3) {
            bar1.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            if (bar2 != null) bar2.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            if (bar3 != null) bar3.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            tvStrengthText.setText("Mạnh");
            tvStrengthText.setTextColor(ContextCompat.getColor(context, R.color.success_green));
        } else {
            bar1.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            if (bar2 != null) bar2.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            if (bar3 != null) bar3.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            if (bar4 != null) bar4.setBackgroundResource(R.drawable.bg_strength_active_emerald);
            tvStrengthText.setText("Rất mạnh");
            tvStrengthText.setTextColor(ContextCompat.getColor(context, R.color.success_green));
        }
    }

    /// Chuẩn hóa văn bản hiển thị cho Autofill Target (Website hoặc App Package)
    public static String getAutofillTargetText(
            Context context, String domain, String packageName
    ) {
        if (domain != null && !domain.isEmpty()) {
            return "🌐 Website: " + domain;

        } else if (packageName != null && !packageName.isEmpty()) {
            // Tự động chuyển Package Name thành Tên App dễ đọc
            String appName = getAppNameFromPackage(context, packageName);

            if (appName != null) {
                return "📱 App: " + appName;
            }

            return "📱 App Package: " + packageName;

        } else {
            return "Chưa chọn mục tiêu";
        }
    }

    /// Hàm phụ trợ đọc tên App từ Package Name
    public static String getAppNameFromPackage(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return null;
        }

            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                return pm.getApplicationLabel(appInfo).toString();

            } catch (PackageManager.NameNotFoundException e) {
                return null; // App không tồn tại hoặc đã bị gỡ cài đặt
            }
    }

    /// Cập nhật icon và màu nền theo danh mục
    public static void updateCategoryIcon(
            Context context,
            String category,
            ImageView ivIcon,
            FrameLayout flBg
    ) {
        if (ivIcon == null || flBg == null) return;
        if (category == null) category = "Khác";

        switch (category) {
            case "Ngân hàng":
                ivIcon.setImageResource(R.drawable.ic_account_balance);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_banking_icon))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_banking_bg))
                );
                break;

            case "Mạng xã hội":
                ivIcon.setImageResource(R.drawable.ic_language);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_social_icon))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_social_bg))
                );
                break;

            case "Công việc":
                ivIcon.setImageResource(R.drawable.ic_work);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_work_icon))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_work_bg))
                );
                break;

            case "Mua sắm":
                ivIcon.setImageResource(R.drawable.ic_shopping_bag);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_shopping_icon))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_shopping_bg))
                );
                break;

            case "Email":
                ivIcon.setImageResource(R.drawable.ic_mail);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_email_icon))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_email_bg))
                );
                break;

            case "Trò chơi":
                ivIcon.setImageResource(R.drawable.ic_gamepad);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_gaming_bg))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_gaming_bg))
                );
                break;

            case "Khác":
            default:
                ivIcon.setImageResource(R.drawable.ic_lock);
                ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_other_icon))
                );

                flBg.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.category_other_bg))
                );
                break;
        }
    }

    // Interface callback khi chọn thành công
    public interface OnWebsiteSelectedListener {
        void onWebsiteSelected(String domain);
    }

    public interface OnAppSelectedListener {
        void onAppSelected(String packageName, String appName);
    }

    /// Hiển thị Dialog nhập Website
    public static void showWebsiteDialog(
            Context context,
            String currentDomain,
            OnWebsiteSelectedListener listener
    ) {
        final TextInputEditText input = new TextInputEditText(context);
        input.setHint("example.com hoặc https://example.com");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
        );

        if (currentDomain != null && !currentDomain.isEmpty()) {
            input.setText(currentDomain);
            if (input.getText() != null) {
                input.setSelection(input.getText().length());
            }
        }

        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Chọn website")
                .setMessage("Nhập website mà tài khoản này dùng để đăng nhập.")
                .setView(input)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {

                String value = input.getText() != null
                        ? input.getText().toString().trim()
                        : "";

                if (value.isEmpty()) {
                    input.setError("Vui lòng nhập domain website");
                    return;
                }
                if (listener != null) {
                    listener.onWebsiteSelected(value);
                }
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    /// Hiển thị Dialog danh sách các Ứng dụng đã cài đặt trên máy
    public static void showAppPicker(
            Context context,
            OnAppSelectedListener listener
    ) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos =
                packageManager.queryIntentActivities(intent, 0);

        Collections.sort(
                resolveInfos,
                Comparator.comparing(
                        info -> info.loadLabel(packageManager).toString(),
                        String.CASE_INSENSITIVE_ORDER
                )
        );

        List<String> appNames = new ArrayList<>();
        List<String> packageNames = new ArrayList<>();
        Set<String> addedPackages = new HashSet<>();

        for (ResolveInfo info : resolveInfos) {
            String pkg = info.activityInfo.packageName;

            if (!addedPackages.add(pkg)) {
                continue;
            }

            String label = info.loadLabel(packageManager).toString();
            appNames.add(label);
            packageNames.add(pkg);
        }

        if (appNames.isEmpty()) {
            Toast.makeText(
                    context,
                    "Không tìm thấy ứng dụng",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        String[] names = appNames.toArray(new String[0]);

        new AlertDialog.Builder(context)
                .setTitle("Chọn ứng dụng")
                .setItems(names, (dialog, which) -> {
                    String pkg = packageNames.get(which);
                    String name = appNames.get(which);
                    if (listener != null) {
                        listener.onAppSelected(pkg, name);
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }
}