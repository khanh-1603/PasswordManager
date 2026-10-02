package com.example.passwordmanager;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.passwordmanager.databinding.ActivityMainBinding;
import com.example.passwordmanager.di.AppContainer;
import com.example.passwordmanager.firebase.IAuthRepository;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.VaultKeyStore;
import com.example.passwordmanager.ui.auth.LoginActivity;
import com.example.passwordmanager.ui.credential.GeneratorFragment;
import com.example.passwordmanager.ui.credential.SettingsFragment;
import com.example.passwordmanager.ui.credential.VaultFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseUser;


public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        IAuthRepository authRepository = AppContainer.getInstance(this)
                .getAuthRepository();

        FirebaseUser user = authRepository.getCurrentUser();

        if (user == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        /*
         * Process có thể đã bị Android kill nên CryptoSession mất.
         * Nếu trước đó đã lưu wrapped vault key, yêu cầu local auth
         * để khôi phục session. Nếu không có, bắt buộc Master Password.
         */

        if (!CryptoSession.isActive()) {
            VaultKeyStore vaultKeyStore =
                    AppContainer.getInstance(this)
                            .getVaultKeyStore();

            if (vaultKeyStore.hasVaultKey(user.getUid())) {
                // Đi qua LoginActivity trước (extra AUTO_UNLOCK) để nó tự bắn
                // UnlockActivity lên trên — nhờ vậy màn hình Login (trung tính,
                // không lộ dữ liệu Vault) luôn hiện phía sau lớp trong suốt của
                // Unlock, thay vì lộ MainActivity đang bị finish().

                Intent intent = new Intent(this, LoginActivity.class);

                if (AppContainer.getInstance(this)
                        .getLocalAuthManager()
                        .isEnabled(user.getUid())) {
                    // Có PIN/sinh trắc học -> LoginActivity tự bật UnlockActivity.
                    intent.putExtra(LoginActivity.EXTRA_AUTO_UNLOCK, true);
                } else {
                    // Local Auth tắt -> vào thẳng màn Mở khóa bằng Master Password,
                    // không đi vòng qua UnlockActivity (tránh chồng 2 LoginActivity).
                    intent.putExtra(LoginActivity.EXTRA_MASTER_PASSWORD_UNLOCK, true);
                }

                startActivity(intent);
            } else {
                startActivity(new Intent(this, LoginActivity.class));
            }
            finish();
            return;
        }

        bottomNav = binding.bottomNav;
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            Fragment selectedFragment = null;

            if (id == R.id.nav_vault) {
                selectedFragment = new VaultFragment();

            } else if (id == R.id.nav_generator) {
                selectedFragment = new GeneratorFragment();

            } else if (id == R.id.nav_settings) {
                selectedFragment = new SettingsFragment();
            }

            if (selectedFragment != null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();

                return true;
            }

            return false;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_vault);
        }
    }
}