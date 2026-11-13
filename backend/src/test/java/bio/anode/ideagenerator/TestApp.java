package bio.anode.ideagenerator;

import static bio.anode.ideagenerator.repository.TenantRepository.LOCAL;

import bio.anode.ideagenerator.config.Mode;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import bio.anode.ideagenerator.repository.SettingsRepository;
import bio.anode.ideagenerator.repository.SpendRepository;
import bio.anode.ideagenerator.repository.TemplateRepository;
import bio.anode.ideagenerator.repository.TenantRepository;
import bio.anode.ideagenerator.service.Ai;
import bio.anode.ideagenerator.service.BoardService;
import bio.anode.ideagenerator.service.EventBus;
import bio.anode.ideagenerator.service.EvolutionService;
import bio.anode.ideagenerator.service.IdeaService;
import bio.anode.ideagenerator.service.JudgeService;
import bio.anode.ideagenerator.service.ProjectService;
import bio.anode.ideagenerator.service.SettingsService;
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
