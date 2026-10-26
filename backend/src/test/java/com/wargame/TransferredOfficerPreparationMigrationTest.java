package com.wargame;

import db.migration.V125__transferred_officer_preparation;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 覆盖跨页、重复记录、遗留孤儿及重复运行，防止历史修复改变账号进度。 */
class TransferredOfficerPreparationMigrationTest {
    @Test void repairsTransferredPreparationWithoutAddingLevelsOrMovingAccountProgress() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE officers(id BIGINT PRIMARY KEY,player_id BIGINT NOT NULL)");
            sql.execute("CREATE TABLE officer_equipment_progress(id BIGINT PRIMARY KEY,player_id BIGINT NOT NULL,progress_key VARCHAR(120),amount INT,version BIGINT DEFAULT 0,UNIQUE(player_id,progress_key))");
            sql.execute("INSERT INTO officers VALUES(51,2)");
            try (var insert = connection.prepareStatement("INSERT INTO officer_equipment_progress(id,player_id,progress_key,amount) VALUES(?,?,?,?)")) {
                for (int id = 1; id <= 202; id++) {
                    insert.setInt(1, id); insert.setInt(2, 1); insert.setString(3, "prep/51/slot" + id); insert.setInt(4, 5); insert.addBatch();
                }
                insert.executeBatch();
            }
            sql.execute("INSERT INTO officer_equipment_progress(id,player_id,progress_key,amount) VALUES(203,2,'prep/51/slot1',3),(204,1,'collection/marshal_military_weapon',1),(205,1,'prep/999/weapon',4),(206,1,'prep/invalid/weapon',3),(207,1,'loadout/1/a',1),(208,1,'prep/999999999999999999999999/weapon',5)");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            new V125__transferred_officer_preparation().migrate(context);
            new V125__transferred_officer_preparation().migrate(context);
            try (var rows = sql.executeQuery("SELECT COUNT(*),MIN(amount),MAX(amount) FROM officer_equipment_progress WHERE player_id=2")) {
                assertTrue(rows.next()); assertEquals(202, rows.getInt(1)); assertEquals(5, rows.getInt(2)); assertEquals(5, rows.getInt(3));
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM officer_equipment_progress WHERE player_id=1")) {
                assertTrue(rows.next()); assertEquals(5, rows.getInt(1));
            }
        }
    }
}
