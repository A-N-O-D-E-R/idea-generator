package bio.anode.ideagenerator.model;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public record Lead(long id, long boardId, String source, String title, String summary, String hook, String url,
                   JsonNode meta, Long ideaId, String fetchedAt) {

    /** A verified lead before it is stored. */
    public record Draft(String title, String summary, String hook, String url, Map<String, Object> meta) {}
}
