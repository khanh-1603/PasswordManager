package com.example.passwordmanager.autofill;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillEventHistory;
import android.service.autofill.FillRequest;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Autofill Service chính được Android đăng ký.
 * Chỉ điều phối Fill và Save sang helper tương ứng.
 */
public class PasswordAutofillService extends AutofillService {

    @Override
    public void onFillRequest(
            @NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull FillCallback callback
    ) {
        // Đọc lựa chọn của người dùng từ phiên Autofill trước.
        updateLastUsedFromHistory();

        FillRequestHelper.handle(
                this, request, cancellationSignal, callback
        );
    }

    @Override
    public void onSaveRequest(
            @NonNull SaveRequest request,
            @NonNull SaveCallback callback
    ) {
        SaveRequestHelper.handle(this, request, callback);
    }

    private void updateLastUsedFromHistory() {

        FillEventHistory history = getFillEventHistory();

        Log.d("PasswordAutofill", "===== FILL EVENT HISTORY =====");

        if (history == null) {
            Log.d("PasswordAutofill", "history = NULL");
            return;
        }

        if (history.getEvents() == null) {
            Log.d("PasswordAutofill", "events = NULL");
            return;
        }

        Log.d(
                "PasswordAutofill",
                "event count = " + history.getEvents().size()
        );

        for (FillEventHistory.Event event : history.getEvents()) {
            Log.d("PasswordAutofill", "EVENT TYPE = " + event.getType() + ", DATASET ID = " + event.getDatasetId());

            Bundle clientState = event.getClientState();
            String contextKey = clientState != null
                    ? clientState.getString("autofill_context_key")
                    : null;

            if (contextKey != null) {
                Log.d("PasswordAutofill", "CLIENT STATE = " + contextKey);
            }

            if (event.getType() == FillEventHistory.Event.TYPE_DATASET_SELECTED
                    && event.getDatasetId() != null
                    && contextKey != null
            ) {
                AutofillAuthSession.setLastUsed(this, contextKey, event.getDatasetId());
            }
        }
    }
}
