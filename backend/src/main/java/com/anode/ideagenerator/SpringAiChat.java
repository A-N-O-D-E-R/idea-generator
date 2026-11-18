package com.anode.ideagenerator;

import static com.anode.ideagenerator.Providers.extractJson;

import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.Chat;
import com.anode.ideagenerator.Providers.ChatRequest;
import com.anode.ideagenerator.Providers.ChatResult;
import com.anode.ideagenerator.Providers.ParsedModel;
import com.anode.ideagenerator.Providers.Provider;
import com.anode.ideagenerator.Providers.ProviderError;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Every LLM call of the app, on Spring AI ({@link ChatModels}): it routes a "provider:model" string to the OpenAI-compatible, Anthropic or Ollama
 * model of the tenant's settings, asks for the JSON Schema as structured output, and returns the JSON answer, its cost and any citations.
 * What the providers disagree on is adapted and retried without spending an attempt, as before: a model that insists on reasoning, refuses
 * {@code temperature}, wants {@code max_completion_tokens} or has no structured output.
 */
final class SpringAiChat implements Chat {
    private static final Pattern PROBLEM_FIELDS = Pattern.compile("reasoning|temperature|max_tokens|max_completion_tokens|response_format|json_schema|structured",
            Pattern.CASE_INSENSITIVE);

    private final AppSettings settings;

    SpringAiChat(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public ChatResult chat(ChatRequest r) {
        ParsedModel pm = Providers.parseModel(r.model());
        if (pm.model().isEmpty()) throw new ProviderError("bad_model", "No model given in \"" + r.model() + "\"");
        return switch (pm.provider().type) {
            case "anthropic" -> anthropic(pm.model(), r);
            case "ollama" -> ollama(pm.model(), r);
            default -> openAiCompatible(pm.provider(), pm.model(), r);
        };
    }

    private static boolean has(String text, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** What Node reports as e.cause.code for a failed connection. */
    private static String causeCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof ConnectException) return "ECONNREFUSED";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** The SDKs wrap a connection error as "Request failed": the root cause says what happened. */
    private static String withCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return root != e && root.getMessage() != null && !message.contains(root.getMessage()) ? message + ": " + root.getMessage() : message;
    }

    private static String promptJsonRule(JsonNode schema) {
        return "\n\nRespond with ONLY a JSON object that matches this JSON Schema. No prose, no code fences.\n" + Js.stringify(schema);
    }

    // ---------------------------------------------------------------- OpenAI-compatible

