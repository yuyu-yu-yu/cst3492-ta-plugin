package edu.tongji.cst3492.ta.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent application-level settings for the CST3492 TA plugin.
 * Follows Lecture 5 Slide 17 ("The key comes from DEEPSEEK_API_KEY. It never appears in code, slides or git"):
 * {@code apiKey} is marked {@link Transient} so it is never written in plain text to {@code Cst3492TaSettings.xml}.
 */
@Service(Service.Level.APP)
@State(
        name = "edu.tongji.cst3492.ta.settings.TaSettingsState",
        storages = @Storage("Cst3492TaSettings.xml")
)
public final class TaSettingsState implements PersistentStateComponent<TaSettingsState> {

    public String apiBaseUrl = "https://api.deepseek.com/v1";
    public String modelName = "deepseek-chat";
    public int topKChunks = 3;
    public boolean offlineDemoMode = false;
    public List<String> importedDocumentPaths = new ArrayList<>();

    @Transient
    private volatile String sessionApiKey = "";

    public static TaSettingsState getInstance() {
        if (ApplicationManager.getApplication() == null) {
            return new TaSettingsState();
        }
        return ApplicationManager.getApplication().getService(TaSettingsState.class);
    }

    @Transient
    public String getSessionApiKey() {
        return sessionApiKey == null ? "" : sessionApiKey;
    }

    @Transient
    public void setSessionApiKey(String key) {
        this.sessionApiKey = key == null ? "" : key.trim();
    }

    public String resolveApiKey() {
        if (offlineDemoMode) {
            return "";
        }
        if (sessionApiKey != null && !sessionApiKey.isBlank()) {
            return sessionApiKey.trim();
        }
        String envDeepSeek = System.getenv("DEEPSEEK_API_KEY");
        if (envDeepSeek != null && !envDeepSeek.isBlank()) {
            return envDeepSeek.trim();
        }
        String envTongji = System.getenv("TONGJI_API_KEY");
        if (envTongji != null && !envTongji.isBlank()) {
            return envTongji.trim();
        }
        return "";
    }

    @Override
    public @Nullable TaSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull TaSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
