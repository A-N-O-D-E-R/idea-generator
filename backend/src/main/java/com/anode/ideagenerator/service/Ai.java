package com.anode.ideagenerator.service;

import static com.anode.ideagenerator.AppError.messageOf;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.Extract;
import com.anode.ideagenerator.Leads;
import com.anode.ideagenerator.Llm;
import com.anode.ideagenerator.Project;
import com.anode.ideagenerator.Providers;
import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.Chat;
import com.anode.ideagenerator.Providers.ProviderError;
import com.anode.ideagenerator.config.Mode;
import com.anode.ideagenerator.model.Board;
import com.anode.ideagenerator.repository.SpendRepository;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Everything that leaves the process for a board's sake: the LLM (with the board's tenant's settings and models, spend
 * booked on the board's ledger) and, through {@link Io}, the page, paper and file-manager calls. A test supplies an
 * {@link Io} bean to fake them all.
 */
@Component
public class Ai {
    /** What leaves the process. chat: a chat routed with the given (tenant's) settings. */
    public record Io(Function<AppSettings, Chat> chat, Function<String, Extract.Page> fetchPage, Function<String, List<Leads.Paper>> fetchPapers,
                     Consumer<String> reveal, boolean gitInit, Function<String, List<Leads.Paper>> searchPapers) {
        /** shared: a shared server (ideagen.auth=oauth) only fetches public addresses. */
        public static Io real(boolean shared) {
            return new Io(Providers::makeChat, url -> Extract.fetchPage(url, shared), Leads::fetchPapers, Project::reveal, true, Leads::searchPapers);
        }
    }

    private final SettingsService settings;
    private final SpendRepository spend;
    private final Io io;

    public Ai(SettingsService settings, SpendRepository spend, Mode mode, ObjectProvider<Io> io) {
        this.settings = settings;
        this.spend = spend;
        this.io = io.getIfAvailable(() -> Io.real(mode.oauth()));
    }

    public Io io() {
        return io;
    }

    public AppSettings settings(String tenant) {
        return settings.get(tenant);
    }

    public AppSettings settings(Board board) {
        return settings.get(board.tenantId());
    }

    public Chat chat(Board board) {
        return io.chat().apply(settings(board));
    }

    public Llm llm(Board board) {
        return new Llm(chat(board));
    }

    /** The board's model for a role, else the tenant's. */
    public String modelFor(Board board, String role) {
        String m = board.config().models().get(role);
        return m != null && !m.isEmpty() ? m : settings(board).models().get(role);
    }

    /** Every LLM call: spend lands on the board's ledger; failures become 502s that keep the provider's code. */
    public <T> Llm.Out<T> call(Board board, Supplier<Llm.Out<T>> fn) {
        return call(board.tenantId(), board.id(), fn);
    }

    public <T> Llm.Out<T> call(String t, long boardId, Supplier<Llm.Out<T>> fn) {
        try {
            Llm.Out<T> out = fn.get();
            spend.add(t, boardId, out.costUsd());
            return out;
        } catch (AppError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AppError(502, e instanceof ProviderError p ? p.code : "llm_failed", messageOf(e));
        }
    }
}
