package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Llm;
import bio.anode.ideagenerator.Markdown;
import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.model.Reports.Dashboard;
import bio.anode.ideagenerator.model.Reports.ProjectRef;
import bio.anode.ideagenerator.model.Reports.Report;
import bio.anode.ideagenerator.model.Reports.ReportBoard;
import bio.anode.ideagenerator.repository.IdeaRepository;
import bio.anode.ideagenerator.repository.ReportRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

/** Insights across every board: keyword trends, the report, its Markdown export and the saved synthesis. */
@Service
@RequiredArgsConstructor
public class ReportService {
    private final Ai ai;
    private final ReportRepository reports;
    private final IdeaRepository ideas;

    public Dashboard dashboard(String t, int days) {
        return reports.dashboard(t, days);
    }

    public List<ObjectNode> ideasWithKeyword(String t, String keyword) {
        return ideas.withKeyword(t, keyword);
    }

    public Report report(String t, int days) {
        return reports.report(t, days);
    }

    public String markdown(String t, int days, String lang) {
        return Markdown.report(reports.report(t, days), lang);
    }

    /** The report's conclusions across every board, saved so the window opens instantly. */
    public Report synthesize(String t, int days, String lang) {
        Report report = reports.report(t, days);
        List<ReportBoard> boards = report.boards().stream().filter(b -> b.ideaCount() > 0).toList();
        if (boards.isEmpty()) throw new AppError(400, "too_few", "There are no ideas to report on yet");
        List<Llm.SynthesisBoard> input = boards.stream().map(b -> new Llm.SynthesisBoard(b.title(), b.goal(),
                b.summary() == null ? "" : Js.str(b.summary().path("headline")),
                b.best().stream().map(i -> new Llm.SynthesisIdea(i.title(), i.pitch(), i.sum())).toList(),
                b.projects().stream().map(ProjectRef::title).toList())).toList();
        AppSettings s = ai.settings(t);
        ObjectNode synthesis = ai.call(t, 0, () -> new Llm(ai.io().chat().apply(s)).synthesize(s.models().get("summary"), lang, days, input)).value();
        reports.saveSynthesis(t, days, lang, synthesis);
        return reports.report(t, days);
    }
}
