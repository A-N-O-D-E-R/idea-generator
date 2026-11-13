package bio.anode.ideagenerator.model;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/** The shapes of the dashboard (keyword trends), the report and the spend ledger. */
public final class Reports {
    private Reports() {}

    public record Spent(double usd, long calls) {}

    // ---- dashboard
    public record KeywordTrend(String keyword, long count, long boards, Double meanSum, long recent, long previous, int[] series) {}

    public record KeywordCount(String keyword, long count) {}

    public record BoardKeywords(long id, String title, List<KeywordCount> keywords) {}

    public record DashboardTotals(long ideas, long boards, long keywords, long recent) {}

    public record Dashboard(List<String> days, DashboardTotals totals, List<KeywordTrend> keywords, List<BoardKeywords> boards) {}

    // ---- report
    public record Brief(long id, long boardId, String title, String pitch, Integer sum, Integer medal, String reason, String kind) {}

    public record ProjectRef(long id, long boardId, String title, String pitch, Integer sum, Integer medal, String reason,
                             String kind, String boardTitle, String path) {}

    public record Medals(int gold, int silver, int bronze) {}

    public record Totals(int boards, int ideas, int newIdeas, int judged, Medals medals, int projects, double spentUsd) {}

    public record ReportBoard(long id, String title, String goal, String mode, int ideaCount, int newIdeas, List<Brief> best,
                              ObjectNode summary, List<ProjectRef> projects) {}

    public record Report(int days, Totals totals, List<ProjectRef> projects, List<ReportBoard> boards, ObjectNode synthesis) {}
}
