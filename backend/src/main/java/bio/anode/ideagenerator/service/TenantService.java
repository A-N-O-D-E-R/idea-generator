package bio.anode.ideagenerator.service;

import static bio.anode.ideagenerator.repository.TenantRepository.LOCAL;

import bio.anode.ideagenerator.model.Me;
import bio.anode.ideagenerator.repository.TenantRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

/** Tenants: the local one always exists, others are created at first login. */
@Service
@DependsOnDatabaseInitialization // Flyway first
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository tenants;

    @PostConstruct
    void ensureLocal() {
        tenants.ensure(LOCAL, "local", null);
    }

    /** The tenant id of this login (created on first login). */
    public String login(String provider, String subject, String name, String email) {
        return tenants.login(provider, subject, name, email);
    }

    public Me me(String tenant) {
        return tenants.me(tenant);
    }
}
