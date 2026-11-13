package bio.anode.ideagenerator.model;

import java.util.List;

/** An idea on a board, as the API returns it (upstream db.ts Idea). */
public record Idea(
        long id,
        long boardId,
        String title,
        String pitch,
        String kind,
        String sourceUrl,
        double x,
        double y,
        Integer originality,
        Integer feasibility,
        Integer impact,
        String reason,
        String judgedAt,
        Integer medalOverride,
        int generation,
        String projectPath,
        Long originIdeaId,
        Long originBoardId,
        String originBoardTitle,
        List<String> keywords,
        String createdAt,
        /* Elo from pairwise duels (null = never duelled) and how many duels it has had */
        Double elo,
        int duels) {

    public record Edge(long from, long to) {}

    public record Position(long id, double x, double y) {}
}
