package com.wargame;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.*;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.ArmyProductionQueueRepository;
import com.wargame.repository.ConstructionRepository;
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
    @Autowired ArmyProductionQueueRepository production;
    @Autowired ConstructionRepository construction;
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

    @Test void readinessListsSimultaneousBlockersWithoutConsumingItemsOrMovingCity() {
        main.setReadyAt(System.currentTimeMillis() + 60000);
        main.setSurrenderOfferId(1L); main.setSurrenderFrozenAt(1L);
        playerCityRepository.saveAndFlush(main);
        March departure = march(player.getId(), 0, "wild", "42", "gather", false);
        March arrival = march(999L, 0, "player", main.getId().toString(), "reinforce", false);
        Map<String, Object> result = teleport.readiness(player.getId());
        assertEquals(false, result.get("ready"));
        List<?> checks = (List<?>) result.get("checks");
        Set<Object> blocked = new HashSet<>();
        checks.forEach(value -> { Map<?, ?> check = (Map<?, ?>) value; if (Boolean.FALSE.equals(check.get("passed"))) blocked.add(check.get("key")); });
        assertEquals(Set.of("construction", "surrender", "departures", "arrivals"), blocked);
        assertEquals(10, count(CityTeleportService.NORMAL));
        assertEquals(10, count(CityTeleportService.ADVANCED));
        assertEquals(10, playerCityRepository.findById(main.getId()).orElseThrow().getX());
        main.setReadyAt(0L); main.setSurrenderOfferId(null); main.setSurrenderFrozenAt(0L);
        playerCityRepository.saveAndFlush(main);
        marchRepository.deleteAll(List.of(departure, arrival)); marchRepository.flush();
        assertEquals(true, teleport.readiness(player.getId()).get("ready"));
        assertEquals(true, teleport.preview(player.getId(), "southeast", 700, 600).get("valid"));
    }

    @Test void foundingInOtherBattlefieldsUsesSameFourCellRules() {
        player.setMilitaryRank(7);playerRepository.save(player);giveResources(player.getId(),100000,100000,100000,100000,100000);
        assertEquals(true,cities.site(player.getId(),650,650).get("valid"));
        PlayerCity founded=cities.foundAt(player.getId(),null,650,650,"东南分城");
        assertEquals(650,founded.getX());assertFalse(terrain.siteReason(world.getId(),terrain.current(),651,651,null).isEmpty());
    }

    private void navalAsset(String kind, int slot) {
        if (kind.equals("fleet")) {
            ArmyUnit unit=createArmyUnit(player.getId(),"destroyer",2);
            unit.setCitySlot(slot);armyUnitRepository.saveAndFlush(unit);
        } else if (kind.equals("queue") || kind.equals("finishedQueue")) {
            ArmyProductionQueue job=new ArmyProductionQueue();job.setPlayerId(player.getId());job.setCitySlot(slot);
            job.setUnitType("destroyer");job.setBuildingType("shipyard");job.setUnitCount(2);
            job.setStartedAt(1L);job.setFinishesAt(kind.equals("finishedQueue")?2L:System.currentTimeMillis()+60000);
            job.setDurationSeconds(60);job.setSpeedMultiplier(1.0);production.saveAndFlush(job);
        } else if (kind.endsWith("Construction")) {
            Construction job=new Construction();job.setPlayerId(player.getId());job.setCitySlot(slot);
            job.setBuildingType(kind.startsWith("port")?"port":"shipyard");job.setTargetLevel(1);
            job.setSlot(0);job.setStartAt(1L);job.setFinishAt(System.currentTimeMillis()+60000);
            construction.saveAndFlush(job);
        } else {
            Building building=createBuilding(player.getId(),kind,1);
            building.setCitySlot(slot);buildingRepository.saveAndFlush(building);
        }
    }

    /** 人工海岸保证内陆与沿海落点确定，避免普通传送的随机结果掩盖错误。 */
    private void coast() {
        char[] mask="0".repeat(400*400).toCharArray();
        for(int y=0;y<400;y++)for(int x=200;x<400;x++)mask[y*400+x]='1';
        world.setTerrainData(new String(mask));worldMapRepository.saveAndFlush(world);
    }

    @ParameterizedTest @ValueSource(strings={"fleet","queue","finishedQueue","port","shipyard","portConstruction","shipyardConstruction"})
    void navalAssetsRejectInlandPreviewAndDirectSubmitWithoutConsuming(String asset) {
        navalAsset(asset,0);
        // 历史资格不能绕过目的地地形限制。
        main.setLegacyNaval(true);playerCityRepository.saveAndFlush(main);
        var preview=teleport.preview(player.getId(),"northwest",50,60);
        assertEquals(false,preview.get("valid"));assertTrue(preview.get("reason").toString().contains("沿海或海岛"));
        assertThrows(IllegalArgumentException.class,()->advanced("northwest",50,60));
        assertEquals(10,count(CityTeleportService.ADVANCED));
        assertEquals(10,playerCityRepository.findById(main.getId()).orElseThrow().getX());
    }

    @ParameterizedTest @ValueSource(strings={"northwest","northeast","southwest","southeast"})
    void fleetCanTeleportToCoastInEveryBattlefield(String field) {
        coast();navalAsset("fleet",0);
        int x=field.endsWith("east")?600:198,y=field.startsWith("south")?600:60;
        assertEquals(true,teleport.preview(player.getId(),field,x,y).get("valid"));
        advanced(field,x,y);
        assertEquals(9,count(CityTeleportService.ADVANCED));
        assertEquals(2,armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(),0,"destroyer").get(0).getCount());
    }

    @ParameterizedTest @ValueSource(strings={"northwest","northeast","southwest","southeast"})
    void fleetCanTeleportToIslandInteriorInEveryBattlefield(String field) {
        world.setTerrainData(WorldTerrainService.generate());worldMapRepository.saveAndFlush(world);
        navalAsset("fleet",0);
        int x=field.endsWith("east")?434:364,y=field.startsWith("south")?642:156;
        assertFalse(WorldTerrainService.coastal(terrain.current(),x,y,2));
        assertTrue(WorldTerrainService.isIsland(terrain.current(),x,y));
        assertEquals(true,teleport.preview(player.getId(),field,x,y).get("valid"));
        advanced(field,x,y);assertEquals(9,count(CityTeleportService.ADVANCED));
    }

    @ParameterizedTest @ValueSource(strings={"fleet","queue","finishedQueue","port","shipyard","portConstruction","shipyardConstruction"})
    void randomTeleportFiltersForNavalAssetsAndNeverFallsBackToInland(String asset) {
        navalAsset(asset,0);
        // 只保留一处空闲内陆落点，沿海边缘用野地占住；不能退而选择这个内陆落点。
        char[] mask="1".repeat(400*400).toCharArray();
        for(int y=10;y<12;y++)for(int x=10;x<12;x++)mask[y*400+x]='0';
        for(int y=59;y<63;y++)for(int x=49;x<53;x++) {
            mask[y*400+x]='0';
            if(x==49||x==52||y==59||y==62)createWildTile(world.getId(),"hill",x,y,1,Map.of(),100);
        }
        world.setTerrainData(new String(mask));worldMapRepository.saveAndFlush(world);
        assertEquals("",terrain.siteReason(world.getId(),terrain.current(),50,60,null));
        assertFalse(WorldTerrainService.coastal(terrain.current(),50,60,2));
        assertFalse(WorldTerrainService.isIsland(terrain.current(),50,60));
        assertThrows(IllegalArgumentException.class,()->teleport.teleport(player.getId(),CityTeleportService.NORMAL,"northwest",null,null));
        assertEquals(10,count(CityTeleportService.NORMAL));
        assertEquals(10,playerCityRepository.findById(main.getId()).orElseThrow().getX());
        coast();
        var result=teleport.teleport(player.getId(),CityTeleportService.NORMAL,"northwest",null,null);
        int x=(Integer)result.get("x"),y=(Integer)result.get("y");
        assertTrue(WorldTerrainService.isIsland(terrain.current(),x,y)||WorldTerrainService.coastal(terrain.current(),x,y,2));
        assertEquals(9,count(CityTeleportService.NORMAL));
    }

    @ParameterizedTest @ValueSource(strings={"fleet","queue","port","shipyard","portConstruction","shipyardConstruction"})
    void otherCityNavalAssetsDoNotRestrictSelectedLandCity(String asset) {
        PlayerCity branch=city(player,1,30,30);navalAsset(asset,1);
        assertEquals(true,teleport.preview(player.getId(),"northwest",50,60).get("valid"));
        advanced("northwest",50,60);
        try(var ignored=scope.enter(branch)) {
            assertEquals(false,teleport.preview(player.getId(),"northwest",70,80).get("valid"));
            assertThrows(IllegalArgumentException.class,()->advanced("northwest",70,80));
        }
        assertEquals(9,count(CityTeleportService.ADVANCED));
    }

    @Test void landCityWithEmptyNavalRowsAndLegacyFlagStillTeleportsFreely() {
        main.setLegacyNaval(true);playerCityRepository.saveAndFlush(main);
        createArmyUnit(player.getId(),"destroyer",0);createArmyUnit(player.getId(),"infantry",5);
        createBuilding(player.getId(),"port",0);createBuilding(player.getId(),"shipyard",0);
        assertEquals(true,teleport.preview(player.getId(),"northwest",50,60).get("valid"));
        advanced("northwest",50,60);
        teleport.teleport(player.getId(),CityTeleportService.NORMAL,"northwest",null,null);
        assertEquals(9,count(CityTeleportService.ADVANCED));assertEquals(9,count(CityTeleportService.NORMAL));
    }

    @Test void submitRechecksAssetsAddedAfterSuccessfulPreview() {
        assertEquals(true,teleport.preview(player.getId(),"northwest",50,60).get("valid"));
        navalAsset("queue",0);
        assertThrows(IllegalArgumentException.class,()->advanced("northwest",50,60));
        assertEquals(10,count(CityTeleportService.ADVANCED));
    }
}
