package bio.anode.ideagenerator.service;

import static bio.anode.ideagenerator.AppError.messageOf;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Leads;
import bio.anode.ideagenerator.Project;
import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Workspace;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** An idea's Claude Code project folder: written from a brief, filed in the workspace, revealed, zipped. */
@Service
@RequiredArgsConstructor
public class ProjectService {
    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final EventBus bus;

    /**
     * The idea's project folder, or null. On a shared server projectPath holds only the folder name, inside the tenant's
     * projects folder, so no server path reaches the JSON (getFileName also strips anything that tries to climb out).
     */
    static Path projectFolder(AppSettings s, Idea idea) {
        if (idea.projectPath() == null || idea.projectPath().isEmpty()) return null;
        Path p = Path.of(idea.projectPath());
        return s.shared() ? Path.of(s.projectsRoot()).resolve(p.getFileName().toString()) : p;
    }

    public Map<String, Object> create(String t, long ideaId) {
        return create(t, ideas.require(t, ideaId));
    }

    Map<String, Object> create(String t, Idea idea) {
        Board board = boards.require(t, idea.boardId());
        AppSettings settings = ai.settings(board);
        Path existing = projectFolder(settings, idea);
        if (existing != null && Files.exists(existing)) {
            return Js.obj("idea", idea, "path", idea.projectPath(), "existed", true);
        }
        List<Idea> lineage = ideas.lineage(t, idea.id());
        String lang = board.config().lang();
        Project.Brief brief = ai.call(board, () -> Project.writeBrief(ai.chat(board), ai.modelFor(board, "brief"), idea, board.goal(), lineage, lang)).value();
        String path;
        try {
            path = Project.writeProject(settings.projectsRoot(), brief, idea, board.goal(), lineage, lang, ai.io().gitInit());
            if (settings.shared()) path = Path.of(path).getFileName().toString();
        } catch (Exception e) {
            throw new AppError(500, "project_failed", messageOf(e));
        }
        Idea saved = ideas.setProjectPath(t, idea.id(), path);
        bus.ideas(board.id(), List.of(saved));
        Map<String, Object> out = Js.obj("idea", saved, "path", path, "existed", false);
        if (!settings.workspaceRoot().isEmpty()) {
            // The project is made either way; filing into the workspace is a bonus that may fail on its own.
            try {
                fileInWorkspace(out, board, settings, brief, saved, lineage, path);
            } catch (IOException | RuntimeException e) {
                out.put("workspaceError", messageOf(e));
            }
        }
        return out;
    }

    private void fileInWorkspace(Map<String, Object> out, Board board, AppSettings settings, Project.Brief brief, Idea saved,
                                 List<Idea> lineage, String path) throws IOException {
        String workspace = settings.workspaceRoot();
        List<Workspace.Row> rows = Workspace.rows(Workspace.readIndex(workspace));
        List<Workspace.Repo> repos = Workspace.scanRepos(settings.reposRoots());
        List<Leads.Paper> papers = ai.io().searchPapers().apply(brief.name() + " " + brief.oneLiner());
        Workspace.Match match = ai.call(board, () -> Workspace.match(ai.chat(board), ai.modelFor(board, "brief"), brief, saved, rows, repos, papers)).value();
        Workspace.Filed f = Workspace.file(workspace, brief, saved, board.goal(), lineage, path, Js.utcToday(), match);
        out.put("workspace", f.path());
        out.put("workspaceExisted", f.existed());
        if (match.duplicateOf() != null) out.put("workspaceDuplicateOf", match.duplicateOf());
        out.put("workspaceRelated", f.related());
        out.put("workspaceRepos", match.repos().stream().map(Workspace.RepoHit::path).toList());
        out.put("workspacePapers", match.papers().size());
    }

    public Map<String, Object> reveal(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        if (ai.settings(boards.require(t, idea.boardId())).shared()) throw new AppError(403, "not_available", "Not available on a shared server");
        if (idea.projectPath() == null || idea.projectPath().isEmpty() || !Files.exists(Path.of(idea.projectPath()))) {
            throw new AppError(404, "no_project", "The project folder is gone");
        }
        ai.io().reveal().accept(idea.projectPath());
        return Js.obj("ok", true);
    }

    /** The idea's project folder, for the zip download; on a shared server only inside the tenant's projects folder. */
    public Path dir(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        AppSettings s = ai.settings(boards.require(t, idea.boardId()));
        Path folder = projectFolder(s, idea);
        if (folder == null || !Files.isDirectory(folder)) throw new AppError(404, "no_project", "This idea has no project folder");
        try {
            Path dir = folder.toRealPath();
            if (s.shared() && !dir.startsWith(Path.of(s.projectsRoot()).toRealPath())) throw new AppError(404, "no_project", "This idea has no project folder");
            return dir;
        } catch (IOException e) {
            throw new AppError(404, "no_project", "This idea has no project folder");
        }
    }
}
