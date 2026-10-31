package com.wargame;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.DepotService;
import com.wargame.service.OfficerService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UniversalSkillBookConcurrencyTest extends BaseServiceTest {
    @Autowired private DepotService depot;
    @Autowired private OfficerService officers;
    @Autowired private PlayerItemRepository items;
    @Autowired private PlatformTransactionManager transactions;

    private void give(Long playerId, String key, int count) {
        items.saveAndFlush(new PlayerItem(null, playerId, key, count, 0L));
    }

    private int count(Long playerId, String key) {
        return items.findByPlayerIdAndItemKey(playerId, key).orElseThrow().getCount();
    }

    private Officer officer(Long playerId) {
        Officer officer = createOfficer(playerId, "idle", 30, 30, 30);
        officer.setSkills("[]");
        return officerRepository.saveAndFlush(officer);
    }

    /** 两个事务先加载同一军官，再同时调用服务，验证锁内重新读取而非复用旧快照。 */
    private List<Map<String, Object>> race(Long officerId, Supplier<Map<String, Object>> first,
                                          Supplier<Map<String, Object>> second) throws Exception {
        var ready = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(first, second).stream().map(action -> pool.submit(() ->
                    new TransactionTemplate(transactions).execute(status -> {
                        officerRepository.findById(officerId).orElseThrow();
                        ready.countDown();
                        try {
                            assertTrue(ready.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return action.get();
                    }))).toList();
            return List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repeatedChoiceWithTwoBooksConsumesOnlyOnce() throws Exception {
        Long playerId = createTestPlayer("books-repeat-race", 30).getId();
        Officer officer = officer(playerId);
        give(playerId, "universalSkillBook", 2);
        Supplier<Map<String, Object>> action = () -> depot.useItem(playerId, "universalSkillBook", officer.getId(), null, "frenzy");
        var results = race(officer.getId(), action, action);
        assertEquals(1, results.stream().filter(result -> Boolean.TRUE.equals(result.get("success"))).count());
        assertEquals(1, count(playerId, "universalSkillBook"));
        assertEquals(1, JsonUtil.parseList(officerRepository.findById(officer.getId()).orElseThrow().getSkills()).size());
    }

    @Test
    void differentChoicesCannotConsumeOneBookTwice() throws Exception {
        Long playerId = createTestPlayer("books-stock-race", 30).getId();
        Officer officer = officer(playerId);
        give(playerId, "universalSkillBook", 1);
        var results = race(officer.getId(),
                () -> depot.useItem(playerId, "universalSkillBook", officer.getId(), null, "frenzy"),
                () -> depot.useItem(playerId, "universalSkillBook", officer.getId(), null, "medic"));
        assertEquals(1, results.stream().filter(result -> Boolean.TRUE.equals(result.get("success"))).count());
        assertEquals(0, count(playerId, "universalSkillBook"));
        assertEquals(1, JsonUtil.parseList(officerRepository.findById(officer.getId()).orElseThrow().getSkills()).size());
    }

    @Test
    void randomAndSpecificLearningCompeteWithUniversalForLastSlot() throws Exception {
        for (boolean random : new boolean[] {true, false}) {
            Long playerId = createTestPlayer("books-slot-race-" + random, 30).getId();
            Officer officer = officer(playerId);
            officer.setSkills("[{\"id\":\"pierce\",\"lv\":2},{\"id\":\"blitz\",\"lv\":3}]");
            officerRepository.saveAndFlush(officer);
            String otherBook = random ? "skillBook" : "skillBook_frenzy";
            give(playerId, otherBook, 1);
            give(playerId, "universalSkillBook", 1);
            var results = race(officer.getId(),
                    () -> depot.useItem(playerId, "universalSkillBook", officer.getId(), null, "medic"),
                    () -> depot.useItem(playerId, otherBook, officer.getId(), null));
            assertEquals(1, results.stream().filter(result -> Boolean.TRUE.equals(result.get("success"))).count(), results.toString());
            assertEquals(1, count(playerId, otherBook) + count(playerId, "universalSkillBook"));
            assertEquals(3, JsonUtil.parseList(officerRepository.findById(officer.getId()).orElseThrow().getSkills()).size());
        }
    }

    @Test
    void failedTransactionRollsBackBookAndSkillTogether() {
        Long playerId = createTestPlayer("books-rollback", 30).getId();
        Officer officer = officer(playerId);
        give(playerId, "universalSkillBook", 1);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            assertEquals(true, officers.learnUniversalSkill(playerId, officer.getId(), "frenzy").get("success"));
            officerRepository.flush();
            throw new IllegalStateException("模拟技能持久化后事务失败");
        }));
        assertEquals(1, count(playerId, "universalSkillBook"));
        assertEquals("[]", officerRepository.findById(officer.getId()).orElseThrow().getSkills());
    }

    @Test
    void retiredUpgradeAndAbandonPreserveConcurrentFullLevelLearning() throws Exception {
        for (boolean upgrade : new boolean[] {true, false}) {
            Long playerId = createTestPlayer("books-edit-race-" + upgrade, 30).getId();
            Officer officer = officer(playerId);
            officer.setSkills("[{\"id\":\"pierce\",\"lv\":2}]");
            officerRepository.saveAndFlush(officer);
            give(playerId, "universalSkillBook", 1);
            if (upgrade) give(playerId, "skillBook_pierce", 1);
            var results = race(officer.getId(),
                    () -> officers.learnUniversalSkill(playerId, officer.getId(), "medic"),
                    () -> upgrade ? officers.upgradeSkill(playerId, officer.getId(), 0, "skillBook_pierce")
                            : officers.abandonSkill(playerId, officer.getId(), 0));
            assertEquals(true, results.get(0).get("success"));
            assertEquals(!upgrade, results.get(1).get("success"));
            var learned = JsonUtil.parseList(officerRepository.findById(officer.getId()).orElseThrow().getSkills());
            assertTrue(learned.contains(Map.of("id", "medic", "lv", 5)));
            assertEquals(upgrade ? 2 : 1, learned.size());
            if (upgrade) {
                assertTrue(learned.contains(Map.of("id", "pierce", "lv", 5)));
                assertEquals(1, count(playerId, "skillBook_pierce"));
            }
            assertEquals(0, count(playerId, "universalSkillBook"));
        }
    }
}