    private ChatResult openAiCompatible(Provider p, String model, ChatRequest r) {
        String base = Providers.baseFor(settings, p);
        if (base.isEmpty()) throw new ProviderError("no_base_url", p.label + ": set a base URL in Settings");
        String key = Providers.keyFor(settings, p);
        if (p.needsKey && key == null) throw new ProviderError("no_key", p.label + ": no API key (Settings → Providers)");
        if (r.plugins() != null && !p.search) {
            throw new ProviderError("no_search", p.label + " cannot search the web; use an OpenRouter model such as openrouter:x-ai/grok-4.3");
        }
        ChatModel chat = ChatModels.openAiCompatible(settings, p, base, key);

        Double temperature = r.temperature();
        boolean reasoningOff = p == Provider.OPENROUTER;           // thinking counts against max_tokens, so it is switched off...
        int maxTokens = r.maxTokens() != null ? r.maxTokens() : 1200;
        boolean completionTokens = false;                           // ...max_completion_tokens instead of max_tokens, for models that want it
        boolean schemaMode = p != Provider.MLX;
        String lastError = "";
        for (int attempt = 0, adjustments = 0; attempt < 3; ) {
            var options = OpenAiChatOptions.builder().model(model).maxRetries(0);
            if (temperature != null) options.temperature(temperature);
            if (completionTokens) options.maxCompletionTokens(maxTokens);
            else options.maxTokens(maxTokens);
            Map<String, Object> extra = new LinkedHashMap<>();
            if (p == Provider.OPENROUTER) {
                if (reasoningOff) extra.put("reasoning", Map.of("enabled", false));
                extra.put("usage", Map.of("include", true));
                if (r.plugins() != null) extra.put("plugins", Js.M.convertValue(r.plugins(), List.class));
            }
            if (!extra.isEmpty()) options.extraBody(extra);
            if (schemaMode) options.outputSchema(Js.stringify(r.schema()));
            Prompt prompt = new Prompt(List.of(new SystemMessage(r.system() + (schemaMode ? "" : promptJsonRule(r.schema()))), new UserMessage(r.user())), options.build());

            ChatResponse res;
            try {
                res = chat.call(prompt);
            } catch (OpenAIServiceException e) {
                int status = e.statusCode();
                String text = e.getMessage() == null ? "" : e.getMessage();
                // Providers disagree on parameters; adapt and retry without spending an attempt.
                if (status == 400 && adjustments < 4) {
                    adjustments++;
                    if (reasoningOff && p == Provider.OPENROUTER && has(text, "reasoning")) {
                        reasoningOff = false; // this model insists on reasoning; thinking counts against max_tokens
                        maxTokens = Math.max(maxTokens * 4, 4000);
                        continue;
                    }
                    if (temperature != null && has(text, "temperature")) {
                        temperature = null;
                        continue;
                    }
                    if (!completionTokens && has(text, "max_tokens") && has(text, "max_completion_tokens")) {
                        completionTokens = true;
                        continue;
                    }
                    if (schemaMode && has(text, "response_format|json_schema|structured")) {
                        schemaMode = false;
                        continue;
                    }
                }
                attempt++;
                lastError = p.label + " " + status + ": " + Js.cut(text, 300);
                if (status == 401 || status == 403) throw new ProviderError("bad_key", lastError);
                if (status == 429 || status >= 500) {
                    sleep(1000L * attempt);
                    continue;
                }
                throw new ProviderError("provider_error", lastError);
            } catch (ProviderError e) {
                throw e; // a shared server's address check
            } catch (RuntimeException e) {
                throw new RuntimeException(withCause(e), e);
            }
            attempt++;
            String content = res.getResult() == null ? null : res.getResult().getOutput().getText();
            if (content == null || content.isEmpty()) {
                lastError = "Empty answer from " + model;
                continue;
            }
            try {
                return new ChatResult(extractJson(content), cost(res), citations(res));
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                schemaMode = false;
            }
        }
        throw new ProviderError("provider_error", lastError.isEmpty() ? p.label + " failed" : lastError);
    }

    /** OpenRouter reports the price in {@code usage.cost}, which the SDK keeps among the usage's additional properties. */
    private static double cost(ChatResponse res) {
        if (res.getMetadata() != null && res.getMetadata().getUsage() != null && res.getMetadata().getUsage().getNativeUsage() instanceof CompletionUsage usage) {
            var cost = usage._additionalProperties().get("cost");
            if (cost != null) {
                Object number = cost.asNumber().orElse(null);
                double v = number instanceof Number n ? n.doubleValue() : Double.NaN;
                if (Double.isFinite(v)) return v;
            }
        }
        return 0;
    }

    private static List<String> citations(ChatResponse res) {
        List<String> urls = new ArrayList<>();
        if (res.getResult() != null && res.getResult().getOutput().getMetadata().get("annotations") instanceof List<?> annotations) {
            for (Object a : annotations) {
                if (a instanceof ChatCompletionMessage.Annotation annotation) {
                    String url = annotation.urlCitation().url();
                    if (url != null && !url.isEmpty()) urls.add(url);
                }
            }
        }
        return urls;
    }

    // ---------------------------------------------------------------- Ollama

