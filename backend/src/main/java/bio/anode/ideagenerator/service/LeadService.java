package bio.anode.ideagenerator.service;

import static bio.anode.ideagenerator.AppError.messageOf;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Leads;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.model.Lead;
import bio.anode.ideagenerator.model.NewIdea;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import bio.anode.ideagenerator.repository.LeadRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Each board's own researchers (X and research scouts) and turning a lead into an idea. */
@Service
@RequiredArgsConstructor
public class LeadService {
    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final LeadRepository leads;
    private final JudgeService judge;
    private final EventBus bus;

    private List<Lead> ofSource(String t, long boardId, String source) {
        return leads.list(t, boardId).stream().filter(l -> l.source().equals(source)).toList();
    }

    public Map<String, Object> refresh(String t, long boardId, String source) {
        Board board = boards.require(t, boardId);
        String lang = board.config().lang();
        Board.Researcher researcher = source.equals("x") ? board.config().researchers().x() : board.config().researchers().research();
        String focus = researcher.focus();
        String topic = Stream.of(board.goal(), focus).filter(s -> !s.isEmpty()).reduce((x, y) -> x + " — " + y).orElse("");
        Leads.Found r;
        if (source.equals("x")) {
            r = ai.call(board, () -> Leads.xLeads(ai.chat(board), ai.modelFor(board, "x"), topic, lang)).value();
        } else {
            List<Leads.Paper> papers;
            try {
                papers = ai.io().fetchPapers().apply(focus.isEmpty() ? board.goal() : focus);
            } catch (RuntimeException e) {
                throw new AppError(502, "research_failed", messageOf(e));
            }
            r = ai.call(board, () -> Leads.researchLeads(ai.chat(board), ai.modelFor(board, "research"), topic, lang, papers)).value();
        }
        // Nothing verified: keep the old leads rather than wiping the panel.
        List<Lead> found = !r.leads().isEmpty() ? leads.replace(t, board.id(), source, r.leads()) : ofSource(t, board.id(), source);
        bus.emit(board.id(), "leads", "source", source, "leads", found);
        return Js.obj("leads", found, "dropped", r.dropped(), "empty", r.leads().isEmpty());
    }

    public Map<String, Object> seed(String t, long leadId, JsonNode at) {
        Lead lead = leads.find(t, leadId);
        if (lead == null) throw new AppError(404, "not_found", "Lead not found");
        double[] pos = Js.isFinite(at.path("x")) && Js.isFinite(at.path("y"))
                ? new double[]{at.path("x").asDouble(), at.path("y").asDouble()}
                : Layout.besideBoard(ideas.list(t, lead.boardId()));
        String pitch = Js.cut(Stream.of(lead.summary(), lead.hook()).filter(s -> s != null && !s.isEmpty())
                .reduce((x, y) -> x + " " + y).orElse(""), 600);
        Idea idea = ideas.insert(t, new NewIdea(lead.boardId(), lead.title(), pitch, "seed", lead.url(), pos[0], pos[1], 0));
        Lead used = leads.markUsed(t, lead.id(), idea.id());
        bus.ideas(lead.boardId(), List.of(idea));
        bus.emit(lead.boardId(), "leads", "source", lead.source(), "leads", ofSource(t, lead.boardId(), lead.source()));
        judge.queue(t, List.of(idea.id()));
        return Js.obj("idea", idea, "lead", used);
    }

    public boolean delete(String t, long id) {
        return leads.delete(t, id);
    }
}
