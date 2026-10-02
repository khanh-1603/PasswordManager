package com.example.passwordmanager.utils;

import android.app.assist.AssistStructure;
import android.text.InputType;
import android.util.Log;
import android.util.Pair;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.util.Locale;

public final class AutofillFieldUtil {
    private static final String TAG="AutofillFieldUtil";
    private AutofillFieldUtil() {}

    /**
     * Duyệt đệ quy toàn bộ cây view để tìm ô username và password.
     * Ưu tiên autofillHints. Nếu là WebView và hint không
     * đủ chuẩn (vd autocomplete="on"), fallback đọc thẳng htmlInfo
     * để lấy type="password" thật của thẻ <input>.
     */
    public static AutofillId[] findAutofillFields(AssistStructure.ViewNode node) {
        if (node == null) {
            return new AutofillId[]{null, null};
        }

//        Log.d(
//                TAG,
//                "Inspect: class=" + node.getClassName()
//                        + " id=" + node.getIdEntry()
//                        + " autofillId=" + node.getAutofillId()
//                        + " hints=" + java.util.Arrays.toString(node.getAutofillHints())
//                        + " html=" + describeHtmlInfo(node)
//        );

        AutofillId usernameId = getUsernameNode(node);
        AutofillId passwordId = getPasswordNode(node);

        // Đệ quy xuống các node con
        for (int i = 0; i<node.getChildCount(); i++) {
            AutofillId[] child = findAutofillFields(node.getChildAt(i));

            if (usernameId == null) {
                usernameId = child[0];
            }

            if (passwordId == null) {
                passwordId = child[1];
            }

            if (usernameId != null && passwordId != null) {
                break;
            }
        }

        return new AutofillId[]{usernameId, passwordId};
    }

    // Nhận diện ô Username (Dùng chung cho cả App Native lẫn Web)
    public static AutofillId getUsernameNode(AssistStructure.ViewNode node) {
        if (node == null) {
            return null;
        }

        //  Kiểm tra theo Autofill Hints (Chuẩn Native Android)
        String[] hints = node.getAutofillHints();
        AutofillId username;

        if (hints != null) {
            for (String hint : hints) {
                if ("username".equals(hint) || "emailAddress".equals(hint)
                        || "email".equals(hint)
                ) {
                    username = node.getAutofillId();
                    Log.d(TAG, "Username field found via hint: " + username);
                    return username;
                }
            }
        }

        // Fallback dựa trên dữ liệu HTML thật — dùng cho WebView
        // khi trang set autocomplete="on" khiến hint không rõ ràng.
        if (isUserNameFieldFromHtml(node)) {
            username = node.getAutofillId();
            Log.d(TAG, "Username field found via htmlInfo: " + username);
            return username;
        }

        if (isNativeUsernameField(node)) {
            username = node.getAutofillId();
            Log.d(TAG, "Username field found via native metadata: " + username);
            return username;
        }

        return null;
    }

    // Nhận diện ô Password (Dùng chung cho cả App Native lẫn Web)
    public static AutofillId getPasswordNode(AssistStructure.ViewNode node) {
        if (node == null) {
            return null;
        }

        // Kiểm tra theo Autofill Hints (Chuẩn Native Android)
        String[] hints = node.getAutofillHints();
        AutofillId password;

        if (hints != null) {
            for (String hint : hints) {
                if ("password".equals(hint) || "current-password".equals(hint)
                        || "new-password".equals(hint)
                ) {
                    password = node.getAutofillId();
                    Log.d(TAG, "Password field found via hint: " + password);
                    return password;
                }
            }
        }

        // // Fallback dựa trên dữ liệu HTML thật — dùng cho WebView
        if (isPasswordFieldFromHtml(node)) {
            password = node.getAutofillId();
            Log.d(TAG, "Password field found via htmlInfo: " + password);
            return password;
        }

        // Fallback cho Native App (InputType password & ID / Hint "pass")
        if (isNativePasswordField(node)) {
            Log.d(TAG, "Password field found via native metadata: " + node.getAutofillId());
            return node.getAutofillId();
        }

        return null;
    }


