package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.Js;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.experimental.UtilityClass;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Small helpers the repositories share. Call them qualified (Rows.count): static imports do not work on a @UtilityClass. */
@UtilityClass
class Rows {
    /** The boards of tenant ? (a SQL fragment: "board_id IN (...)"). Every query on a board's rows ends with it. */
    final String OWN = "board_id IN (SELECT id FROM boards WHERE tenant_id = ?) ";

    long count(JdbcClient db, String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /** A JSON column; anything unreadable is an empty object. */
    JsonNode json(String s) {
        try {
            return Js.parse(s);
        } catch (RuntimeException e) {
            return Js.object();
        }
    }
}
