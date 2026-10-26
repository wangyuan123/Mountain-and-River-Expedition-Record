package com.wargame;

import db.migration.V121__officer_twelve_piece_equipment;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficerEquipmentMigrationTest {
    @Test void backfillUsesMinimumAcrossInventoryAndEquippedAndRetryNeverAddsTwice() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:gear_migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE players(id BIGINT PRIMARY KEY)");
                s.execute("INSERT INTO players VALUES(1),(2)");
                s.execute("CREATE TABLE player_items(id BIGINT AUTO_INCREMENT PRIMARY KEY,player_id BIGINT,item_key VARCHAR(50),count INT,updated_at BIGINT,version BIGINT DEFAULT 0,UNIQUE(player_id,item_key))");
                s.execute("CREATE TABLE officer_equipment(player_id BIGINT,item_key VARCHAR(80),officer_id BIGINT)");
                s.execute("INSERT INTO player_items(player_id,item_key,count) VALUES(1,'officer_defense_weapon',2),(1,'officer_defense_badge',1),(1,'officer_defense_coat',3),(2,'recruit_military_weapon',1)");
                s.execute("INSERT INTO officer_equipment VALUES(1,'officer_defense_badge',10),(1,'officer_defense_weapon',11)");
            }
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(c);
            var migration = new V121__officer_twelve_piece_equipment(); migration.migrate(context); migration.migrate(context);
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM player_items WHERE player_id=1 AND count=2 AND item_key NOT IN ('officer_defense_weapon','officer_defense_badge','officer_defense_coat')")) { assertTrue(r.next()); assertEquals(9, r.getInt(1)); }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM player_items WHERE player_id=2")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM officer_equipment_progress WHERE player_id=1 AND progress_key LIKE 'collection/%'")) { assertTrue(r.next()); assertEquals(12, r.getInt(1)); }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM officer_equipment")) { assertTrue(r.next()); assertEquals(2, r.getInt(1)); }
        }
    }
}
