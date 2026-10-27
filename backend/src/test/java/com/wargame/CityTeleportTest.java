package com.wargame;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.*;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityTeleportTest extends BaseServiceTest {
    @Autowired CityTeleportService teleport;
    @Autowired CityService cities;
    @Autowired CityScope scope;
    @Autowired WorldTerrainService terrain;
    @Autowired WorldMapService map;
    @Autowired MarchRouteService routes;
    @Autowired PlayerItemRepository items;
    private Player player;
    private PlayerCity main;
    private WorldMap world;

    @BeforeEach void setup() {
        world=createTestWorld(); world.setSize(WorldConfig.WORLD_SIZE);
        world.setTerrainVersion(WorldTerrainService.TERRAIN_VERSION);
        world.setTerrainData("0".repeat(WorldConfig.SIZE*WorldConfig.SIZE)); worldMapRepository.save(world);
        player=createTestPlayer("teleport-player",30);
        main=city(player,0,10,10);
        player.setActiveCityId(main.getId()); playerRepository.save(player);
        items.save(new PlayerItem(null,player.getId(),CityTeleportService.NORMAL,10,System.currentTimeMillis()));
        items.save(new PlayerItem(null,player.getId(),CityTeleportService.ADVANCED,10,System.currentTimeMillis()));
    }
    private PlayerCity city(Player owner,int slot,int x,int y) {
        PlayerCity city=new PlayerCity();city.setOwnerId(owner.getId());city.setCitySlot(slot);city.setWorldId(world.getId());
        city.setName("城"+slot);city.setX(x);city.setY(y);city.setLevel(1);return playerCityRepository.saveAndFlush(city);
    }
    private int count(String item) { return items.findByPlayerIdAndItemKey(player.getId(),item).orElseThrow().getCount(); }
    private Map<String,Object> advanced(String field,int x,int y) { return teleport.teleport(player.getId(),CityTeleportService.ADVANCED,field,x,y); }
    private March march(Long id,int slot,String kind,String target,String action,boolean returning) {
        March m=new March();m.setPlayerId(id);m.setCitySlot(slot);m.setTargetKind(kind);m.setTargetId(target);
        m.setAction(action);m.setReturning(returning);m.setArriveAt(System.currentTimeMillis()+60000);return marchRepository.saveAndFlush(m);
    }

    @ParameterizedTest @ValueSource(strings={"northwest","northeast","southwest","southeast"})
    void randomTeleportStaysInsideChosenBattlefieldWithFourVacantCells(String field) {
        int minX=field.endsWith("east")?400:0,minY=field.startsWith("south")?400:0;
        var result=teleport.teleport(player.getId(),CityTeleportService.NORMAL,field,-1,-1);
        int x=(Integer)result.get("x"),y=(Integer)result.get("y");
        assertTrue(x>=minX&&x<=minX+398);assertTrue(y>=minY&&y<=minY+398);
        assertFalse(x==10&&y==10);assertEquals(9,count(CityTeleportService.NORMAL));
        assertEquals(10,count(CityTeleportService.ADVANCED));
        assertEquals(x,playerRepository.findById(player.getId()).orElseThrow().getCityPosX());
        assertEquals(y,playerCityRepository.findById(main.getId()).orElseThrow().getY());
        int testX = x >= WorldConfig.WORLD_SIZE - 2 ? x - 1 : x + 1;
        int testY = y >= WorldConfig.WORLD_SIZE - 2 ? y - 1 : y + 1;
        assertEquals("该范围与现有城市占地重叠",terrain.siteReason(world.getId(),terrain.current(),testX,testY,null));
    }

    @ParameterizedTest @ValueSource(strings={"northwest","northeast","southwest","southeast"})
    void advancedTeleportOpensAllBattlefieldsAndPreservesAssets(String field) {
        int x=(field.endsWith("east")?400:0)+50,y=(field.startsWith("south")?400:0)+60;
        createBuilding(player.getId(),"house",3);createArmyUnit(player.getId(),"infantry",23);
        long food=resourcesRepository.findByPlayerIdAndCitySlot(player.getId(),0).orElseThrow().getFood();
        assertEquals(true,teleport.preview(player.getId(),field,x,y).get("valid"));
        assertEquals(10,count(CityTeleportService.ADVANCED));
        advanced(field,x,y);
        assertEquals(main.getId(),playerCityRepository.findByOwnerIdAndCitySlot(player.getId(),0).orElseThrow().getId());
        assertEquals(9,count(CityTeleportService.ADVANCED));
        assertEquals(food,resourcesRepository.findByPlayerIdAndCitySlot(player.getId(),0).orElseThrow().getFood());
        assertEquals(23,armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(),0,"infantry").get(0).getCount());
        assertEquals(3,buildingRepository.findByPlayerIdAndCitySlotAndType(player.getId(),0,"house").get(0).getLevel());
        assertEquals("",terrain.siteReason(world.getId(),terrain.current(),10,10,null));
        var targets=(List<?>)map.chunk(player.getId(),x/16,y/16).get("targets");
        assertTrue(targets.stream().map(t->(Map<?,?>)t).anyMatch(t->main.getId().equals(t.get("id"))));
        NpcCity target=new NpcCity();target.setX(x+4);target.setY(y+5);
        var route=routes.plan(player.getId(),target,Map.of("infantry",1),false);
        assertEquals(List.of(List.of(x,y),List.of(x+4,y+5)),route.points());
        assertEquals(Math.hypot(4,5),route.distance());
    }

    @Test void wrongBattlefieldCrossingMidlineAndOuterBoundaryNeverConsume() {
        assertThrows(IllegalArgumentException.class,()->advanced("northwest",410,20));
        assertThrows(IllegalArgumentException.class,()->advanced("northwest",399,20));
        assertThrows(IllegalArgumentException.class,()->advanced("southeast",799,600));
        assertThrows(IllegalArgumentException.class,()->advanced("southeast",Integer.MAX_VALUE,600));
        assertThrows(IllegalArgumentException.class,()->advanced("invalid",40,40));
        assertThrows(IllegalArgumentException.class,()->teleport.teleport(player.getId(),CityTeleportService.ADVANCED,null,40,40));
        assertEquals(10,count(CityTeleportService.ADVANCED));
    }

    @Test void allFourCellsMustBeLandAndUnoccupiedEvenAfterPreview() {
        char[] mask=world.getTerrainData().toCharArray();mask[61*400+51]='1';
        world.setTerrainData(new String(mask));worldMapRepository.saveAndFlush(world);
        assertFalse((Boolean)teleport.preview(player.getId(),"northwest",50,60).get("valid"));
        assertThrows(IllegalArgumentException.class,()->advanced("northwest",50,60));
        assertEquals(true,teleport.preview(player.getId(),"northeast",700,100).get("valid"));
        createWildTile(world.getId(),"hill",701,101,1,Map.of(),100);
        assertThrows(IllegalArgumentException.class,()->advanced("northeast",700,100));
        Player enemy=createTestPlayer("teleport-enemy",30);city(enemy,0,710,110);
        assertThrows(IllegalArgumentException.class,()->advanced("northeast",709,109));
        Bandit bandit=new Bandit();bandit.setWorldId(world.getId());bandit.setX(721);bandit.setY(121);bandit.setName("寇");banditRepository.saveAndFlush(bandit);
        assertThrows(IllegalArgumentException.class,()->advanced("northeast",720,120));
        assertEquals(10,count(CityTeleportService.ADVANCED));
    }

    @Test void insufficientInventoryNeverChangesCoordinates() {
        items.findByPlayerIdAndItemKey(player.getId(),CityTeleportService.ADVANCED).orElseThrow().setCount(0);
        items.flush();assertThrows(IllegalArgumentException.class,()->advanced("southeast",700,600));
        assertEquals(10,playerCityRepository.findById(main.getId()).orElseThrow().getX());
    }

    @Test void randomTeleportWithNoLegalFootprintDoesNotConsumeOrFallBackToAnotherField() {
        world.setTerrainData("1".repeat(400*400));worldMapRepository.saveAndFlush(world);
        assertThrows(IllegalArgumentException.class,()->teleport.teleport(player.getId(),CityTeleportService.NORMAL,"southwest",null,null));
        assertEquals(10,count(CityTeleportService.NORMAL));
    }

    @Test void pinnedBranchMovesWithoutChangingMainCoordinatesOrAnotherTabsView() {
        PlayerCity branch=city(player,1,30,30);
        try(var ignored=scope.enter(branch)) { advanced("southeast",700,600); }
        Player persisted=playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(10,persisted.getCityPosX());assertEquals(10,persisted.getPosX());
        assertEquals(main.getId(),persisted.getActiveCityId());
        assertEquals(700,playerCityRepository.findById(branch.getId()).orElseThrow().getX());
        assertEquals(10,playerCityRepository.findById(main.getId()).orElseThrow().getX());
    }

    @ParameterizedTest @ValueSource(strings={"conquer","gather","transport","rebase","reinforce"})
    void unfinishedOutgoingActionsBlockTeleport(String action) {
        march(player.getId(),0,"wild","42",action,false);
        assertThrows(IllegalArgumentException.class,()->advanced("southwest",50,600));
        assertEquals(10,count(CityTeleportService.ADVANCED));
    }

    @Test void returningTroopsAlsoBlockButOtherCityTroopsDoNot() {
        March outgoing=march(player.getId(),0,"wild","42","conquer",true);
        assertThrows(IllegalArgumentException.class,()->advanced("southwest",50,600));
        marchRepository.delete(outgoing);marchRepository.flush();
        march(player.getId(),1,"wild","42","gather",false);
        advanced("southwest",50,600);assertEquals(9,count(CityTeleportService.ADVANCED));
    }

    @ParameterizedTest @ValueSource(strings={"conquer","scout","transport","rebase","reinforce"})
    void incomingActionsAndGarrisonsBlockTeleport(String action) {
        March incoming=march(999L,0,"player",main.getId().toString(),action,false);
        if(action.equals("reinforce")) { incoming.setStationed(true);marchRepository.saveAndFlush(incoming); }
        assertThrows(IllegalArgumentException.class,()->advanced("northeast",700,50));
        assertEquals(10,count(CityTeleportService.ADVANCED));
    }

    @Test void constructionAndSurrenderFreezeBlockTeleport() {
        main.setReadyAt(System.currentTimeMillis()+60000);playerCityRepository.saveAndFlush(main);
        assertThrows(IllegalArgumentException.class,()->advanced("southeast",700,600));
        main.setReadyAt(0L);main.setSurrenderOfferId(1L);main.setSurrenderFrozenAt(1L);playerCityRepository.saveAndFlush(main);
        assertThrows(IllegalArgumentException.class,()->advanced("southeast",700,600));
        assertEquals(10,count(CityTeleportService.ADVANCED));
    }

    @Test void foundingInOtherBattlefieldsUsesSameFourCellRules() {
        player.setMilitaryRank(7);playerRepository.save(player);giveResources(player.getId(),100000,100000,100000,100000,100000);
        assertEquals(true,cities.site(player.getId(),650,650).get("valid"));
        PlayerCity founded=cities.foundAt(player.getId(),null,650,650,"东南分城");
        assertEquals(650,founded.getX());assertFalse(terrain.siteReason(world.getId(),terrain.current(),651,651,null).isEmpty());
    }
}
