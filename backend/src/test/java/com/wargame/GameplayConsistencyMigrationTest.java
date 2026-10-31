package com.wargame;

import db.migration.V150__gameplay_consistency;
import db.migration.V151__independent_personal_wars;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离历史夹具验证钱包归并、邀请转换及独立战争迁移，不连接游戏数据库。 */
class GameplayConsistencyMigrationTest {
    @Test
    void preservesAssetsAndRequiresNewConsentForLegacyFriendships() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var s = c.createStatement()) {
            s.execute("CREATE TABLE resources(player_id BIGINT,city_slot INT,diamond INT)");
            s.execute("INSERT INTO resources VALUES(1,0,20),(1,1,30)");
            s.execute("CREATE TABLE officers(id BIGINT,military INT)");
            s.execute("INSERT INTO officers VALUES(1,115)");
            s.execute("CREATE TABLE players(id BIGINT,account_status VARCHAR(20))");
            s.execute("INSERT INTO players VALUES(1,'ACTIVE'),(2,'DELETED')");
            s.execute("CREATE TABLE player_cities(id BIGINT)");
            s.execute("CREATE TABLE forum_topics(id BIGINT,tip_total_gold INT)");
            s.execute("CREATE TABLE forum_tips(topic_id BIGINT,amount_gold BIGINT)");
            s.execute("INSERT INTO forum_topics VALUES(1,0)");
            s.execute("INSERT INTO forum_tips VALUES(1,100),(1,200)");
            s.execute("CREATE TABLE guilds(id BIGINT)");
            s.execute("INSERT INTO guilds VALUES(10),(20)");
            s.execute("CREATE TABLE guild_applications(guild_id BIGINT)");
            s.execute("INSERT INTO guild_applications VALUES(10),(30)");
            s.execute("CREATE TABLE guild_members(player_id BIGINT,guild_id BIGINT)");
            s.execute("INSERT INTO guild_members VALUES(1,10)");
            s.execute("CREATE TABLE guild_relations(id BIGINT,guild_low_id BIGINT,guild_high_id BIGINT,updated_by_player_id BIGINT,status VARCHAR(16),CONSTRAINT chk_guild_relations_status CHECK(status IN ('friendly','hostile')))");
            s.execute("INSERT INTO guild_relations VALUES(1,10,20,1,'friendly')");
            s.execute("CREATE TABLE market_orders(seller_id BIGINT,status VARCHAR(20),completed_at TIMESTAMP)");
            s.execute("INSERT INTO market_orders VALUES(1,'ACTIVE',NULL),(2,'ACTIVE',NULL)");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(c);
            new V150__gameplay_consistency().migrate(context);
            try (var r = s.executeQuery("SELECT diamond FROM resources ORDER BY city_slot")) {
                assertTrue(r.next()); assertEquals(50, r.getInt(1));
                assertTrue(r.next()); assertEquals(0, r.getInt(1));
            }
            try (var r = s.executeQuery("SELECT status,friendship_requested_by FROM guild_relations")) {
                assertTrue(r.next()); assertEquals("neutral", r.getString(1)); assertNull(r.getObject(2));
            }
            try (var r = s.executeQuery("SELECT military,assigned_military,legacy_attr_allocation FROM officers")) {
                assertTrue(r.next()); assertEquals(115, r.getInt(1)); assertEquals(0, r.getInt(2)); assertTrue(r.getBoolean(3));
            }
            try (var r = s.executeQuery("SELECT tip_total_gold FROM forum_topics")) {
                assertTrue(r.next()); assertEquals(300L, r.getLong(1));
                assertEquals(java.sql.Types.BIGINT, r.getMetaData().getColumnType(1));
            }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM guild_applications")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            try (var r = s.executeQuery("SELECT status FROM market_orders ORDER BY seller_id")) {
                assertTrue(r.next()); assertEquals("ACTIVE", r.getString(1));
                assertTrue(r.next()); assertEquals("CANCELLED", r.getString(1));
            }
        }
    }

    @Test
    void migratesDistinctLegacyOpponentsWithoutDuplicatingMirroredSnapshots() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var s = c.createStatement()) {
            s.execute("CREATE TABLE players(id BIGINT,account_status VARCHAR(20),war_against_id BIGINT,war_initiator_id BIGINT,war_city_slot INT,war_target_city_id BIGINT,war_declared_at BIGINT,war_at BIGINT,war_end_at BIGINT,war_accelerated BOOLEAN,war_order_effect TEXT)");
            s.execute("INSERT INTO players VALUES(1,'ACTIVE',2,1,0,12,10,20,30,FALSE,NULL),(2,'ACTIVE',1,1,0,12,10,20,30,FALSE,NULL),(3,'ACTIVE',1,3,1,13,11,21,31,TRUE,'{}')");
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(c);
            new V151__independent_personal_wars().migrate(context);
            try (var r = s.executeQuery("SELECT player_low_id,player_high_id,war_at,accelerated FROM personal_wars ORDER BY player_high_id")) {
                assertTrue(r.next()); assertEquals(1, r.getLong(1)); assertEquals(2, r.getLong(2)); assertEquals(20, r.getLong(3));
                assertTrue(r.next()); assertEquals(1, r.getLong(1)); assertEquals(3, r.getLong(2)); assertEquals(21, r.getLong(3)); assertTrue(r.getBoolean(4));
                assertFalse(r.next());
            }
        }
    }
}
