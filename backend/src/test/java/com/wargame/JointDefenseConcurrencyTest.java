package com.wargame;

import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实独立事务竞争，验证回合终局和目标城粮账的唯一提交。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JointDefenseConcurrencyTest extends BaseServiceTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository members;
    @Autowired BattleSessionRepository battles;
    @Autowired AccountService accounts;
    @Autowired GarrisonFoodService food;
    @Autowired CityScope scope;
    record Fixture(Long attacker, Long receiver, Long sender, Long city, Long attack, Long session, long deadline) {}

    PlayerCity city(Player owner, WorldMap world, int x) {
        PlayerCity city = new PlayerCity(); city.setOwnerId(owner.getId()); city.setCitySlot(0); city.setName("并发协防城" + x);
        city.setWorldId(world.getId()); city.setX(x); city.setY(10); city.setAcceptGarrison(true); playerCityRepository.save(city);
        owner.setActiveCityId(city.getId()); owner.setCityPosX(x); owner.setCityPosY(10); playerRepository.save(owner); return city;
    }

    @Test void manualAndTimeoutRaceCreateOneSettlementAndOneReportForEachParticipant() throws Exception {
        Fixture fixture = new TransactionTemplate(transactions).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            var a = createTestPlayer("joint-a" + suffix, 30); var d = createTestPlayer("joint-d" + suffix, 30); var s = createTestPlayer("joint-s" + suffix, 30);
            var world = createTestWorld(); world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE)); worldMapRepository.save(world);
            city(a, world, 10); var target = city(d, world, 20); city(s, world, 30);
            Guild guild = new Guild(); guild.setName("joint" + suffix.substring(0, 8)); guild.setLeaderPlayerId(d.getId()); guild.setCreatedAt(1L); guilds.save(guild);
            members.save(new GuildMember(null, guild.getId(), d.getId(), "leader", 1L)); members.save(new GuildMember(null, guild.getId(), s.getId(), "member", 1L));
            long now = GameClock.now(); a.setWarAgainstId(d.getId()); a.setWarAt(1L); a.setWarEndAt(now + 3600000); playerRepository.save(a);
            createArmyUnit(d.getId(), "infantry", 10);
            var support = createMarch(s.getId(), "player", target.getId().toString(), target.getName(), 30, 10, 20, 10,
                    Map.of("infantry", 100), "reinforce", now - 20000, now - 10000, false, false);
            support.setStationed(true); support.setSupportOwnerId(d.getId()); marchRepository.save(support);
            var attack = createMarch(a.getId(), "player", target.getId().toString(), target.getName(), 10, 10, 20, 10,
                    Map.of("htank", 10000), "plunder", now - 20000, now - 1000, false, false);
            marchService.processMarches(a.getId(), now);
            BattleSession battle = battles.findByMarchId(attack.getId()).orElseThrow();
            battle.setAttackerPositions("{\"htank\":0}"); battle.setDefenderPositions("{\"infantry\":0,\"infantry~s1\":0}");
            battle.setRoundDeadlineAt(now - 1); battles.saveAndFlush(battle);
            return new Fixture(a.getId(), d.getId(), s.getId(), target.getId(), attack.getId(), battle.getId(), now - 1);
        });
        race(() -> marchService.processTimedOutTacticalBattle(fixture.session, fixture.deadline),
                () -> marchService.executeTacticalRound(fixture.attacker, fixture.attack, new GameDtos.BattleCommandRequest(Map.of(), 0)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertFalse(battles.existsById(fixture.session));
            for (Long id : List.of(fixture.attacker, fixture.receiver, fixture.sender)) {
                assertEquals(1, scoutReportRepository.findByPlayerId(id).stream().filter(r -> "battle".equals(r.getType())).count());
            }
        });
    }

    @Test void concurrentFoodTicksChargeTheSameIntervalOnlyOnce() throws Exception {
        long start = GameClock.now();
        Long id = new TransactionTemplate(transactions).execute(status -> {
            Player p = createTestPlayer("joint-food" + UUID.randomUUID(), 30); var c = city(p, createTestWorld(), 10);
            c.setGarrisonFoodAt(start); playerCityRepository.save(c); createArmyUnit(p.getId(), "infantry", 100);
            getResources(p.getId()).setFood(10000); return c.getId();
        });
        Long owner = playerCityRepository.findById(id).orElseThrow().getOwnerId();
        Runnable bill = () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            accounts.lockPlayer(owner);
            var c = playerCityRepository.findById(id).orElseThrow();
            // 与生产 Tick 一样先锁账号再读取城市，不能在锁前缓存上一事务的粮账游标。
            food.settle(c, start + 3600000);
        });
        race(bill, bill);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var c = playerCityRepository.findById(id).orElseThrow();
            assertEquals(9900, getResources(c.getOwnerId()).getFood() + c.getGarrisonFoodRemainder(), 1e-6);
        });
    }

    void race(Runnable first, Runnable second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> a = () -> { barrier.await(10, TimeUnit.SECONDS); first.run(); return null; };
            Callable<Void> b = () -> { barrier.await(10, TimeUnit.SECONDS); second.run(); return null; };
            var left = workers.submit(a); var right = workers.submit(b); left.get(30, TimeUnit.SECONDS); right.get(30, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    @Test void openingCityBattleAndDispatchCannotReuseTheSameTroops() throws Exception {
        record DepartureRace(Long attacker, Long defender, Long attack, Long wild) {}
        DepartureRace f = new TransactionTemplate(transactions).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            var a = createTestPlayer("lock-race-a" + suffix, 30);
            var d = createTestPlayer("lock-race-d" + suffix, 30);
            var world = createTestWorld();
            world.setTerrainData("0".repeat(WorldTerrainService.SIZE * WorldTerrainService.SIZE)); worldMapRepository.save(world);
            city(a, world, 10); var target = city(d, world, 20);
            createArmyUnit(d.getId(), "infantry", 1000); createArmyUnit(d.getId(), "scout", 10);
            giveResources(d.getId(), 100000, 100000, 100000, 100000, 100000);
            long now = GameClock.now(); a.setWarAgainstId(d.getId()); a.setWarAt(now - 1); a.setWarEndAt(now + 3600000); playerRepository.save(a);
            var wild = createWildTile(world.getId(), "forest", 30, 10, 1, Map.of("infantry", 1), 1000);
            var attack = createMarch(a.getId(), "player", target.getId().toString(), target.getName(), 10, 10, 20, 10,
                    Map.of("infantry", 1), "plunder", now - 60000, now - 1, false, false);
            return new DepartureRace(a.getId(), d.getId(), attack.getId(), wild.getId());
        });
        var departed = new java.util.concurrent.atomic.AtomicBoolean(false);
        race(() -> marchService.processMarches(f.attacker(), GameClock.now()), () -> {
            try {
                marchService.createDispatch(f.defender(), new com.wargame.model.dto.DispatchRequest("wild", f.wild(), "scout", Map.of("scout", 1), null, null));
                departed.set(true);
            } catch (com.wargame.security.GameAccessException error) {
                assertEquals("CITY_IN_BATTLE", error.getCode());
            }
        });
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var session = battles.findByMarchId(f.attack()).orElseThrow();
            int expected = departed.get() ? 9 : 10;
            assertEquals(expected, JsonUtil.parseIntMap(session.getInitialDefender()).get("scout"));
            assertEquals(expected, armyUnitRepository.findByPlayerIdAndCitySlotAndType(f.defender(), 0, "scout").get(0).getCount());
            assertEquals(departed.get() ? 1 : 0, marchRepository.countByPlayerIdAndCitySlot(f.defender(), 0));
        });
    }
}
