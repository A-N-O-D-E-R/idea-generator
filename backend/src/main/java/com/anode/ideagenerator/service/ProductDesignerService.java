package com.anode.ideagenerator.service;

import static com.anode.ideagenerator.AppError.messageOf;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.Js;
import com.anode.ideagenerator.ProductDesigner;
import com.anode.ideagenerator.Providers;
import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.ProviderError;
import com.anode.ideagenerator.model.Board;
import com.anode.ideagenerator.model.Idea;
import com.anode.ideagenerator.repository.BoardRepository;
import com.anode.ideagenerator.repository.IdeaRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * "Send to productDesigner": the idea becomes an {@code idea.json} (with the research block once the gate is done) and is imported
 * there: a Projet and a seeded Map. The last link between this app and the design step of the chain.
 */
@Service
public class ProductDesignerService {
    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final ProjectService projects;
    private final String publicUrl;

    ProductDesignerService(Ai ai, BoardRepository boards, IdeaRepository ideas, ProjectService projects,
                           @Value("${ideagen.public-url:}") String publicUrl) {
        this.ai = ai;
        this.boards = boards;
        this.ideas = ideas;
        this.projects = projects;
        this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
    }

    /** The URL that names this idea upstream of productDesigner (its Projet's traceability key); null when no public URL is set. */
    String sourceUrl(Idea idea) {
        return publicUrl.isEmpty() ? null : publicUrl + "/boards/" + idea.boardId() + "/ideas/" + idea.id();
    }

    /** {project: productDesigner's answer} (id, nom, questions, brouillard, ...). */
    public Map<String, Object> send(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        Board board = boards.require(t, idea.boardId());
        AppSettings s = ai.settings(board);
        String key = !s.productDesignerKey().isEmpty() || s.shared() ? s.productDesignerKey()
                : Objects.requireNonNullElse(Providers.env.apply("PRODUCTDESIGNER_API_KEY"), "");
        if (s.productDesignerUrl().isEmpty() || key.isEmpty()) {
            throw new AppError(400, "no_product_designer", "Set the productDesigner address and API key (pd_...) in the app settings");
        }
        if (s.shared()) {
            try {
                Providers.requirePublic(s.productDesignerUrl());
            } catch (ProviderError e) {
                throw new AppError(400, e.code, messageOf(e));
            }
        }
        String json = Js.stringify(projects.ideaJson(t, idea.id()));
        return Js.obj("project", ProductDesigner.importIdea(s.productDesignerUrl(), key, json, sourceUrl(idea), s.shared()));
    }
}
