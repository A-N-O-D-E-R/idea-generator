package bio.anode.ideagenerator;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class IdeaGeneratorApplication {

    public static void main(String[] args) {
        // SQLite will not create the folder for its file (upstream: mkdir data/).
        String db = System.getenv().getOrDefault("DB_FILE", "data/ideas.db");
        File parent = new File(db).getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        SpringApplication.run(IdeaGeneratorApplication.class, args);
    }

    @Bean
    RuntimeSettings runtimeSettings() {
        return new RuntimeSettings();
    }

    @Bean
    Repo repo(DataSource ds) {
        return new Repo(ds);
    }

    /** An Engine.Io bean (tests) replaces the real network, LLM and file-manager calls. */
    @Bean
    Engine engine(Repo repo, RuntimeSettings runtime, ObjectProvider<Engine.Io> io) {
        return new Engine(repo, runtime::get, io.getIfAvailable(() -> Engine.Io.real(runtime::get)));
    }

    @Bean(destroyMethod = "stop")
    Autopilot autopilot(Repo repo, Engine engine, RuntimeSettings runtime, @Value("${idea.autopilot:true}") boolean start) {
        Autopilot ap = new Autopilot(repo, engine.bus, engine::autoStep, runtime::get, System::currentTimeMillis,
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
        long auto = e.getApplicationContext().getBean(Repo.class).listBoards().stream()
                .filter(b -> "auto".equals(b.path("config").path("mode").asString(""))).count();
        if (auto > 0) log.info("Autopilot on for {} board(s); they need keys from the browser or the environment.", auto);
    }
}
