package com.example.passwordmanager.ui.credential;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passwordmanager.R;
import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.repository.CredentialRepository;
import com.example.passwordmanager.data.sync.NetworkManager;
import com.example.passwordmanager.data.sync.SyncManager;

import com.example.passwordmanager.databinding.FragmentVaultBinding;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.viewmodel.CredentialViewModel;
import com.example.passwordmanager.viewmodel.CredentialViewModelFactory;
import com.google.firebase.auth.FirebaseAuth;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;


public class VaultFragment extends Fragment {
    private FragmentVaultBinding binding;
    private EditText edtSearchQuery;
    private ChipGroup chipGroupCategories;
    private RecyclerView recyclerView;
    private LinearLayout layoutEmptyState;
    private FloatingActionButton fabAddCredential;

    private CredentialViewModel  viewModel;
    private CredentialAdapter adapter;

    private final Handler searchHandler
            = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;


    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentVaultBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        // ====================================
        // View
        // ====================================

        recyclerView = binding.rvCredentials;
        layoutEmptyState = binding.layoutEmptyState;

        fabAddCredential = binding.fabAddCredential;
        edtSearchQuery = binding.edtSearchQuery;

        chipGroupCategories = binding.chipGroupCategories;

        // ====================================
        // ViewModel
        // ====================================

        CredentialViewModelFactory factory;

        try {
            factory = CredentialViewModelFactory.create(requireContext());
        } catch (IllegalStateException e) {
            requireActivity().finish();
            return;
        }

        viewModel = new ViewModelProvider(
                this,
                factory
        ).get(CredentialViewModel.class);

        // ====================================
        // Adapter
        // ====================================

        adapter = new CredentialAdapter(
                requireContext(),
                new ArrayList<>(),
                credential -> {
                    Intent intent = new Intent(
                            requireContext(),
                            CredentialDetailActivity.class
                    );

                    intent.putExtra("credential_id", credential.getId());
                    startActivity(intent);
                }
        );

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        recyclerView.setAdapter(adapter);

        // ====================================
        // Credentials
        // ====================================

        viewModel.getCredentials()
                .observe(
                        getViewLifecycleOwner(),
                        credentials -> {
                            adapter.updateList(credentials);

                            // Hiển thị view báo rỗng nếu danh sách trống
                            if (credentials == null || credentials.isEmpty()) {
                                recyclerView.setVisibility(View.GONE);
                                layoutEmptyState.setVisibility(View.VISIBLE);
                            } else {
                                recyclerView.setVisibility(View.VISIBLE);
                                layoutEmptyState.setVisibility(View.GONE);
                            }
                        });

        // ====================================
        // Observe error
        // ====================================

        viewModel.getErrorMessage()
                .observe(
                        getViewLifecycleOwner(),
                        errorMessage -> {
                            if (errorMessage != null
                                    && !errorMessage.isEmpty()) {
                                Toast.makeText(
                                        requireContext(),
                                        "Không thể tải dữ liệu: "
                                                + errorMessage,
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                );

        // ====================================
        // Sync
        // ====================================

        // Vòng đời NetworkManager/SyncManager do CredentialViewModel quản lý
        // (dừng tự động trong onCleared()), Fragment chỉ cần yêu cầu bắt đầu.
        viewModel.startNetworkSync(requireContext());


        // ====================================
        // Search
        // ====================================

        edtSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                searchRunnable = () -> viewModel.setSearchQuery(s.toString());
                searchHandler.postDelayed(searchRunnable, 250); // Delay 250ms
            }
        });

        // ====================================
        // Category
        // ====================================

        chipGroupCategories.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                viewModel.setSelectedCategory("Tất cả");
                return;
            }

            int checkedId = checkedIds.get(0);

            if (checkedId == R.id.chipAll) {
                viewModel.setSelectedCategory("Tất cả");

            } else if (checkedId == R.id.chipBanking) {
                viewModel.setSelectedCategory("Ngân hàng");

            } else if (checkedId == R.id.chipSocial) {
                viewModel.setSelectedCategory("Mạng xã hội");

            } else if (checkedId == R.id.chipWork) {
                viewModel.setSelectedCategory("Công việc");

            } else if (checkedId == R.id.chipEmail) {
                viewModel.setSelectedCategory("Email");

            } else if (checkedId == R.id.chipGaming) {
                viewModel.setSelectedCategory("Trò chơi");

            } else if (checkedId == R.id.chipShopping) {
                viewModel.setSelectedCategory("Mua sắm");

            } else if (checkedId == R.id.chipOther) {
                viewModel.setSelectedCategory("Khác");
            }
        });

        // ====================================
        // Add Credential
        // ====================================

        fabAddCredential.setOnClickListener(v -> {
            startActivity(new Intent(
                    requireContext(),
                    AddCredentialActivity.class
                    )
            );
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        adapter = null;
        viewModel = null;
        binding = null;
    }
}

