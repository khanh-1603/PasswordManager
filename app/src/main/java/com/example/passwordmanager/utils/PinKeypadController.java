package com.example.passwordmanager.utils;

import com.example.passwordmanager.databinding.ActivityPinEntryBinding;
import android.view.View;
import android.widget.TextView;

import com.example.passwordmanager.R;

/**
 * Wiring UI thuần cho bàn phím số của activity_pin_entry.xml
 * (bấm số, backspace, tô 4 ô chấm PIN).
 *
 * KHÔNG chứa nghiệp vụ verify/setup PIN — Activity sở hữu layout
 * (Unlock / LocalAuthManagement / LocalAuthIntro) tự quyết định làm
 * gì với PIN đã nhập đủ 4 số thông qua {@link OnPinCompleteListener}.
 *
 * Dùng khi layout activity_pin_entry.xml ĐÃ được setContentView().
 */
public class PinKeypadController {

    public interface OnPinCompleteListener {
        void onPinComplete(String pin);
    }

    private static final int PIN_LENGTH = 4;

    private final StringBuilder enteredPin = new StringBuilder();
    private final View[] pinBoxes;
    private final View[] pinDots;
    private final OnPinCompleteListener listener;

    public PinKeypadController(
            ActivityPinEntryBinding binding,
            OnPinCompleteListener listener)
    {
        this.listener = listener;

        pinBoxes = new View[]{
                binding.pinBox1, binding.pinBox2,
                binding.pinBox3, binding.pinBox4
        };

        pinDots = new View[]{
                binding.pinDot1, binding.pinDot2,
                binding.pinDot3, binding.pinDot4
        };

        TextView[] keys = {
                binding.btnKey0, binding.btnKey1, binding.btnKey2, binding.btnKey3,
                binding.btnKey4, binding.btnKey5, binding.btnKey6, binding.btnKey7,
                binding.btnKey8, binding.btnKey9
        };

        for (int digit = 0; digit <= 9; digit++) {
            TextView key = keys[digit];
            int finalDigit = digit;
            key.setOnClickListener(v -> onDigit(finalDigit));
        }

        binding.btnKeyBackspace.setOnClickListener(v -> onBackspace());

        render();
    }

    private void onDigit(int digit) {
        if (enteredPin.length() >= PIN_LENGTH) {
            return;
        }

        enteredPin.append(digit);
        render();

        if (enteredPin.length() == PIN_LENGTH) {
            listener.onPinComplete(enteredPin.toString());
        }
    }

    private void onBackspace() {
        if (enteredPin.length() == 0) {
            return;
        }

        enteredPin.deleteCharAt(enteredPin.length() - 1);
        render();
    }

    /// Xóa PIN đã nhập, dùng khi verify/setup thất bại và cần nhập lại.
    public void reset() {
        enteredPin.setLength(0);
        render();
    }

    private void render() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            boolean filled = i < enteredPin.length();

            pinBoxes[i].setBackgroundResource(
                    filled
                            ? R.drawable.bg_pin_box_active
                            : R.drawable.bg_pin_box_inactive
            );

            pinDots[i].setVisibility(
                    filled
                            ? View.VISIBLE
                            : View.GONE
            );
        }
    }
}