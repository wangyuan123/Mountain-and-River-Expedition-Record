package com.wargame;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
class PvpMigrationTest {
    @Test void migrationIsIndependentAndEnforcesRoundReportRequestUniqueness() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:pvp_migration;MODE=MySQL", "sa", "")) {
            var s = c.createStatement(); s.execute("CREATE TABLE army_units(id BIGINT PRIMARY KEY, count INT)"); s.execute("INSERT INTO army_units VALUES(1,100)");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V87__pvp_practice.sql"));
            s.execute("INSERT INTO pvp_player_states(player_id) VALUES(1)"); var p = s.executeQuery("select rating from pvp_player_states"); assertTrue(p.next()); assertEquals(1000, p.getInt(1));
            for (String insert : new String[]{"INSERT INTO pvp_round_orders(id,session_id,round_no,player_id) VALUES('%s','s',1,1)", "INSERT INTO pvp_round_results(id,session_id,round_no) VALUES('%s','s',1)", "INSERT INTO pvp_practice_reports(id,session_id,player_id) VALUES('%s','s',1)", "INSERT INTO pvp_requests(id,player_id,request_id) VALUES('%s',1,'request')"}) {
                s.execute(insert.formatted("one")); assertThrows(java.sql.SQLException.class, () -> s.execute(insert.formatted("two")));
            }
            var r = s.executeQuery("select count from army_units where id=1"); assertTrue(r.next()); assertEquals(100, r.getInt(1));
        }
    }
}