    private static boolean isNativePasswordField(AssistStructure.ViewNode node) {
        int v = node.getInputType() & InputType.TYPE_MASK_VARIATION;
        if (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        {
            return true;
        }

        String id = node.getIdEntry(), hint = node.getHint() == null
                ?null
                :node.getHint().toString();

        return hasKeyword(id,"pass") || hasKeyword(hint,"pass");
    }

    private static boolean isNativeUsernameField(AssistStructure.ViewNode node) {
        int c = node.getInputType() & InputType.TYPE_MASK_CLASS,
                v = node.getInputType() & InputType.TYPE_MASK_VARIATION;

        if (c == InputType.TYPE_CLASS_TEXT &&
                v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        ) {
            return true;
        }
        String id = node.getIdEntry(), hint = node.getHint() == null
                ? null
                : node.getHint().toString();

        return hasUserKeyword(id) || hasUserKeyword(hint);
    }

    private static boolean hasKeyword(String s,String k){return s!=null && s.toLowerCase(Locale.US).contains(k);}
    private static boolean hasUserKeyword(String s){return hasKeyword(s,"user")||hasKeyword(s,"email")||hasKeyword(s,"login");}

    private static boolean isPasswordFieldFromHtml(AssistStructure.ViewNode node) {
        ViewStructure.HtmlInfo htmlInfo = node.getHtmlInfo();

        if (htmlInfo == null
                ||!"input".equalsIgnoreCase(htmlInfo.getTag())
        ) {
            return false;
        }

        String type = getHtmlAttributes(htmlInfo, "type");
        String autocomplete = getHtmlAttributes(htmlInfo, "autocomplete");

        return "password".equalsIgnoreCase(type)
                || "current-password".equalsIgnoreCase(autocomplete)
                || "new-password".equalsIgnoreCase(autocomplete);
    }

    private static boolean isUserNameFieldFromHtml(AssistStructure.ViewNode node) {
        ViewStructure.HtmlInfo htmlInfo = node.getHtmlInfo();

        if (htmlInfo == null
                || !"input".equalsIgnoreCase(htmlInfo.getTag())
        ) {
            return false;
        }

        String type = getHtmlAttributes(htmlInfo, "type");

        // Nếu type là password -> Chắc chắn KHÔNG PHẢI Username
        if ("password".equalsIgnoreCase(type)) {
            return false;
        }

        String autocomplete = getHtmlAttributes(htmlInfo, "autocomplete");
        String name = getHtmlAttributes(htmlInfo, "name");
        String id = getHtmlAttributes(htmlInfo, "id");

        // Nếu id hoặc name có chứa từ "pass" (vd: m_login_password) -> KHÔNG PHẢI Username
        if ((name != null && name.toLowerCase().contains("pass"))
                || (id != null && id.toLowerCase().contains("pass"))
        ) {
            return false;
        }

        // Khớp theo type hoặc autocomplete
        if ("email".equalsIgnoreCase(type) || "username".equalsIgnoreCase(autocomplete)
                || "email".equalsIgnoreCase(autocomplete)
        ) {
            return true;
        }

        // Khớp theo từ khóa name
        if (name != null) {
            String lowername = name.toLowerCase();
            if (lowername.contains("user") || lowername.contains("email")
                    || lowername.contains("login")
            ) {
                return true;
            }
        }

        // Khớp theo từ khóa id
        if (id != null) {
            String lowerId = id.toLowerCase();
            if (lowerId.contains("user") || lowerId.contains("email")
                    || lowerId.contains("login")
            ) {
                return true;
            }
        }

        return false;
    }

    private static String getHtmlAttributes(ViewStructure.HtmlInfo htmlInfo, String attributeName) {
        if (htmlInfo == null || htmlInfo.getAttributes() == null) {
            return null;
        }

        for (Pair<String, String> attr : htmlInfo.getAttributes()) {
            if (attributeName.equalsIgnoreCase(attr.first)) {
                return attr.second;
            }
        }

        return null;
    }

    public static String findWebDomain(AssistStructure structure) {
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            AssistStructure.WindowNode windowNode = structure.getWindowNodeAt(i);
            String domain = findWebDomain(windowNode.getRootViewNode());

            if (domain != null && !domain.isEmpty()) {
                return domain;
            }
        }
        return null;
    }