    private ChatResult ollama(String model, ChatRequest r) {
        if (r.plugins() != null) throw new ProviderError("no_search", "Ollama cannot search the web; use an OpenRouter model for the X scout");
        String base = Providers.baseFor(settings, Provider.OLLAMA);
        ChatModel chat = ChatModels.ollama(settings, base);
        // think:false stops reasoning models (qwen3) spending every token on thinking; format takes the JSON Schema directly.
        boolean thinkOff = true;
        for (int attempt = 0; attempt < 3; attempt++) {
            var options = OllamaChatOptions.builder().model(model).temperature(r.temperature())
                    .numPredict(r.maxTokens() != null ? r.maxTokens() : 1200).format(Js.M.convertValue(r.schema(), Map.class));
            if (thinkOff) options.disableThinking();
            Prompt prompt = new Prompt(List.of(new SystemMessage(r.system()), new UserMessage(r.user())), options.build());
            ChatResponse res;
            try {
                res = chat.call(prompt);
            } catch (ProviderError e) {
                throw e; // a shared server's address check
            } catch (RuntimeException e) {
                // Spring AI reports an HTTP error as "<status> - <body>"
                java.util.regex.Matcher m = Pattern.compile("^\\s*(\\d{3}) - ([\\s\\S]*)$").matcher(e.getMessage() == null ? "" : e.getMessage());
                if (e instanceof RestClientResponseException re) {
                    m = Pattern.compile("^(\\d{3})([\\s\\S]*)$").matcher(re.getStatusCode().value() + re.getResponseBodyAsString());
                }
                if (m.matches()) {
                    String text = m.group(2);
                    if (m.group(1).equals("400") && thinkOff && has(text, "think")) {
                        thinkOff = false;
                        continue;
                    }
                    throw new ProviderError("provider_error", "Ollama " + m.group(1) + ": " + Js.cut(text.strip(), 300));
                }
                throw new ProviderError("unreachable", "Ollama at " + base + " is not reachable (" + causeCode(e) + "). Is it running?");
            }
            try {
                return new ChatResult(extractJson(res.getResult().getOutput().getText()), 0);
            } catch (RuntimeException e) {
                if (attempt == 2) throw new ProviderError("provider_error", "Ollama " + model + ": " + e.getMessage());
            }
        }
        throw new ProviderError("provider_error", "Ollama " + model + " failed");
    }

    // ---------------------------------------------------------------- Anthropic

    private ChatResult anthropic(String model, ChatRequest r) {
        if (r.plugins() != null) throw new ProviderError("no_search", "The X scout needs OpenRouter (Grok with X search)");
        String key = Providers.keyFor(settings, Provider.ANTHROPIC);
        if (key == null) {
            // The SDK refuses to send a request without credentials.
            throw new RuntimeException("Could not resolve authentication method. Expected either apiKey or authToken to be set.");
        }
        String envBase = Providers.env.apply("ANTHROPIC_BASE_URL");
        ChatModel chat = ChatModels.anthropic(envBase == null || envBase.isEmpty() ? null : envBase.replaceAll("/+$", ""), key);
        // Structured output as output_config.format; no temperature (current models reject non-default sampling); low effort: short, single-shot answers.
        boolean effort = true;
        for (int attempt = 0; attempt < 2; attempt++) {
            var options = AnthropicChatOptions.builder().model(model).maxTokens(Math.max(r.maxTokens() != null ? r.maxTokens() : 1200, 4000))
                    .outputSchema(Js.stringify(r.schema()));
            if (effort) options.effort(com.anthropic.models.messages.OutputConfig.Effort.LOW);
            Prompt prompt = new Prompt(List.of(new SystemMessage(r.system()), new UserMessage(r.user())), options.build());
            ChatResponse res;
            try {
                res = chat.call(prompt);
            } catch (com.anthropic.errors.AnthropicServiceException e) {
                String message = e.statusCode() + " " + e.getMessage();
                if (e.statusCode() == 400 && effort && has(message, "effort")) {
                    effort = false; // older models (e.g. Haiku 4.5) do not take effort
                    continue;
                }
                if (e.statusCode() == 401) throw new ProviderError("bad_key", "Anthropic: " + message);
                throw new ProviderError("provider_error", "Anthropic " + e.statusCode() + ": " + message);
            } catch (com.anthropic.errors.AnthropicIoException e) {
                throw new ProviderError("provider_error", "Anthropic undefined: Connection error.");
            }
            if ("refusal".equals(res.getResult().getMetadata().getFinishReason())) throw new ProviderError("refused", model + " declined to answer");
            double[] price = Providers.ANTHROPIC_PRICES.getOrDefault(model, new double[]{0, 0});
            var usage = res.getMetadata().getUsage();
            double cost = ((usage.getPromptTokens() == null ? 0 : usage.getPromptTokens()) * price[0]
                    + (usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens()) * price[1]) / 1e6;
            return new ChatResult(extractJson(res.getResult().getOutput().getText()), cost);
        }
        throw new ProviderError("provider_error", "Anthropic " + model + " failed");
    }
}
