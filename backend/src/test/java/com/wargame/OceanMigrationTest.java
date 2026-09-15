package com.wargame;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
class OceanMigrationTest {
    @Test void migrationRetainsCitiesResourcesAndInflightTimes() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:ocean-migration;MODE=MySQL","sa","");var s=c.createStatement()){
            s.execute("create table world_map(id bigint primary key)");
            s.execute("create table player_cities(id bigint primary key,owner_id bigint,x int,y int)");
            s.execute("create table marches(id bigint primary key,start_at bigint,arrive_at bigint)");
            s.execute("insert into world_map values(1)");s.execute("insert into player_cities values(1,1,190,190),(2,null,20,20)");s.execute("insert into marches values(1,1000,9000)");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V33__ocean_terrain_and_routes.sql"));
            try(var rs=s.executeQuery("select * from player_cities where id=1")){assertTrue(rs.next());assertTrue(rs.getBoolean("legacy_naval"));assertEquals(190,rs.getInt("x"));}
            try(var rs=s.executeQuery("select * from player_cities where id=2")){assertTrue(rs.next());assertFalse(rs.getBoolean("legacy_naval"));}
            try(var rs=s.executeQuery("select * from marches where id=1")){assertTrue(rs.next());assertEquals(9000,rs.getLong("arrive_at"));assertNull(rs.getString("route_data"));}
        }
    }
}
