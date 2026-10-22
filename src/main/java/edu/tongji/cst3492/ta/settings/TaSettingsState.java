package edu.tongji.cst3492.ta.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent application-level settings for the CST3492 TA plugin.
 * Follows Lecture 5 Slide 17 ("The key comes from DEEPSEEK_API_KEY. It never appears in code, slides or git"):
 * the API key is never written in plain text to {@code Cst3492TaSettings.xml}; it is stored in IntelliJ's
 * {@link PasswordSafe} (OS keychain / KeePass) and cached in memory. Without an IntelliJ application
 * (plain unit tests) the key lives only in memory.
 */
@Service(Service.Level.APP)
@State(
        name = "edu.tongji.cst3492.ta.settings.TaSettingsState",
        storages = @Storage("Cst3492TaSettings.xml")
)
public final class TaSettingsState implements PersistentStateComponent<TaSettingsState> {

    private static final String CREDENTIAL_SUBSYSTEM = "CST3492CourseTA";
    private static final String CREDENTIAL_KEY = "LlmApiKey";
    private static final String CREDENTIAL_USER = "cst3492-ta";

    public String apiBaseUrl = "https://api.deepseek.com/v1";
    public String modelName = "deepseek-chat";
    public int topKChunks = 3;
    /** Defaults to offline mode unless an API key is provided through the environment. */
    public boolean offlineDemoMode = !hasEnvironmentApiKey();
    /** Written from background indexing threads and read on the EDT, hence copy-on-write. */
    public List<String> importedDocumentPaths = new CopyOnWriteArrayList<>();

    @Transient
    private volatile String sessionApiKey = "";

    public static TaSettingsState getInstance() {
        if (ApplicationManager.getApplication() == null) {
            return new TaSettingsState();
        }
        return ApplicationManager.getApplication().getService(TaSettingsState.class);
    }

    private static CredentialAttributes createCredentialAttributes() {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(CREDENTIAL_SUBSYSTEM, CREDENTIAL_KEY)
        );
    }

    private static boolean hasEnvironmentApiKey() {
        return !readEnv("DEEPSEEK_API_KEY").isEmpty() || !readEnv("TONGJI_API_KEY").isEmpty();
    }

    private static String readEnv(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    @Transient
    public String getSessionApiKey() {
        String cached = sessionApiKey;
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (ApplicationManager.getApplication() != null) {
            try {
                String stored = PasswordSafe.getInstance().getPassword(createCredentialAttributes());
                if (stored != null && !stored.isBlank()) {
                    sessionApiKey = stored.trim();
                    return sessionApiKey;
                }
            } catch (Exception ignored) {
                // Credential store unavailable (e.g. locked keychain): fall back to in-memory/env key.
            }
        }
        return "";
    }

    @Transient
    public void setSessionApiKey(String key) {
        this.sessionApiKey = key == null ? "" : key.trim();
        if (ApplicationManager.getApplication() != null) {
            try {
                PasswordSafe.getInstance().set(
                        createCredentialAttributes(),
                        new Credentials(CREDENTIAL_USER, this.sessionApiKey)
                );
            } catch (Exception ignored) {
                // Credential store unavailable: key stays in memory for this IDE session only.
            }
        }
    }

    public String resolveApiKey() {
        if (offlineDemoMode) {
            return "";
        }
        String sessionKey = getSessionApiKey();
        if (!sessionKey.isBlank()) {
            return sessionKey;
        }
        String envDeepSeek = readEnv("DEEPSEEK_API_KEY");
        if (!envDeepSeek.isEmpty()) {
            return envDeepSeek;
        }
        return readEnv("TONGJI_API_KEY");
    }

    @Override
    public @Nullable TaSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull TaSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
        // The deserializer may hand back a plain ArrayList; restore the thread-safe list.
        List<String> loaded = importedDocumentPaths;
        importedDocumentPaths = loaded == null
                ? new CopyOnWriteArrayList<>()
                : new CopyOnWriteArrayList<>(loaded);
    }
}
