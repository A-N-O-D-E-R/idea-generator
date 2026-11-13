package bio.anode.ideagenerator.model;

/** An idea about to be inserted. */
public record NewIdea(long boardId, String title, String pitch, String kind, String sourceUrl, double x, double y,
                      int generation, Long originIdeaId, Long originBoardId) {
    public NewIdea(long boardId, String title, String pitch, String kind, String sourceUrl, double x, double y, int generation) {
        this(boardId, title, pitch, kind, sourceUrl, x, y, generation, null, null);
    }
}
