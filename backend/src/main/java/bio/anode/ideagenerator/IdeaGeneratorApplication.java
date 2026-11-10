package bio.anode.ideagenerator;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class IdeaGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdeaGeneratorApplication.class, args);
    }

    @Bean
    RuntimeSettings runtimeSettings() {
        return new RuntimeSettings();
    }

    @Bean
    @DependsOnDatabaseInitialization // Flyway first
    Repo repo(DataSource ds) {
        Repo repo = new Repo(ds);
        repo.ensureTenant(Repo.LOCAL, "local", null);
        return repo;
    }

    /** An Engine.Io bean (tests) replaces the real network, LLM and file-manager calls. */
    @Bean
    Engine engine(Repo repo, RuntimeSettings runtime, ObjectProvider<Engine.Io> io) {
        return new Engine(repo, t -> runtime.get(), io.getIfAvailable(Engine.Io::real));
    }

    @Bean(destroyMethod = "stop")
    Autopilot autopilot(Repo repo, Engine engine, RuntimeSettings runtime, @Value("${idea.autopilot:true}") boolean start) {
        Autopilot ap = new Autopilot(repo, engine.bus, engine::autoStep, t -> runtime.get(), System::currentTimeMillis,
                r -> Thread.ofVirtual().name("autopilot-step").start(r));
        return start ? ap.start(1000) : ap;
    }

    /** Upstream answers every request with Cache-Control: no-cache. */
    @Bean
    Filter noCache() {
        return (req, res, chain) -> {
            ((HttpServletResponse) res).setHeader("Cache-Control", "no-cache");
            chain.doFilter(req, res);
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent e) {
        String port = e.getApplicationContext().getEnvironment().getProperty("local.server.port", "8300");
        var log = LoggerFactory.getLogger(IdeaGeneratorApplication.class);
        log.info("Idégeneratorn: http://localhost:{}", port);
        long auto = e.getApplicationContext().getBean(Repo.class).autoBoards().size();
        if (auto > 0) log.info("Autopilot on for {} board(s); they need keys from the browser or the environment.", auto);
    }
}
