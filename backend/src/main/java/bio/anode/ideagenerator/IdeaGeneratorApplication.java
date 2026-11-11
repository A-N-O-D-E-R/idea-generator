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
        // Pin the DNS cache so a shared server's address check (Providers.requirePublic) and the connect right after it
        // see the same answer; short enough that real DNS changes still land.
        java.security.Security.setProperty("networkaddress.cache.ttl", "30");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "10");
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
            return of(auth, address, false);
        }

        /**
         * publishedOnLoopback: the operator states the process is only reachable through a loopback port mapping
         * (docker-compose.yml publishes 127.0.0.1:8300 only), so a container may bind 0.0.0.0 without a login.
         */
        static Mode of(String auth, String address, boolean publishedOnLoopback) {
            if (auth.equals("oauth")) return new Mode(true);
            if (!auth.equals("none")) throw new IllegalStateException("ideagen.auth must be none or oauth, not " + auth);
            boolean loopback;
            try {
                loopback = address != null && !address.isBlank() && InetAddress.getByName(address).isLoopbackAddress();
            } catch (UnknownHostException e) {
                loopback = false;
            }
            if (!loopback && publishedOnLoopback) {
                org.slf4j.LoggerFactory.getLogger(Mode.class).warn("ideagen.auth=none on {}: no login. Only safe because the port is published on "
                        + "127.0.0.1 alone (ideagen.none-published-on-loopback=true); never map it to another interface.", address);
            } else if (!loopback) {
                throw new IllegalStateException("ideagen.auth=none has no login: server.address must be loopback (127.0.0.1), not '"
                        + address + "'. Use ideagen.auth=oauth to serve other machines.");
            }
            return new Mode(false);
        }
    }

    @Bean
    Mode mode(@Value("${ideagen.auth:none}") String auth, @Value("${server.address:}") String address,
              @Value("${ideagen.none-published-on-loopback:false}") boolean publishedOnLoopback) {
        return Mode.of(auth, address, publishedOnLoopback);
    }

    @Bean
    Secrets secrets(Mode mode, @Value("${ideagen.keyset-file:}") String keysetFile) throws Exception {
        return new Secrets(keysetFile, mode.oauth());
    }

    /** On a shared server (oauth) every tenant's projects live under ideagen.data-dir/tenants/<id>/projects. */
    @Bean
    Settings settings(DataSource ds, Secrets secrets, Mode mode, @Value("${ideagen.data-dir:data}") String dataDir) {
        return new Settings(ds, secrets, mode.oauth() ? Path.of(dataDir) : null);
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
    Engine engine(Repo repo, Settings settings, Mode mode, ObjectProvider<Engine.Io> io) {
        return new Engine(repo, settings::get, io.getIfAvailable(() -> Engine.Io.real(mode.oauth())));
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
