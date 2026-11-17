package com.anode.ideagenerator.service;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.Js;
import com.anode.ideagenerator.Providers;
import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.Provider;
import com.anode.ideagenerator.repository.SpendRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The LLM providers as the tenant sees them: where each key comes from, what was spent, which models exist. */
@Service
@RequiredArgsConstructor
public class ProviderService {
    private final SettingsService settings;
    private final SpendRepository spend;

    public Map<String, Object> status(String t) {
        AppSettings s = settings.get(t);
        List<Map<String, Object>> providers = Arrays.stream(Provider.values()).map(p -> Js.obj(
                "id", p.id(), "label", p.label, "local", p.local, "needsKey", p.needsKey, "search", p.search, "note", p.note,
                "keySource", Providers.keySource(s, p), "baseUrl", s.baseUrls().get(p.id()))).toList();
        return Js.obj("providers", providers, "spentToday", spend.today(t, null), "spentTotal", spend.total(t));
    }

    public Map<String, Object> models(String t, String id) {
        Provider p = Provider.of(id);
        if (p == null) throw new AppError(404, "not_found", "Unknown provider");
        try {
            return Js.obj("models", Providers.listModels(settings.get(t), p));
        } catch (Providers.ProviderError e) {
            throw new AppError(502, e.code, AppError.messageOf(e));
        } catch (RuntimeException e) {
            throw new AppError(502, "provider_error", AppError.messageOf(e));
        }
    }
}
