package bio.anode.ideagenerator;

import bio.anode.ideagenerator.repository.BoardRepository;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
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

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent e) {
        String port = e.getApplicationContext().getEnvironment().getProperty("local.server.port", "8300");
        log.info("Idégeneratorn: http://localhost:{}", port);
        long auto = e.getApplicationContext().getBean(BoardRepository.class).auto().size();
        if (auto > 0) log.info("Autopilot on for {} board(s); they need keys in the settings or the environment.", auto);
    }
}
