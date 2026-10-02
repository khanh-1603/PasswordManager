package com.example.passwordmanager.ui.credential;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.databinding.FragmentGeneratorBinding;
import com.example.passwordmanager.utils.CredentialUIUtils;
import com.example.passwordmanager.utils.PasswordGenerator;
import com.example.passwordmanager.viewmodel.CredentialViewModel;
import com.example.passwordmanager.viewmodel.CredentialViewModelFactory;
import com.google.android.material.slider.Slider;

public class GeneratorFragment extends Fragment {
    private FragmentGeneratorBinding binding;

    private TextView tvGeneratedPassword;
    private TextView tvLengthBadge;

    private Slider sliderLength;

    private CheckBox cbUppercase;
    private CheckBox cbNumbers;
    private CheckBox cbSymbols;
    private CredentialViewModel credentialViewModel;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentGeneratorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        CredentialViewModelFactory factory;

        try {
            factory = CredentialViewModelFactory.create(requireContext());
        } catch (IllegalStateException e) {
            requireActivity().finish();
            return;
        }

        credentialViewModel = new ViewModelProvider(this, factory)
                .get(CredentialViewModel.class);

        credentialViewModel.getPasswordStrength()
                .observe(getViewLifecycleOwner(), score -> {
                    CredentialUIUtils.updateStrengthBarsUI(
                            requireContext(),
                            score,
                            binding.strengthBar1,
                            binding.strengthBar2,
                            binding.strengthBar3,
                            binding.strengthBar4,
                            binding.tvStrengthText
                    );
                });

        tvGeneratedPassword = binding.tvGeneratedPassword;
        tvLengthBadge = binding.tvLengthBadge;

        sliderLength = binding.sliderLength;

        cbUppercase = binding.cbUppercase;
        cbNumbers = binding.cbNumbers;
        cbSymbols = binding.cbSymbols;

        cbUppercase.setChecked(true);
        cbNumbers.setChecked(true);
        cbSymbols.setChecked(true);

        View btnCopyGenerated = binding.btnCopyGenerated;
        View btnRefreshPassword = binding.btnRefreshPassword;
        View btnGenerateAction = binding.btnGenerateAction;

        updateLengthBadge();

        sliderLength.addOnChangeListener(
                (slider, value, fromUser) -> updateLengthBadge()
        );

        btnGenerateAction.setOnClickListener(v -> generatePassword());

        btnRefreshPassword.setOnClickListener(v -> generatePassword());

        btnCopyGenerated.setOnClickListener(v -> copyPassword());

        generatePassword();
    }

    private void updateLengthBadge() {
        int length = Math.round(sliderLength.getValue());
        tvLengthBadge.setText(String.valueOf(length));
    }

    private void generatePassword() {
        int length = Math.round(sliderLength.getValue());

        boolean uppercase = cbUppercase.isChecked();
        boolean numbers = cbNumbers.isChecked();
        boolean symbols = cbSymbols.isChecked();

        String password = PasswordGenerator.generatePassword(
                length,
                uppercase,
                numbers,
                symbols
        );

        tvGeneratedPassword.setText(password);
        if (credentialViewModel != null) {
            credentialViewModel.evaluatePasswordStrength(password);
        }
    }

    private void copyPassword() {
        String password = tvGeneratedPassword.getText().toString();

        if (password.isEmpty()) {
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);

        ClipData clip = ClipData.newPlainText(
                "Generated password",
                password
        );

        clipboard.setPrimaryClip(clip);

        Toast.makeText(
                requireContext(),
                "Đã sao chép mật khẩu",
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
