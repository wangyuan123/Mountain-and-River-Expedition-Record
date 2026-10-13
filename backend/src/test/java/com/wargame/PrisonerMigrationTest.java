package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 迁移在H2兼容模式下建立三张表并保留同城多兵种批次。 */
class PrisonerMigrationTest {
    @Test
    void createsSettlementPoolAndIdempotentOperationTables() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:prisoner_migration;MODE=MySQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V82__prisoner_recruitment.sql"));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO battle_settlements(source_kind,source_id,player_id,created_at) VALUES('player_battle','m1',1,1)");
                statement.executeUpdate("INSERT INTO prisoner_units(player_id,city_id,city_slot,settlement_id,source_id,original_owner_id,type,original_count,count,created_at) VALUES(1,2,0,1,'owner:9',9,'infantry',4,4,1)");
                statement.executeUpdate("INSERT INTO prisoner_units(player_id,city_id,city_slot,settlement_id,source_id,original_owner_id,type,original_count,count,created_at) VALUES(1,2,0,1,'owner:9',9,'tank',2,2,1)");
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM prisoner_units")) { result.next(); assertEquals(2, result.getInt(1)); }
                statement.executeUpdate("INSERT INTO prisoner_operations(player_id,city_id,operation_key,action,type,count,created_at,result) VALUES(1,2,'op-12345678','recruit','infantry',1,1,'{}')");
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM prisoner_operations")) { result.next(); assertEquals(1, result.getInt(1)); }
            }
        }
    }
}