    public static String findWebDomain(AssistStructure.ViewNode node) {
        if (node == null) {
            return null;
        }

        String webDomain = node.getWebDomain();

        if (webDomain != null && !webDomain.isEmpty()) {
            return webDomain;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            String domain = findWebDomain(node.getChildAt(i));

            if (domain != null && !domain.isEmpty()) {
                return domain;
            }
        }
        return null;
    }

    public static String normalizeDomain(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {
            String domain = value.trim().toLowerCase(Locale.US);

            //  Cắt bỏ giao thức http://, https://, www.
            if (domain.startsWith("https://")) {
                domain = domain.substring(8);
            }

            if (domain.startsWith("http://")) {
                domain = domain.substring(7);
            }

            if (domain.startsWith("www.")) {
                domain = domain.substring(4);
            }

            //  Cắt bỏ cổng port (:8000) và đường dẫn (/login)
            int colonIndex = domain.indexOf(':');
            if (colonIndex >= 0) domain = domain.substring(0, colonIndex);

            int slashIndex = domain.indexOf('/');
            if (slashIndex >= 0) domain = domain.substring(0, slashIndex);

            // Nếu là địa chỉ IP (vd: 10.0.2.2 hoặc 172.16.64.74) -> Giữ nguyên
            if (domain.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return domain;
            }

            // 4. Trích xuất Domain gốc (Tách theo dấu chấm)
            String[] parts = domain.split("\\.");

            if (parts.length >= 2) {
                // Ví dụ: ["m", "facebook", "com"] -> "facebook.com"
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }

            return domain;

        } catch (Exception e) {
            Log.e(TAG, "Lỗi chuẩn hóa domain: " + value, e);
            return value.trim().toLowerCase(Locale.US);
        }
    }

    /**
     * Trích xuất username và password đã nhập từ AssistStructure.
     * Kết quả: [0] username, [1] password.
     */
    public static String[] extractFilledCredentials(AssistStructure structure) {
        String[] result = new String[]{null, null};

        if (structure == null) {
            return result;
        }

        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            extractFilledValues(
                    structure.getWindowNodeAt(i).getRootViewNode(),
                    result
            );

            if (result[0] != null && result[1] != null) {
                break;
            }
        }

        return result;
    }

    /** Duyệt đệ quy để lấy giá trị text của các field đã nhập. */
    private static void extractFilledValues(
            AssistStructure.ViewNode node,
            String[] result
    ) {
        if (node == null) {
            return;
        }

        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            AutofillValue value = node.getAutofillValue();
            if (value != null && value.isText()) {
                text = value.getTextValue();
            }
        }

        if (text != null && text.length() > 0) {
            // Bất kỳ field nào có text và KHÔNG PHẢI password field đều được tính là username/email field
            if (result[0] == null && getUsernameNode(node) != null) {
                result[0] = text.toString();
                Log.d("AutofillFieldUtil", "Extracted filled username: " + text);
            }

            if (result[1] == null && getPasswordNode(node) != null) {
                result[1] = text.toString();
                Log.d("AutofillFieldUtil", "Password extracted");
            }
        }

        if (result[0] != null && result[1] != null) {
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            extractFilledValues(node.getChildAt(i), result);

            if (result[0] != null && result[1] != null) {
                return;
            }
        }
    }
}