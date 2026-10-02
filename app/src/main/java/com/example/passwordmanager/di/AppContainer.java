package com.example.passwordmanager.di;

import android.content.Context;

import com.example.passwordmanager.data.local.AppDatabase;
import com.example.passwordmanager.data.local.CredentialDao;
import com.example.passwordmanager.data.repository.CredentialRepository;
import com.example.passwordmanager.data.repository.ICredentialRepository;
import com.example.passwordmanager.firebase.AuthRepository;
import com.example.passwordmanager.firebase.IAuthRepository;
import com.example.passwordmanager.firebase.ISecurityRepository;
import com.example.passwordmanager.firebase.SecurityRepository;
import com.example.passwordmanager.security.AuthAttemptManager;
import com.example.passwordmanager.security.CryptoManager;
import com.example.passwordmanager.security.CryptoSession;
import com.example.passwordmanager.security.LocalAuthManager;
import com.example.passwordmanager.security.VaultKeyStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nơi khởi tạo và giữ (cache) các dependency dùng chung của toàn app:
 * Repository và các Manager liên quan tới bảo mật cục bộ.
 *
 * Đây là một Service Locator thủ công (không dùng Hilt/Koin) — đủ cho quy mô
 * đồ án, nhưng tách được việc "khởi tạo dependency" ra khỏi View. Thay vì mỗi
 * Activity/Fragment tự "new" Repository/Manager, chúng chỉ cần gọi
 * AppContainer.getInstance(context) để lấy về instance dùng chung.
 */
public class AppContainer {
    private static volatile AppContainer instance;
    private final Context appContext;

    private final IAuthRepository authRepository;
    private final ISecurityRepository securityRepository;
    private final VaultKeyStore vaultKeyStore;
    private final AuthAttemptManager authAttemptManager;
    private final LocalAuthManager localAuthManager;

    // Executor dùng chung cho MỌI tác vụ nền của app (crypto, DB, sync...)
    // thay vì để từng nơi tự "Executors.newSingleThreadExecutor()" cho một
    // tác vụ rồi bỏ luôn không shutdown() — mỗi lần như vậy rò rỉ vĩnh viễn
    // 1 thread vì single-thread executor mặc định không tự timeout.
    // Cached thread pool: thread rảnh quá 60s tự bị thu hồi.
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();

    // CredentialRepository phụ thuộc uid + CryptoSession, chỉ tồn tại SAU khi
    // đăng nhập/unlock thành công, nên không thể khởi tạo sẵn như các
    // dependency ở trên — phải tạo theo phiên và cache lại.
    //
    // Cache được khóa theo CẢ uid LẪN instance CryptoManager hiện tại của
    // CryptoSession (không chỉ uid): sau logout/login lại hoặc đổi Master
    // Password, CryptoSession.start(...) tạo ra một CryptoManager MỚI dù uid
    // có thể giữ nguyên — nếu chỉ so sánh uid, Repository cache sẽ bị trả về
    // với khóa mã hóa CŨ, gây giải mã sai/crash.
    private ICredentialRepository credentialRepository;
    private String credentialRepositoryUid;
    private CryptoManager credentialRepositoryCryptoManager;

    private AppContainer(Context context) {
        this.appContext = context.getApplicationContext();
        this.authRepository = new AuthRepository();
        this.securityRepository = new SecurityRepository();
        this.vaultKeyStore = new VaultKeyStore(appContext);
        this.authAttemptManager = new AuthAttemptManager(appContext);
        this.localAuthManager = new LocalAuthManager(appContext);
    }

    public static AppContainer getInstance(Context context) {
        if (instance == null) {
            synchronized (AppContainer.class) {
                if (instance == null) {
                    instance = new AppContainer(context);
                }
            }
        }

        return instance;
    }

    public IAuthRepository getAuthRepository() {
        return authRepository;
    }

    public ISecurityRepository getSecurityRepository() {
        return securityRepository;
    }

    public VaultKeyStore getVaultKeyStore() {
        return vaultKeyStore;
    }

    public AuthAttemptManager getAuthAttemptManager() {
        return authAttemptManager;
    }

    public LocalAuthManager getLocalAuthManager() {
        return localAuthManager;
    }

    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    public CredentialDao getCredentialDao() {
        return AppDatabase.getInstance(appContext).credentialDao();
    }

    /**
     * Trả về CredentialRepository cho phiên đăng nhập hiện tại.
     * Yêu cầu CryptoSession đã được khởi tạo (sau login/unlock thành công),
     * nếu không sẽ ném IllegalStateException để nơi gọi xử lý (vd: finish()).
     */
    public synchronized ICredentialRepository getCredentialRepository(String uid) {
        if (uid == null || uid.isEmpty()) {
            throw new IllegalStateException("Người dùng chưa đăng nhập");
        }

        if (!CryptoSession.isActive()) {
            throw new IllegalStateException("CryptoSession chưa được khởi tạo");
        }

        CryptoManager currentCryptoManager = CryptoSession.get();

        boolean staleCache = credentialRepository == null
                || !uid.equals(credentialRepositoryUid)
                || credentialRepositoryCryptoManager != currentCryptoManager;

        if (staleCache) {
            credentialRepository = new CredentialRepository(
                    appContext,
                    uid,
                    currentCryptoManager,
                    backgroundExecutor
            );
            credentialRepositoryUid = uid;
            credentialRepositoryCryptoManager = currentCryptoManager;
        }

        return credentialRepository;
    }

    /**
     * Gọi khi logout / đổi tài khoản / xóa tài khoản để không giữ lại
     * CredentialRepository (và CryptoManager bên trong nó) của phiên cũ.
     * (Việc đổi Master Password đã tự động được xử lý ở trên vì tạo
     * CryptoManager mới, không cần gọi hàm này.)
     */
    public synchronized void clearCredentialRepository() {
        credentialRepository = null;
        credentialRepositoryUid = null;
        credentialRepositoryCryptoManager = null;
    }
}