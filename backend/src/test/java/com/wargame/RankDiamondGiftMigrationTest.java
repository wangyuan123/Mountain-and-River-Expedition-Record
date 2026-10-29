package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class RankDiamondGiftMigrationTest {
    @Test
    void migrationFreezesOldRanksAndLeavesNewAndUninitializedAccountsOnStarterPath() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:rank_gifts_migration;MODE=MySQL", "sa", "")) {
            var sql = db.createStatement();
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, game_initialized BOOLEAN, military_rank INT, prestige INT, rank_exam TEXT)");
            sql.execute("INSERT INTO players VALUES(1,TRUE,16,9000000,'passed'),(2,TRUE,17,10000000,NULL),(3,FALSE,1,0,NULL),(4,TRUE,NULL,0,NULL)");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("db/migration/V139__rank_diamond_gifts.sql"));
            sql.execute("INSERT INTO players(id,game_initialized,military_rank) VALUES(5,TRUE,1)");
            var rows = sql.executeQuery("SELECT * FROM players ORDER BY id");
            for (int expected : new int[]{16,17,0,1,0}) {
                assertTrue(rows.next()); assertEquals(expected, rows.getInt("rank_gift_baseline_tier"));
                assertFalse(rows.getBoolean("rank_gifts_enrolled"));
            }
            rows = sql.executeQuery("SELECT prestige,rank_exam FROM players WHERE id=1"); rows.next();
            assertEquals(9000000, rows.getInt(1)); assertEquals("passed", rows.getString(2));
            sql.execute("INSERT INTO rank_gift_rewards(player_id,rank_tier,item_key,diamonds,source,issued_at) VALUES(1,16,'rank_diamond_pack_16',2500,'legacy',100)");
            assertThrows(java.sql.SQLException.class, () -> sql.execute("INSERT INTO rank_gift_rewards(player_id,rank_tier,item_key,diamonds,source,issued_at) VALUES(1,16,'rank_diamond_pack_16',2500,'promotion',200)"));
            sql.execute("UPDATE players SET military_rank=17 WHERE id=1");
            rows = sql.executeQuery("SELECT rank_gift_baseline_tier FROM players WHERE id=1"); rows.next();
            assertEquals(16, rows.getInt(1));
        }
    }
}
