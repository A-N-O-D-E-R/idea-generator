package com.anode.ideagenerator;

import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.Provider;
import com.anode.ideagenerator.Providers.ProviderError;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The Spring AI {@link ChatModel}s, built on demand and kept per (provider, base URL, key): a tenant's own key and base URL give
 * the tenant its own model, a second request of the same tenant reuses it (each holds an HTTP client with its pools, so they must not
 * be made per call). Spring AI reads the key and base URL when a model is built, not per request.
 * <ul>
 *   <li>OpenAI-compatible (OpenRouter, OpenAI, Azure, LM Studio, MLX, any other): {@code spring-ai-openai} with the provider's base URL;
 *       Azure's key goes in the {@code api-key} header, a local provider has no key at all;</li>
 *   <li>Anthropic: {@code spring-ai-anthropic}; Ollama: {@code spring-ai-ollama}.</li>
 * </ul>
 * On a shared server the OpenAI-compatible and Ollama calls keep the old guarantees: the target is checked to be a public address on every
 * request, redirects are never followed (a public host could send the request inside), and a response larger than {@link Providers#MAX_BODY}
 * is refused.
 */
final class ChatModels {
    private ChatModels() {}

    /** The public-address check of a shared server; a seam so tests can exercise the transport on a loopback fake server. */
    static java.util.function.Consumer<String> publicCheck = Providers::requirePublic;

    private static final Map<String, ChatModel> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_MODELS = 256;

    /** The shared-server transport: never follows a redirect. */
    private static final OkHttpClient NO_REDIRECT = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build();

    private static ChatModel cached(String key, java.util.function.Supplier<ChatModel> build) {
        if (CACHE.size() > MAX_MODELS) CACHE.clear();
        return CACHE.computeIfAbsent(key, k -> build.get());
    }

    private static String digest(String s) {
        if (s == null) return "-";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ChatModel openAiCompatible(AppSettings settings, Provider p, String base, String key) {
        return cached("openai|" + p.id() + "|" + base + "|" + digest(key) + "|" + settings.shared(), () -> {
            var options = OpenAiChatOptions.builder().baseUrl(base).apiKey(key == null ? "none" : key).maxRetries(0).build();
            return OpenAiChatModel.builder().options(options)
                    .httpClientBuilderCustomizer(b -> b.timeout(Duration.ofSeconds(150)).interceptor(transport(settings, p, key)))
                    .build();
        });
    }

    static ChatModel anthropic(String base, String key) {
        return cached("anthropic|" + base + "|" + digest(key), () -> {
            var options = AnthropicChatOptions.builder().apiKey(key);
            if (base != null) options.baseUrl(base);
            return AnthropicChatModel.builder().options(options.build()).build();
        });
    }

    static ChatModel ollama(AppSettings settings, String base) {
        return cached("ollama|" + base + "|" + settings.shared(), () -> {
            HttpClient jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
            var factory = new JdkClientHttpRequestFactory(jdk);
            factory.setReadTimeout(Duration.ofSeconds(300));
            RestClient.Builder rest = RestClient.builder().requestFactory(factory);
            if (settings.shared()) {
                rest.requestInterceptor((request, body, execution) -> {
                    publicCheck.accept(request.getURI().toString());
                    return execution.execute(request, body);
                });
            }
            var api = OllamaApi.builder().baseUrl(base).restClientBuilder(rest).build();
            return OllamaChatModel.builder().ollamaApi(api).options(OllamaChatOptions.builder().build())
                    .retryTemplate(new org.springframework.core.retry.RetryTemplate(org.springframework.core.retry.RetryPolicy.withMaxRetries(0))).build();
        });
    }

    /**
     * What the OpenAI SDK's client sends, adjusted per provider: no key, no Authorization header; Azure takes {@code api-key};
     * OpenRouter wants its referer headers. On a shared server this interceptor is also the transport (see the class comment).
     */
    private static Interceptor transport(AppSettings settings, Provider p, String key) {
        return chain -> {
            Request.Builder b = chain.request().newBuilder();
            if (key == null) {
                b.removeHeader("Authorization");
            } else if (p == Provider.AZURE) {
                b.removeHeader("Authorization").header("api-key", key);
            }
            if (p == Provider.OPENROUTER) b.header("HTTP-Referer", "http://localhost:8300").header("X-Title", "Idea Generator 2");
            Request request = b.build();
            if (!settings.shared()) return chain.proceed(request);

            publicCheck.accept(request.url().toString());
            OkHttpClient client = NO_REDIRECT.newBuilder()
                    .connectTimeout(chain.connectTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(chain.readTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .writeTimeout(chain.writeTimeoutMillis(), TimeUnit.MILLISECONDS).build();
            Response response = client.newCall(request).execute();
            if (response.body() != null && response.peekBody(Providers.MAX_BODY + 1L).contentLength() > Providers.MAX_BODY) {
                response.close();
                throw new IOException("response larger than " + (Providers.MAX_BODY >> 20) + " MB");
            }
            return response;
        };
    }

    /** For tests. */
    static void clear() {
        CACHE.clear();
    }

}
