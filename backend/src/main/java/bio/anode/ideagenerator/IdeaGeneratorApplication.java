package bio.anode.ideagenerator;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
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

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if (a.startsWith("--generate-keyset=")) {
                Path file = Path.of(a.substring("--generate-keyset=".length()));
                Secrets.generate(file);
                System.out.println("Wrote a new keyset to " + file.toAbsolutePath() + "; set IDEAGEN_KEYSET_FILE to it. Keep it secret and backed up.");
                return;
            }
        }
        SpringApplication.run(IdeaGeneratorApplication.class, args);
    }

    /** ideagen.auth: none (one local tenant, loopback only) or oauth. */
    public record Mode(boolean oauth) {
        static Mode of(String auth, String address) {
            if (auth.equals("oauth")) return new Mode(true);
            if (!auth.equals("none")) throw new IllegalStateException("ideagen.auth must be none or oauth, not " + auth);
            boolean loopback;
            try {
                loopback = address != null && !address.isBlank() && InetAddress.getByName(address).isLoopbackAddress();
            } catch (UnknownHostException e) {
                loopback = false;
            }
            if (!loopback) {
                throw new IllegalStateException("ideagen.auth=none has no login: server.address must be loopback (127.0.0.1), not '"
                        + address + "'. Use ideagen.auth=oauth to serve other machines.");
            }
            return new Mode(false);
        }
    }

    @Bean
    Mode mode(@Value("${ideagen.auth:none}") String auth, @Value("${server.address:}") String address) {
        return Mode.of(auth, address);
    }

    @Bean
    Secrets secrets(Mode mode, @Value("${ideagen.keyset-file:}") String keysetFile) throws Exception {
        return new Secrets(keysetFile, mode.oauth());
    }

    @Bean
    Settings settings(DataSource ds, Secrets secrets) {
        return new Settings(ds, secrets);
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
    Engine engine(Repo repo, Settings settings, ObjectProvider<Engine.Io> io) {
        return new Engine(repo, settings::get, io.getIfAvailable(Engine.Io::real));
    }

    @Bean(destroyMethod = "stop")
    Autopilot autopilot(Repo repo, Engine engine, Settings settings, @Value("${idea.autopilot:true}") boolean start) {
        Autopilot ap = new Autopilot(repo, engine.bus, engine::autoStep, settings::get, System::currentTimeMillis,
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
        if (auto > 0) log.info("Autopilot on for {} board(s); they need keys in the settings or the environment.", auto);
    }
}
