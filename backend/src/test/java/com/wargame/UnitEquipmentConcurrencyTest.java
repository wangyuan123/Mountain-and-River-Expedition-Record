package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.UnitEquipmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UnitEquipmentConcurrencyTest extends BaseServiceTest {
    @Autowired UnitEquipmentService equipment;
    @Autowired PlayerItemRepository items;
    @Autowired UnitEquipmentOrderRepository orders;
    @Autowired UnitLoadoutRepository loadouts;
    @Autowired PlatformTransactionManager transactions;

    private Long player() { return createTestPlayer("gear-" + UUID.randomUUID(), 30).getId(); }
    private int inventory(Long player) { return items.findByPlayerIdAndItemKey(player, "ue_infantry_helmet").map(PlayerItem::getCount).orElse(0); }

    @Test void twoFirstSavesHaveOneWinnerAndConsumeOneItem() throws Exception {
        Long player = player(); items.save(new PlayerItem(null, player, "ue_infantry_helmet", 1, 1L));
        CyclicBarrier barrier = new CyclicBarrier(2); ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<Boolean> save = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try { equipment.save(player, "infantry", Map.of("protection", "ue_infantry_helmet"), 0L); return true; }
            catch (IllegalArgumentException stale) { return false; }
        };
        try {
            Future<Boolean> first = workers.submit(save), second = workers.submit(save);
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(0, inventory(player)); assertEquals(1, loadouts.findByPlayerId(player).size());
        } finally { workers.shutdownNow(); }
    }

    @Test void concurrentCompletionGrantsOneItemAndRollbackLeavesOrderClaimable() throws Exception {
        Long player = player(); UnitEquipmentOrder order = new UnitEquipmentOrder();
        order.setPlayerId(player); order.setCitySlot(0); order.setItemKey("ue_infantry_helmet"); order.setCost("{}"); order.setStartAt(0L); order.setEndAt(1L); orders.save(order);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(equipment.complete(player, 2L)); assertEquals(1, inventory(player)); status.setRollbackOnly();
        });
        assertEquals(0, inventory(player)); assertEquals(1, orders.findByPlayerId(player).size());
        CyclicBarrier barrier = new CyclicBarrier(2); ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<Boolean> complete = () -> { barrier.await(10, TimeUnit.SECONDS); return equipment.complete(player, 2L); };
        try {
            Future<Boolean> first = workers.submit(complete), second = workers.submit(complete);
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, inventory(player)); assertTrue(orders.findByPlayerId(player).isEmpty());
            equipment.save(player, "infantry", Map.of("protection", "ue_infantry_helmet"), 0L);
            assertEquals(0, inventory(player));
        } finally { workers.shutdownNow(); }
    }
}
