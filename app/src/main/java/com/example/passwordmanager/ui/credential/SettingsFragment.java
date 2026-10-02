package com.example.passwordmanager.ui.credential;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutofillManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.passwordmanager.R;
import com.example.passwordmanager.databinding.FragmentSettingsBinding;
import com.example.passwordmanager.firebase.AuthRepository;
import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.ui.security.LocalAuthManagementActivity;
import com.example.passwordmanager.viewmodel.AuthViewModel;
import com.example.passwordmanager.viewmodel.AuthViewModelFactory;
import com.example.passwordmanager.viewmodel.Resource;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseUser;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding binding;
    private TextView tvUserName;
    private TextView tvUserEmail;
    private ImageView btnEditProfile;

    private LinearLayout rowLocalAuth;
    private MaterialButton btnLogout;
    private SwitchMaterial switchAutofill;

    private AuthViewModel authViewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvUserName = binding.tvUserName;
        tvUserEmail = binding.tvUserEmail;
        btnEditProfile = binding.btnEditProfile;
        rowLocalAuth = binding.rowLocalAuth;
        btnLogout = binding.btnLogout;
        switchAutofill = binding.switchAutofill;

        authViewModel = new ViewModelProvider(
                this,
                new AuthViewModelFactory(requireActivity().getApplication())
        ).get(AuthViewModel.class);
        // Setup User Info & Observe
        setupUserInfo();
        observeViewModel();

        // Handlers
        View.OnClickListener editProfileClickListener = v -> {
            startActivity(new Intent(
                    requireContext(),
                    ProfileManagementActivity.class)
            );

        };
        if (btnEditProfile != null) {
            btnEditProfile.setOnClickListener(editProfileClickListener);
        }

        rowLocalAuth.setOnClickListener(v -> {
            startActivity(new Intent(
                    requireContext(),
                    LocalAuthManagementActivity.class)
            );
        });

        btnLogout.setOnClickListener(v -> {
            authViewModel.logout();
            startActivity(new Intent(
                    requireContext(),
                    LoginActivity.class)
            );
            requireActivity().finish();
        });

        if (switchAutofill != null) {
            AutofillManager autofillManager = requireContext()
                    .getSystemService(AutofillManager.class);

            boolean isEnabled = autofillManager != null
                    && autofillManager.hasEnabledAutofillServices();

            switchAutofill.setChecked(isEnabled);

            // Xử lý sự kiện bật/tắt Switch
            switchAutofill.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> {
                        if (isChecked) {
                            Toast.makeText(
                                    requireContext(),
                                    "Đã bật Autofill Framework",
                                    Toast.LENGTH_SHORT
                            ).show();

                        } else {
                            Toast.makeText(
                                    requireContext(),
                                    "Đã tắt Autofill Framework",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        // Mở màn hình cài đặt Autofill của hệ thống để người dùng cấu hình
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
                            intent.setData(Uri.parse(
                                    "package:" + requireContext().getPackageName())
                            );

                            startActivity(intent);

                        } catch (Exception e) {
                            // Fallback nếu thiết bị không hỗ trợ intent trực tiếp
                            Intent fallbackIntent = new Intent(Settings.ACTION_SETTINGS);
                            startActivity(fallbackIntent);
                        }
                    });
        }
    }

    private void setupUserInfo() {
        FirebaseUser user = authViewModel.getUser();

        if (user != null) {
            String name = user.getDisplayName();

            if (name != null && !name.trim().isEmpty()) {
                tvUserName.setText(name);

            } else {
                tvUserName.setText("Chưa đặt tên");
            }

            if (user.getEmail() != null) {
                tvUserEmail.setText(user.getEmail());
            }
        }
    }

    private void observeViewModel() {
        authViewModel.getUpdateProfileResult()
                .observe(getViewLifecycleOwner(), resource -> {
                    if (resource == null) return;

                    if (resource.getStatus() == Resource.Status.LOADING) {
                        Toast.makeText(
                                requireContext(),
                                "Đang cập nhật...",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else if (resource.getStatus() == Resource.Status.SUCCESS) {
                        Toast.makeText(
                                requireContext(),
                                "Đã cập nhật thành công!",
                                Toast.LENGTH_SHORT
                        ).show();

                        FirebaseUser user = resource.getData();

                        if (user != null && user.getDisplayName() != null) {
                            tvUserName.setText(user.getDisplayName());
                        } else {
                            setupUserInfo();
                        }

                        authViewModel.clearUpdateProfileResult();

                    } else if (resource.getStatus() == Resource.Status.ERROR) {
                        String error = resource.getMessage() != null
                                ? resource.getMessage()
                                : "Đã có lỗi xảy ra";

                        Toast.makeText(
                                requireContext(),
                                error,
                                Toast.LENGTH_LONG
                        ).show();

                        authViewModel.clearUpdateProfileResult();
                    }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Tự động làm mới lại thông tin user (tên, email) mỗi khi quay lại tab Cài đặt
        setupUserInfo();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        }
}
