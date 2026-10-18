package com.wargame;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证旧列移除后，账号刷新额度、战争保护期、行军目标和装备明细完整保留。 */
class LegacyColumnRemovalMigrationTest {
    @Test
    void removesOnlyRetiredColumnsAndPreservesActiveData() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:legacyColumnRemoval;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(id BIGINT PRIMARY KEY, academy_refresh_at BIGINT, war_at BIGINT, war_end_at BIGINT, war_against_id BIGINT)");
            statement.execute("CREATE TABLE player_cities(id BIGINT PRIMARY KEY, war_at BIGINT, war_end_at BIGINT)");
            statement.execute("CREATE TABLE academy(id BIGINT PRIMARY KEY, player_id BIGINT, city_slot INT, refresh_at BIGINT, officers TEXT)");
            statement.execute("CREATE TABLE city_state(id BIGINT PRIMARY KEY, player_id BIGINT, city_slot INT, status VARCHAR(20), war_at BIGINT, war_target_id BIGINT, war_end_at BIGINT, shield_until BIGINT, peace_until BIGINT, march_boost_until BIGINT)");
            statement.execute("CREATE TABLE marches(id BIGINT PRIMARY KEY, target_idx INT, target_id VARCHAR(100), target_kind VARCHAR(50), army TEXT)");
            statement.execute("CREATE TABLE officers(id BIGINT PRIMARY KEY, equipment_count INT, military INT, defense INT, logistics INT, knowledge INT)");
            statement.execute("CREATE TABLE officer_equipment(id BIGINT PRIMARY KEY, officer_id BIGINT, item_key VARCHAR(64), military_bonus INT)");

            statement.execute("INSERT INTO players VALUES(1,1000,2000,3000,2)");
            statement.execute("INSERT INTO player_cities VALUES(10,4000,5000)");
            statement.execute("INSERT INTO academy VALUES(20,1,0,9999,'existing-candidates')");
            statement.execute("INSERT INTO city_state VALUES(30,1,0,'war',9999,2,6000,7000,8000,9000)");
            statement.execute("INSERT INTO marches VALUES(40,99,'123','bandit','{\"infantry\":12}')");
            statement.execute("INSERT INTO officers VALUES(50,999,60,70,80,90)");
            statement.execute("INSERT INTO officer_equipment VALUES(60,50,'existing-equipment',10)");

            var before = activeData(connection);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V99__remove_unused_legacy_columns.sql"));
            assertEquals(before, activeData(connection));

            for (var retired : Map.of("academy", List.of("refresh_at"),
                    "city_state", List.of("war_at", "war_target_id"),
                    "marches", List.of("target_idx"), "officers", List.of("equipment_count")).entrySet()) {
                for (String column : retired.getValue()) {
                    try (var columns = connection.getMetaData().getColumns(null, null,
                            retired.getKey().toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
                        assertFalse(columns.next(), retired.getKey() + "." + column);
                    }
                }
            }
        }
    }

    /** 仅对现行逻辑依赖的列取快照，避免把其他表的同名战争字段误删。 */
    private Map<String, List<Object>> activeData(Connection connection) throws Exception {
        Map<String, List<Object>> data = new LinkedHashMap<>();
        for (String query : List.of("SELECT * FROM players", "SELECT * FROM player_cities",
                "SELECT id,player_id,city_slot,officers FROM academy",
                "SELECT id,player_id,city_slot,status,war_end_at,shield_until,peace_until,march_boost_until FROM city_state",
                "SELECT id,target_id,target_kind,army FROM marches",
                "SELECT id,military,defense,logistics,knowledge FROM officers", "SELECT * FROM officer_equipment")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                List<Object> values = new ArrayList<>();
                while (rows.next()) {
                    for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                        values.add(rows.getObject(column));
                    }
                }
                data.put(query, values);
            }
        }
        return data;
    }
}
