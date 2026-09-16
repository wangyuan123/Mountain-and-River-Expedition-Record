package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.service.WorldMapService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldMapServiceTest extends BaseServiceTest {
    @Autowired WorldMapService maps;
    @Test @SuppressWarnings("unchecked")
    void fixedChunkIncludesOnlyItsCellsAndNoEnemyIntelligence() {
        Player viewer = createTestPlayer("chunk-viewer",30);
        long world = createTestWorld().getId();
        WildTile first = createWildTile(world,"forest",15,15,2,Map.of("infantry",999),500);
        createWildTile(world,"forest",16,15,2,Map.of("infantry",888),900);
        first.setScouted(true);wildTileRepository.save(first);
        var targets=(List<Map<String,Object>>)maps.chunk(viewer.getId(),0,0).get("targets");
        assertEquals(1,targets.size());assertEquals(first.getId(),targets.get(0).get("id"));
        assertFalse(targets.get(0).containsKey("garrison"));assertFalse(targets.get(0).containsKey("totalRes"));
        var detail=maps.target(viewer.getId(),"wild",first.getId());
        assertFalse(detail.containsKey("garrison"),"A global scouted flag must not reveal another player's report");
        assertFalse(detail.containsKey("totalRes"));
        assertThrows(IllegalArgumentException.class,()->maps.chunk(viewer.getId(),-1,0));
        assertThrows(IllegalArgumentException.class,()->maps.chunk(viewer.getId(),13,0));
    }
    @Test @SuppressWarnings("unchecked")
    void ownershipIsViewerSpecificAndStableIdDetailAllowsGathering() {
        Player a=createTestPlayer("owner-a",30),b=createTestPlayer("viewer-b",30);
        long world=createTestWorld().getId();
        WildTile t=createWildTile(world,"grainfield",199,199,1,Map.of("infantry",10),1000);
        t.setOccupied(true);t.setOccupiedBy(a.getId());t.setMined(200);wildTileRepository.save(t);
        var own=maps.target(a.getId(),"wild",t.getId());assertEquals(true,own.get("occupied"));assertEquals(1000,own.get("totalRes"));assertEquals(200,own.get("mined"));
        var other=maps.target(b.getId(),"wild",t.getId());assertEquals(false,other.get("occupied"));assertEquals(true,other.get("claimed"));assertFalse(other.containsKey("garrison"));
        assertEquals(a.getId(),other.get("ownerId"));assertEquals(a.getUsername(),other.get("ownerName"));
        assertEquals(a.getUsername(),own.get("ownerName"));
        var edge=(List<Map<String,Object>>)maps.chunk(b.getId(),12,12).get("targets");assertEquals(t.getId(),edge.get(0).get("id"));
        assertEquals(a.getUsername(),edge.get(0).get("ownerName"));
        assertFalse(edge.get(0).containsKey("garrison"));assertFalse(edge.get(0).containsKey("totalRes"));
        t.setOccupiedBy(b.getId());wildTileRepository.save(t);
        assertEquals(b.getUsername(),maps.target(a.getId(),"wild",t.getId()).get("ownerName"));
        var changed=(List<Map<String,Object>>)maps.chunk(a.getId(),12,12).get("targets");
        assertEquals(b.getUsername(),changed.get(0).get("ownerName"));
        // Unclaimed wilds must not retain the previous owner's public identity.
        t.setOccupied(false);wildTileRepository.save(t);
        var unclaimed=maps.target(a.getId(),"wild",t.getId());
        assertEquals(false,unclaimed.get("claimed"));assertFalse(unclaimed.containsKey("ownerId"));assertFalse(unclaimed.containsKey("ownerName"));
        var released=(List<Map<String,Object>>)maps.chunk(a.getId(),12,12).get("targets");
        assertFalse(released.get(0).containsKey("ownerName"));
        assertThrows(IllegalArgumentException.class,()->maps.target(a.getId(),"invalid",t.getId()));
    }
    @Test
    void playerDetailHasViewerSpecificWarStatusWithoutPrivateArmy() {
        Player a=createTestPlayer("map-attacker",30),b=createTestPlayer("map-defender",30),c=createTestPlayer("map-observer",30);
        long world=createTestWorld().getId();
        b.setWarAgainstId(a.getId());b.setWarAt(1234L);b.setWarEndAt(9999L);playerRepository.save(b);
        PlayerCity city=new PlayerCity();city.setWorldId(world);city.setOwnerId(b.getId());city.setName("defender city");city.setX(33);city.setY(34);city.setCitySlot(0);city.setForts("{\"bunker\":500}");city.setResources("{\"gold\":9999}");playerCityRepository.save(city);
        var attacker=maps.target(a.getId(),"player",city.getId());
        assertEquals(1234L,attacker.get("warAt"));assertFalse(attacker.containsKey("forts"));assertFalse(attacker.containsKey("resources"));assertFalse(attacker.containsKey("army"));
        assertEquals(0L,maps.target(c.getId(),"player",city.getId()).get("warAt"));
        assertEquals(true,maps.target(b.getId(),"player",city.getId()).get("selfCity"));
        assertThrows(IllegalArgumentException.class,()->maps.target(a.getId(),"simulated_npc",city.getId()));
    }
}
