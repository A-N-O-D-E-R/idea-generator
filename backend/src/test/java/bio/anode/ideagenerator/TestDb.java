package bio.anode.ideagenerator;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres container for the whole test run (needs Docker); every caller gets its own fresh database in it. */
final class TestDb {
    private TestDb() {}

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        PG.start();
    }

    /** JDBC URL of a new, empty database (Flyway migrates it on first use). */
    static synchronized String freshUrl() {
        String name = "test" + N.incrementAndGet();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            c.createStatement().execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    /** A new, migrated database: a Hikari pool on it. */
    static HikariDataSource freshDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(freshUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        ds.setMaximumPoolSize(4);
        Flyway.configure().dataSource(ds).load().migrate();
        return ds;
    }

    static void register(org.springframework.test.context.DynamicPropertyRegistry r) {
        String url = freshUrl();
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

}
