package com.wargame;

import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OfficerEquipmentConcurrencyTest extends BaseServiceTest {
    @Autowired EquipmentService gear;
    @Autowired OfficerEquipmentProgressService progress;
    @Autowired PlayerItemRepository items;
    @Autowired OfficerEquipmentRepository worn;
    @Autowired DepotService depot;
    private void give(Long p, String key, int count) {
        PlayerItem i = new PlayerItem(); i.setPlayerId(p); i.setItemKey(key); i.setCount(count); i.setUpdatedAt(0L); items.saveAndFlush(i);
    }
    private Officer officer(Long p) {
        Officer o = new Officer(); o.setPlayerId(p); o.setName("并发军备"); o.setLevel(100); o.setRole("idle"); return officerRepository.saveAndFlush(o);
    }
    private List<Map<String, Object>> race(Supplier<Map<String, Object>> a, Supplier<Map<String, Object>> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var fa = pool.submit(() -> { start.await(); return a.get(); });
            var fb = pool.submit(() -> { start.await(); return b.get(); }); start.countDown();
            return List.of(fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void retiredPreparationIsRejectedConcurrentlyWithoutCharging() throws Exception {
        Long p = createTestPlayer("prep-race", 30).getId(); var o = officer(p); giveResources(p, 100, 100, 100, 100, 5000);
        give(p, "officerPreparationParts", 100);
        var results = race(() -> gear.prepare(p, o.getId(), "helmet", 0), () -> gear.prepare(p, o.getId(), "helmet", 0));
        assertEquals(0, results.stream().filter(r -> r.get("success").equals(true)).count());
        assertEquals(100, items.findByPlayerIdAndItemKey(p, "officerPreparationParts").orElseThrow().getCount());
        assertEquals(5000, resourcesRepository.findByPlayerId(p).orElseThrow().getGold());
    }
    @Test void oneBoxCannotBeOpenedTwiceConcurrently() throws Exception {
        Long p = createTestPlayer("box-race", 30).getId(); give(p, "box_marshal_defense", 1);
        var results = race(() -> depot.useItem(p, "box_marshal_defense", null, null), () -> depot.useItem(p, "box_marshal_defense", null, null));
        assertEquals(1, results.stream().filter(r -> r.get("success").equals(true)).count());
        for (var d : OfficerEquipmentDef.setItems("marshal_defense")) assertEquals(1, items.findByPlayerIdAndItemKey(p, d.key()).orElseThrow().getCount());
    }
    @Test void twoOfficersCannotBothConsumeOnePhysicalSetAndRetiredClaimsNeverPay() throws Exception {
        Long p = createTestPlayer("loadout-race", 30).getId(); var a = officer(p); var b = officer(p);
        OfficerEquipmentDef.setItems("recruit_military").forEach(d -> give(p, d.key(), 1));
        var results = race(() -> gear.equipSet(p, a.getId(), "recruit_military"), () -> gear.equipSet(p, b.getId(), "recruit_military"));
        assertEquals(1, results.stream().filter(r -> r.get("success").equals(true)).count()); assertEquals(12, worn.findByPlayerId(p).size());
        var claims = race(() -> progress.claim(p, "wild"), () -> progress.claim(p, "wild"));
        assertEquals(0, claims.stream().filter(r -> r.get("success").equals(true)).count());
        assertTrue(items.findByPlayerIdAndItemKey(p, "officerPreparationParts").isEmpty());
    }
}
