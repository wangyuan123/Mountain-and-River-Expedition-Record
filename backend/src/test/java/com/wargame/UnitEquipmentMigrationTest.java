package com.wargame;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
class UnitEquipmentMigrationTest {
    @Test void oldMarchesAndBattlesRemainWithoutEquipmentAndLoadoutsAreUnique() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:unit_equipment_migration;MODE=MySQL", "sa", "")) {
            var s = c.createStatement(); s.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, army TEXT)"); s.execute("INSERT INTO marches VALUES(1,'{\"infantry\":12}')");
            s.execute("CREATE TABLE battle_sessions(id BIGINT PRIMARY KEY)"); s.execute("INSERT INTO battle_sessions VALUES(1)");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V85__unit_equipment.sql"));
            var r = s.executeQuery("SELECT * FROM marches"); assertTrue(r.next()); assertNull(r.getString("unit_equipment_snapshot")); assertEquals("{\"infantry\":12}", r.getString("army"));
            s.execute("INSERT INTO unit_loadouts(player_id,unit_type,slots) VALUES(1,'infantry','{}')");
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO unit_loadouts(player_id,unit_type,slots) VALUES(1,'infantry','{}')"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO unit_equipment_orders(player_id,item_key,cost,start_at,end_at) VALUES(1,'ue_infantry_helmet',NULL,0,1)"));
        }
    }
}
