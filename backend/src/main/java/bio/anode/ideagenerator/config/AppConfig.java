package bio.anode.ideagenerator.config;

import bio.anode.ideagenerator.Secrets;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.SettingsRepository;
import bio.anode.ideagenerator.repository.SpendRepository;
import bio.anode.ideagenerator.service.Autopilot;
import bio.anode.ideagenerator.service.EventBus;
import bio.anode.ideagenerator.service.EvolutionService;
import bio.anode.ideagenerator.service.SettingsService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans that need constructor arguments from properties or a test seam; everything else is a component. */
@Configuration
public class AppConfig {

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
    SettingsService settingsService(SettingsRepository repo, Secrets secrets, Mode mode, @Value("${ideagen.data-dir:data}") String dataDir) {
        return new SettingsService(repo, secrets, mode.oauth() ? Path.of(dataDir) : null);
    }

    @Bean(destroyMethod = "stop")
    @DependsOnDatabaseInitialization // Flyway first
    Autopilot autopilot(BoardRepository boards, SpendRepository spend, EventBus bus, EvolutionService evolution, SettingsService settings,
                        @Value("${idea.autopilot:true}") boolean start) {
        Autopilot ap = new Autopilot(boards, spend, bus, evolution::step, settings::get, System::currentTimeMillis,
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
}
