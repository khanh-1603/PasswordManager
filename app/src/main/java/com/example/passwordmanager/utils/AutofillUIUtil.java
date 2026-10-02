package com.example.passwordmanager.utils;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.view.View;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import com.example.passwordmanager.R;
import com.example.passwordmanager.ui.security.UnlockActivity;

public final class AutofillUIUtil {

    private AutofillUIUtil() {
        // Utility class không khởi tạo object
    }

    /**
     * Tạo RemoteViews Presentation cho ô Autofill Dataset.
     */
    public static RemoteViews createDatasetPresentation(
            Context context,
            String username,
            boolean isLastUsed
    ) {
        RemoteViews presentation = new RemoteViews(
                context.getPackageName(),
                R.layout.autofill_dataset
        );

        presentation.setTextViewText(R.id.tvUsername, username);

        if (isLastUsed) {
            presentation.setViewVisibility(R.id.layoutHeader, View.VISIBLE);
            presentation.setViewVisibility(R.id.viewAccentIndicator, View.VISIBLE);
            presentation.setViewVisibility(R.id.viewBottomDivider, View.VISIBLE);
            presentation.setInt(
                    R.id.layoutItemContainer,
                    "setBackgroundResource",
                    R.drawable.bg_autofill_recent_item
            );
        } else {
            presentation.setViewVisibility(R.id.layoutHeader, View.GONE);
            presentation.setViewVisibility(R.id.viewAccentIndicator, View.GONE);
            presentation.setViewVisibility(R.id.viewBottomDivider, View.GONE);
            presentation.setInt(
                    R.id.layoutItemContainer,
                    "setBackgroundResource",
                    R.drawable.bg_autofill_normal_item
            );
        }

        return presentation;
    }

    /**
     * Tạo IntentSender dùng khi người dùng chọn một credential cụ thể trong danh sách Autofill.
     */
//    public static IntentSender createCredentialSelectionAuthenticationIntent(
//            Context context,
//            String credentialId,
//            String contextKey,
//            AutofillId usernameId,
//            AutofillId passwordId
//    ) {
//        Intent intent = new Intent(context, UnlockActivity.class);
//
//        intent.putExtra("source", "SELECT_CREDENTIAL");
//        intent.putExtra("credential_id", credentialId);
//        intent.putExtra("context_key", contextKey);
//
//        if (usernameId != null) {
//            intent.putExtra("username_id", usernameId);
//        }
//
//        if (passwordId != null) {
//            intent.putExtra("password_id", passwordId);
//        }
//
//        int requestCode = credentialId != null
//                ? credentialId.hashCode()
//                : System.identityHashCode(intent);
//
//        PendingIntent pendingIntent = PendingIntent.getActivity(
//                context,
//                requestCode,
//                intent,
//                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
//        );
//
//        return pendingIntent.getIntentSender();
//    }
}