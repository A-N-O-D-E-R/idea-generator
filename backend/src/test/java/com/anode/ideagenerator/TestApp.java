package com.anode.ideagenerator;

import static com.anode.ideagenerator.repository.TenantRepository.LOCAL;

import com.anode.ideagenerator.config.Mode;
import com.anode.ideagenerator.model.Board;
import com.anode.ideagenerator.repository.BoardRepository;
import com.anode.ideagenerator.repository.IdeaRepository;
import com.anode.ideagenerator.repository.SettingsRepository;
import com.anode.ideagenerator.repository.SpendRepository;
import com.anode.ideagenerator.repository.TemplateRepository;
import com.anode.ideagenerator.repository.TenantRepository;
import com.anode.ideagenerator.service.Ai;
import com.anode.ideagenerator.service.BoardService;
import com.anode.ideagenerator.service.EventBus;
import com.anode.ideagenerator.service.EvolutionService;
import com.anode.ideagenerator.service.IdeaService;
import com.anode.ideagenerator.service.JudgeService;
import com.anode.ideagenerator.service.ProjectService;
import com.anode.ideagenerator.service.SettingsService;
import javax.sql.DataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The repositories on a fresh database (with the local tenant) and, for the tests that need them, the services wired by hand. */
final class TestApp {
    final TenantRepository tenants;
    final TemplateRepository templates;
    final BoardRepository boards;
    final IdeaRepository ideas;
    final SpendRepository spend;
    final SettingsService settings;
    final EventBus bus = new EventBus();

    TestApp() {
        DataSource ds = TestDb.freshDataSource();
        JdbcClient db = JdbcClient.create(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        templates = new TemplateRepository(db);
        tenants = new TenantRepository(db, tx, templates);
        boards = new BoardRepository(db);
        ideas = new IdeaRepository(db, tx, boards);
        spend = new SpendRepository(db);
        try {
            settings = new SettingsService(new SettingsRepository(db, tx), new Secrets("", false), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        tenants.ensure(LOCAL, "local", null);
    }

    /** A board with the default flow and this partial config merged on top (null = defaults). */
    Board board(String tenant, String title, String config) {
        return boards.insert(tenant, title, "", Board.mergeConfig(Board.defaultConfig(), config == null ? null : Js.parse(config)), null);
    }

    /** The autopilot's step, on the real services with this fake outside world. */
    EvolutionService evolution(Ai.Io io) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("io", io);
        Ai ai = new Ai(settings, spend, new Mode(false), beans.getBeanProvider(Ai.Io.class));
        JudgeService judge = new JudgeService(ai, boards, ideas, new ProjectService(ai, boards, ideas, bus), bus);
        BoardService boardService = new BoardService(ai, boards, ideas, null, templates, spend, judge, bus);
        return new EvolutionService(boards, ideas, new IdeaService(ai, boards, ideas, boardService, judge, bus), judge);
    }
}
