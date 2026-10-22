package edu.tongji.cst3492.ta.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent application-level settings for the CST3492 TA plugin.
 * Supports Tongji AI Services Platform (https://agi.tongji.edu.cn), DeepSeek API (https://api.deepseek.com/v1),
 * environment variable fallback (DEEPSEEK_API_KEY / TONGJI_API_KEY), and offline demo mode.
 */
@Service(Service.Level.APP)
@State(
        name = "edu.tongji.cst3492.ta.settings.TaSettingsState",
        storages = @Storage("Cst3492TaSettings.xml")
)
public final class TaSettingsState implements PersistentStateComponent<TaSettingsState> {

    public String apiBaseUrl = "https://api.deepseek.com/v1";
    public String apiKey = "";
    public String modelName = "deepseek-chat";
    public int topKChunks = 3;
    public boolean fallbackToOfflineTa = true;

    public static TaSettingsState getInstance() {
        if (ApplicationManager.getApplication() == null) {
            return new TaSettingsState();
        }
        return ApplicationManager.getApplication().getService(TaSettingsState.class);
    }

    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
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
