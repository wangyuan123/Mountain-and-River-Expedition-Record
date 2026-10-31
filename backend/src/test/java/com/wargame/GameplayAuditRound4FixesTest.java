package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用正常道具、加点、经济事件和外交流程验证本轮修复。 */
class GameplayAuditRound4FixesTest extends BaseServiceTest {
    @Autowired CityScope scope;
    @Autowired PlayerCityRepository cities;
    @Autowired PlayerItemRepository items;
    @Autowired DepotService depot;
    @Autowired OfficerService officers;
    @Autowired ConstructionRepository construction;
    @Autowired TechResearchQueueRepository research;
    @Autowired GuildRelationService relations;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository members;
    @Autowired PersonalWarService wars;
    @Autowired BuildService builds;
    @Autowired VipService vip;
    @Autowired TechService techService;

    private PlayerCity city(Player p,int slot) {
        PlayerCity c=new PlayerCity(); c.setWorldId(1L); c.setOwnerId(p.getId()); c.setCitySlot(slot);
        c.setName("测试城"+slot); c.setX(20+slot*3); c.setY(30); c.setReadyAt(0L); c.setLastTick(p.getLastTick());
        return cities.saveAndFlush(c);
    }

    @Test
    void branchGiftCreditsSharedWalletAndKeepsCityResourcesLocal() {
        Player p=createTestPlayer("gift-owner",30); PlayerCity branch=city(p,1);
        Resources local=new Resources();local.setPlayerId(p.getId());local.setCitySlot(1);local.setGold(0);local.setFood(0);local.setSteel(0);local.setOil(0);local.setRare(0);
        resourcesRepository.save(local);
        String gift=DepotService.STAGE_GIFTS.keySet().stream().sorted().findFirst().orElseThrow();
        // 礼包定义由服务持有，测试只验证正常奖励去向。
        items.saveAndFlush(new PlayerItem(null,p.getId(),gift,1,GameClock.now()));
        try(var ignored=scope.enter(branch)) { assertEquals(true,depot.useItem(p.getId(),gift,null,null).get("success")); }
        assertTrue(scope.wallet(p.getId()).getDiamond()>0);
        assertTrue(resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),1).orElseThrow().getFood()>0);
        assertEquals(0,Objects.requireNonNullElse(resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),1).orElseThrow().getDiamond(),0));
    }

    @Test
    void experienceBooksUseTheSameManualLevelUpContract() {
        Player p=createTestPlayer("book-owner",30);
        Officer a=createOfficer(p.getId(),"idle",40,50,60), b=createOfficer(p.getId(),"idle",40,50,60);
        items.saveAndFlush(new PlayerItem(null,p.getId(),"expBook",2,GameClock.now()));
        assertEquals(true,depot.useItem(p.getId(),"expBook",a.getId(),null).get("success"));
        assertEquals(true,officers.useExpBook(p.getId(),b.getId(),"expBook",1).get("success"));
        a=officerRepository.findById(a.getId()).orElseThrow(); b=officerRepository.findById(b.getId()).orElseThrow();
        assertEquals(a.getExp(),b.getExp()); assertEquals(a.getLevel(),b.getLevel()); assertEquals(a.getAttrPoints(),b.getAttrPoints());
    }

    @Test
    void resetRefundsActualAssignmentsAndPreservesOfficerAptitude() {
        Player p=createTestPlayer("training-owner",30); giveResources(p.getId(),1000,1000,1000,1000,50000);
        Officer o=createOfficer(p.getId(),"idle",115,80,67,91); o.setStar(5);o.setAttrPoints(20);officerRepository.saveAndFlush(o);
        assertEquals(true,officers.assignAttr(p.getId(),o.getId(),"logistics",10).get("success"));
        assertEquals(true,officers.assignAttr(p.getId(),o.getId(),"military",3).get("success"));
        assertEquals(true,officers.wash(p.getId(),o.getId()).get("success"));
        o=officerRepository.findById(o.getId()).orElseThrow();
        assertEquals(115,o.getMilitary());assertEquals(80,o.getDefense());assertEquals(67,o.getLogistics());assertEquals(91,o.getKnowledge());
        assertEquals(20,o.getAttrPoints());assertEquals(0,o.assignedPoints());
    }

    @Test
    void resourceConstructionStartsProducingAtActualCompletion() throws Exception {
        Player p=createTestPlayer("build-income",30); long start=p.getLastTick();
        Resources stock=resourcesRepository.findByPlayerId(p.getId()).orElseThrow();stock.setSteel(0);resourcesRepository.save(stock);
        Construction q=new Construction();q.setPlayerId(p.getId());q.setCitySlot(0);q.setBuildingType("refinery");q.setSlot(0);q.setTargetLevel(1);q.setStartAt(start);q.setFinishAt(start+1800000);construction.saveAndFlush(q);
        try(var clock=GameClock.use(()->start+3600000)) {
            builds.completeUpgrade(p.getId(),start+3600000);
            tickService.settleEconomyBeforeChange(p.getId());
            assertEquals((long)Math.floor(tickService.produceOf(p.getId(),"refinery")*.5),resourcesRepository.findByPlayerId(p.getId()).orElseThrow().getSteel());
        }
    }

    @Test
    void expiredShieldStillCreditsItsEffectiveProductionInterval() throws Exception {
        Player p=createTestPlayer("shield-income",30);long start=p.getLastTick();createBuilding(p.getId(),"refinery",1);
        Resources stock=resourcesRepository.findByPlayerId(p.getId()).orElseThrow();stock.setSteel(0);resourcesRepository.save(stock);
        double base=tickService.produceOf(p.getId(),"refinery");createCityState(p.getId(),"peace",start+1800000,0);
        try(var clock=GameClock.use(()->start+3600000)) {
            tickService.settleEconomyBeforeChange(p.getId());
            assertEquals((long)Math.floor(base*1.05+1e-9),resourcesRepository.findByPlayerId(p.getId()).orElseThrow().getSteel());
        }
    }

    @Test
    void sharedTechnologySplitsBothCitiesAtResearchCompletion() throws Exception {
        Player p=createTestPlayer("research-income",30);long start=p.getLastTick();PlayerCity branch=city(p,1);
        createBuilding(p.getId(),"refinery",1);double base=tickService.produceOf(p.getId(),"refinery");
        Building b=new Building();b.setPlayerId(p.getId());b.setCitySlot(1);b.setType("refinery");b.setLevel(1);buildingRepository.save(b);
        resourcesRepository.findByPlayerId(p.getId()).orElseThrow().setSteel(0);
        Resources local=new Resources();local.setPlayerId(p.getId());local.setCitySlot(1);local.setSteel(0);local.setGold(0);local.setFood(0);local.setOil(0);local.setRare(0);resourcesRepository.save(local);
        TechResearchQueue q=new TechResearchQueue();q.setPlayerId(p.getId());q.setCitySlot(0);q.setTechType("prod_steel");q.setTargetLevel(1);q.setStartedAt(start);q.setFinishesAt(start+1800000);q.setDurationSeconds(1800);research.saveAndFlush(q);
        try(var clock=GameClock.use(()->start+3600000)) {
            techService.settleCompletedResearch(p.getId(),start+3600000);
            tickService.settleEconomyBeforeChange(p.getId());
            try(var selected=scope.enter(branch)) {tickService.settleEconomyBeforeChange(p.getId());}
            long expected=(long)Math.floor((base+tickService.produceOf(p.getId(),"refinery"))*.5+1e-9);
            assertEquals(expected,resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getSteel());
            assertEquals(expected,resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),1).orElseThrow().getSteel());
        }
    }

    @Test
    void mayorAppointmentSettlesOldProductionBeforeChangingAttributes() throws Exception {
        Player p=createTestPlayer("mayor-income",30); long start=p.getLastTick();
        createBuilding(p.getId(),"refinery",1);
        Resources stock=resourcesRepository.findByPlayerId(p.getId()).orElseThrow(); stock.setSteel(0);resourcesRepository.save(stock);
        double before=tickService.produceOf(p.getId(),"refinery");
        Officer mayor=createOfficer(p.getId(),"idle",40,100,100);
        try(var clock=GameClock.use(()->start+1800000)) { officers.appoint(p.getId(),mayor.getId(),"mayor"); }
        double after=tickService.produceOf(p.getId(),"refinery");
        try(var clock=GameClock.use(()->start+3600000)) { tickService.settleEconomyBeforeChange(p.getId()); }
        assertEquals((long)Math.floor((before+after)*.5+1e-9),resourcesRepository.findByPlayerId(p.getId()).orElseThrow().getSteel());
    }

    @Test
    void vipAmnestyCountsMainCityOnceAndClearsSentimentFractions() {
        Player p=createTestPlayer("vip-amnesty-count",30);p.setVipLevel(6);p.setMoraleAccrual(.5);p.setResentmentAccrual(.6);playerRepository.save(p);
        city(p,0);PlayerCity branch=city(p,1);branch.setMoraleAccrual(.7);branch.setResentmentAccrual(.8);cities.save(branch);
        var result=vip.allCitiesAppease(p.getId(),"free_amnesty");
        assertEquals(2,result.get("affectedCities"));
        p=playerRepository.findById(p.getId()).orElseThrow();branch=cities.findById(branch.getId()).orElseThrow();
        assertEquals(100,p.getMorale());assertEquals(0,p.getResentmentAccrual());assertEquals(0,p.getMoraleAccrual());
        assertEquals(100,branch.getMorale());assertEquals(0,branch.getResentmentAccrual());assertEquals(0,branch.getMoraleAccrual());
    }

    private Guild guild(Player p,String name) {
        Guild g=new Guild();g.setName(name);g.setIcon("flag");g.setNotice("");g.setLeaderPlayerId(p.getId());g.setCreatedAt(System.currentTimeMillis());g=guilds.saveAndFlush(g);
        members.saveAndFlush(new GuildMember(null,g.getId(),p.getId(),"leader",System.currentTimeMillis()));return g;
    }

    @Test
    void acceptedFriendshipBecomesSharedAndEitherPartyCanEndIt() {
        Player a=createTestPlayer("diplomat-a",30),b=createTestPlayer("diplomat-b",30);
        Guild ga=guild(a,"甲军团"),gb=guild(b,"乙军团");
        relations.updateRelation(a.getId(),gb.getId(),"friendly");
        assertEquals("neutral",relations.relationshipBetweenPlayers(a.getId(),b.getId()));
        assertEquals("outgoing",relations.listRelations(ga.getId()).get(0).get("friendshipInvitation"));
        assertEquals("incoming",relations.listRelations(gb.getId()).get(0).get("friendshipInvitation"));
        relations.respondFriendship(b.getId(),ga.getId(),"accept");
        assertEquals("friendly",relations.relationshipBetweenPlayers(a.getId(),b.getId()));
        relations.updateRelation(b.getId(),ga.getId(),"neutral");
        assertEquals("neutral",relations.relationshipBetweenPlayers(a.getId(),b.getId()));
    }

    @Test
    void invitationsCanBeWithdrawnOrDeclinedWithoutChangingCurrentRelation() {
        Player a=createTestPlayer("invite-a",30),b=createTestPlayer("invite-b",30);
        Guild ga=guild(a,"丙军团"),gb=guild(b,"丁军团");
        relations.updateRelation(a.getId(),gb.getId(),"friendly");relations.respondFriendship(a.getId(),gb.getId(),"withdraw");
        relations.updateRelation(a.getId(),gb.getId(),"friendly");relations.respondFriendship(b.getId(),ga.getId(),"reject");
        assertEquals("neutral",relations.relationshipBetweenPlayers(a.getId(),b.getId()));
    }

    @Test
    void friendshipInvitationKeepsExistingHostilityUntilAccepted() {
        Player a=createTestPlayer("hostile-invite-a",30),b=createTestPlayer("hostile-invite-b",30);
        Guild ga=guild(a,"戊军团"),gb=guild(b,"己军团");
        relations.updateRelation(a.getId(),gb.getId(),"hostile");
        relations.updateRelation(a.getId(),gb.getId(),"friendly");
        assertEquals("hostile",relations.relationshipBetweenPlayers(a.getId(),b.getId()));
        relations.respondFriendship(b.getId(),ga.getId(),"reject");
        assertEquals("hostile",relations.relationshipBetweenPlayers(a.getId(),b.getId()));
        relations.updateRelation(a.getId(),gb.getId(),"friendly");
        relations.respondFriendship(b.getId(),ga.getId(),"accept");
        assertEquals("friendly",relations.relationshipBetweenPlayers(b.getId(),a.getId()));
    }

    @Test
    void personalWarsKeepIndependentOpponentsAndAccelerationState() {
        Player a=createTestPlayer("war-a",30),b=createTestPlayer("war-b",30),c=createTestPlayer("war-c",30);
        long now=GameClock.now();wars.declare(a.getId(),b.getId(),0,11L,now,now+1000,now+100000);
        wars.declare(a.getId(),c.getId(),0,12L,now,now+2000,now+200000);
        var first=wars.between(a.getId(),b.getId());first.setAccelerated(true);first.setWarAt(now+500);wars.save(first);
        assertEquals(now+500,wars.between(b.getId(),a.getId()).getWarAt());
        assertEquals(now+2000,wars.between(a.getId(),c.getId()).getWarAt());
        assertFalse(wars.between(a.getId(),c.getId()).isAccelerated());
    }
}
