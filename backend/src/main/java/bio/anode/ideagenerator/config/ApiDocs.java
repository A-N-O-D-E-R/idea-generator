package bio.anode.ideagenerator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import java.util.Locale;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI at /v3/api-docs, Swagger UI at /swagger-ui.html. Bodies are read raw (upstream's lenient JSON coercion),
 * so request schemas are free-form; each operation is tagged by its first path segment and summarised from its name.
 */
@Configuration
public class ApiDocs {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Idea Generator API")
                .version("0.1.0")
                .license(new License().name("MIT (upstream fltman/idea-generator-2)").url("https://github.com/fltman/idea-generator-2"))
                .description("""
                        Boards, ideas (seed, branch, cross, judge, duel), scouts, reports and settings.
                        Errors are `{"error": "...", "code": "..."}`. Writes need the CSRF token: send the `XSRF-TOKEN` cookie back as
                        the `X-XSRF-TOKEN` header (Swagger UI does it). With `ideagen.auth=oauth`, `/api/**` needs a signed-in session
                        (sign in at `/`). Live board events: `GET /api/boards/{id}/events` (server-sent events)."""));
    }

    @Bean
    OpenApiCustomizer tagsAndSummaries() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
            String[] parts = path.replaceFirst("^/api/?", "").split("[/.]");
            String tag = parts.length == 0 || parts[0].isEmpty() ? "app" : parts[0];
            op.setTags(java.util.List.of(tag));
            if (op.getSummary() == null && op.getOperationId() != null) {
                String words = op.getOperationId().replaceAll("_\\d+$", "").replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
                op.setSummary(Character.toUpperCase(words.charAt(0)) + words.substring(1));
            }
        }));
    }
}
