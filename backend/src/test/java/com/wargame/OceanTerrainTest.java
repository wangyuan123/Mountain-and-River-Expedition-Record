package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.service.*;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.wargame.service.WorldTerrainService.*;

class OceanTerrainTest extends BaseServiceTest {
    @Autowired WorldTerrainService terrain;
    @Autowired CityService cities;
    @Autowired MarchRouteService routes;
    @Autowired CityScope scope;
    private WorldMap coastWorld(){
        WorldMap w=createTestWorld();char[] mask=new char[SIZE*SIZE];Arrays.fill(mask,'0');
        for(int y=0;y<SIZE;y++)for(int x=100;x<SIZE;x++)mask[y*SIZE+x]='1';
        for(int y=35;y<61;y++)for(int x=150;x<160;x++)mask[y*SIZE+x]='0';
        w.setTerrainData(new String(mask));return worldMapRepository.save(w);
    }
    private Player player(String name){Player p=createTestPlayer(name,30);p.setMilitaryRank(7);p.setCityPosX(98);p.setCityPosY(40);playerRepository.save(p);giveResources(p.getId(),100000,100000,100000,100000,100000);return p;}
    private PlayerCity city(Player p,WorldMap w,int x,int y){
        PlayerCity c=new PlayerCity();c.setWorldId(w.getId());c.setOwnerId(p.getId());c.setCitySlot(0);c.setName("港城");c.setX(x);c.setY(y);c.setLevel(1);return playerCityRepository.save(c);
    }
    private NpcCity createNpcCity(Long world,String name,int x,int y,int level,Map<String,Integer> army){
        NpcCity c=new NpcCity();c.setWorldId(world);c.setName(name);c.setX(x);c.setY(y);c.setLevel(level);c.setArmy(JsonUtil.toJson(army));c.setForts("{}");c.setResources("{}");c.setDefeated(false);return npcCityRepository.save(c);
    }
    @Test void generatedOceanIsStableAndProtectsEveryExistingAsset(){
        WorldMap w=createTestWorld();Player p=player("ocean-legacy");PlayerCity c=city(p,w,190,110);
        createWildTile(w.getId(),"forest",185,112,1,Map.of(),100);
        String base=generate();long count=base.chars().filter(v->v=='1').count();assertTrue(count>SIZE*SIZE*.20&&count<SIZE*SIZE*.27,"sea ratio "+count/40000.0);
        String mask=terrain.ensure();assertFalse(sea(mask,190,110));assertFalse(sea(mask,191,111));assertFalse(sea(mask,185,112));
        assertEquals(mask,terrain.ensure());assertEquals(mask,worldMapRepository.findById(w.getId()).orElseThrow().getTerrainData());
        assertEquals(190,playerCityRepository.findById(c.getId()).orElseThrow().getX());
        assertEquals(1,wildTileRepository.findByWorldId(w.getId()).size());
        List<Integer> all=new ArrayList<>();for(int i=0;i<mask.length();i++)if(mask.charAt(i)=='1')all.add(i);
        assertFalse(MarchRouteService.path(mask,List.of(all.get(0)),List.of(all.get(all.size()-1)),true).isEmpty());
    }
    @Test void existingTerrainOnlyGainsIslandLandAndRetainsPreviousCoast(){
        WorldMap world=createTestWorld();
        char[] old=generate().toCharArray();
        int islandCell=islandCells(new String(old)).get(3).get(0);
        old[islandCell]='1';
        old[42*SIZE+198]='1';
        world.setTerrainData(new String(old));
        world.setTerrainVersion(1);
        worldMapRepository.save(world);
        String upgraded=terrain.ensure();
        assertEquals('0',upgraded.charAt(islandCell));
        assertEquals('1',upgraded.charAt(42*SIZE+198));
        assertEquals(2,worldMapRepository.findById(world.getId()).orElseThrow().getTerrainVersion());
        assertEquals(upgraded,terrain.ensure());
    }
    @Test void coastalFoundingReservesFourCellsAndKeepsResourceRules(){
        WorldMap w=coastWorld();Player p=player("coastal-founder");city(p,w,98,40);
        assertEquals(true,cities.site(p.getId(),98,45).get("valid"));
        PlayerCity c=cities.foundAt(p.getId(),null,98,45,"海湾城");assertEquals(98,c.getX());assertTrue(terrain.coastal(c));assertFalse(c.isLegacyNaval());
        assertEquals(90000,resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold());
        assertEquals(false,cities.site(p.getId(),98,46).get("valid"));
        assertFalse(terrain.siteReason(w.getId(),w.getTerrainData(),97,44,null,false).isEmpty());
        assertThrows(IllegalArgumentException.class,()->cities.foundAt(p.getId(),null,99,48,"海中"));
        assertThrows(IllegalArgumentException.class,()->cities.foundAt(p.getId(),null,70,48,"内陆"));
        assertThrows(IllegalArgumentException.class,()->cities.foundAt(p.getId(),null,199,199,"边界"));
        assertEquals(90000,resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold());
    }
    @Test void foundingRejectsWildNpcAndEdgeCityOverlap(){
        WorldMap w=coastWorld();Player p=player("coastal-obstacles");city(p,w,98,40);
        createWildTile(w.getId(),"forest",99,46,1,Map.of(),100);
        assertEquals(false,cities.site(p.getId(),98,45).get("valid"));
        createNpcCity(w.getId(),"港口营地",98,50,1,Map.of());
        assertEquals(false,cities.site(p.getId(),98,50).get("valid"));
        var balance=resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow();balance.setGold(1);resourcesRepository.save(balance);
        assertThrows(IllegalArgumentException.class,()->cities.foundAt(p.getId(),null,98,55,"缺钱"));assertEquals(100000,balance.getSteel());
    }
    @Test void fleetsUseConnectedWaterAndAirliftChecksActualTroopPopulation(){
        WorldMap w=coastWorld();Player p=player("ocean-route");city(p,w,98,40);createBuilding(p.getId(),"port",1);
        NpcCity target=createNpcCity(w.getId(),"海岛",150,40,1,Map.of());
        var route=routes.plan(p.getId(),target,Map.of("destroyer",1),false);assertEquals("sea",route.mode());
        assertEquals(52,route.distance());assertTrue(route.points().size()>=2);
        NpcCity inland=createNpcCity(w.getId(),"内陆",50,40,1,Map.of());
        assertThrows(IllegalArgumentException.class,()->routes.plan(p.getId(),inland,Map.of("destroyer",1),false));
        assertThrows(IllegalArgumentException.class,()->routes.plan(p.getId(),target,Map.of("infantry",81,"transport",1),false));
        var lift=routes.plan(p.getId(),target,Map.of("infantry",80,"transport",1),false);assertEquals("airlift",lift.mode());assertEquals(0,lift.cargoLimit());
        var mixed=routes.plan(p.getId(),target,Map.of("infantry",10,"transport",1,"destroyer",1),false);assertEquals("sea",mixed.mode());assertEquals(70,mixed.cargoLimit());
        assertEquals("land",routes.plan(p.getId(),inland,Map.of("infantry",1),false).mode());
        assertEquals("air",routes.plan(p.getId(),inland,Map.of("scout",1),false).mode());
    }
    @Test void legacyInlandFleetRetainsAnOutletButNewInlandPortIsRejected(){
        WorldMap w=coastWorld();Player p=player("legacy-fleet");p.setCityPosX(20);playerRepository.save(p);PlayerCity c=city(p,w,20,40);
        NpcCity target=createNpcCity(w.getId(),"海岛",150,40,1,Map.of());
        c.setLegacyNaval(true);playerCityRepository.save(c);
        var route=routes.plan(p.getId(),target,Map.of("destroyer",1),false);assertEquals("sea_supply",route.mode());assertEquals(130,route.distance());
        c.setLegacyNaval(false);playerCityRepository.save(c);
        assertThrows(IllegalArgumentException.class,()->routes.plan(p.getId(),target,Map.of("destroyer",1),false));
        createBuilding(p.getId(),"command",2);
        var build=buildService.upgrade(p.getId(),"port",0);assertEquals(false,build.get("success"));assertTrue(build.get("message").toString().contains("沿海"));
    }
    @Test void failedDispatchDoesNotDeductAndSuccessfulRouteSurvivesReturn(){
        WorldMap w=coastWorld();Player p=player("route-save");city(p,w,98,40);createBuilding(p.getId(),"port",1);
        createArmyUnit(p.getId(),"destroyer",10);createArmyUnit(p.getId(),"infantry",10);
        NpcCity inland=createNpcCity(w.getId(),"内陆",50,40,1,Map.of());
        var invalid=new DispatchRequest("npc",inland.getId(),"conquer",Map.of("destroyer",1),null,Map.of("gold",100));
        assertThrows(IllegalArgumentException.class,()->marchService.createDispatch(p.getId(),invalid));
        assertEquals(10,armyUnitRepository.findByPlayerIdAndCitySlotAndType(p.getId(),0,"destroyer").get(0).getCount());
        assertEquals(100000,resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold());
        NpcCity island=createNpcCity(w.getId(),"海岛",150,40,1,Map.of());
        var request=new DispatchRequest("npc",island.getId(),"conquer",Map.of("destroyer",1),null,Map.of());
        var preview=marchService.previewRoute(p.getId(),request);March march=marchService.createDispatch(p.getId(),request);
        assertEquals(preview.get("distance"),march.getDistance());assertEquals("sea",march.getRouteMode());
        assertEquals(98,JsonUtil.parseTree(march.getRouteData()).get(0).get(0).asInt());
        marchService.cancelMarch(p.getId(),march.getId());assertTrue(march.getReturning());
        assertEquals(150,JsonUtil.parseTree(march.getRouteData()).get(0).get(0).asInt());
    }
}
