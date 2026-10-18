package com.wargame;

import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.DepotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class NewbiePackConcurrencyTest extends BaseServiceTest {
    @Autowired ShopController shop;
    @Autowired DepotService depot;
    @Autowired PlayerItemRepository items;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPurchasesOnlyChargeOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Player player = tx.execute(status -> {
            createTestWorld();
            Player created = createTestPlayer("newbie-buy-race", 30);
            var res = resourcesRepository.findByPlayerId(created.getId()).orElseThrow();
            res.setDiamond(1000); resourcesRepository.save(res);
            return created;
        });
        assertNotNull(player);
        int successes = race(() -> {
            var principal = UserPrincipal.from(player);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            try {
                return Boolean.TRUE.equals(shop.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody().get("success"));
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        assertEquals(1, successes);
        assertEquals(901, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), "newbiePack").orElseThrow().getCount());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentOpeningsOnlyAwardOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(status -> {
            Long id = createTestPlayer("newbie-open-race", 30).getId();
            items.save(new PlayerItem(null, id, "newbiePack", 1, System.currentTimeMillis()));
            return id;
        });
        assertEquals(1, race(() -> Boolean.TRUE.equals(depot.useItem(playerId, "newbiePack", null, null).get("success"))));
        var awarded = resourcesRepository.findByPlayerId(playerId).orElseThrow();
        assertEquals(21000, awarded.getFood()); assertEquals(21000, awarded.getSteel());
        assertEquals(11000, awarded.getOil()); assertEquals(5500, awarded.getRare()); assertEquals(3500, awarded.getGold());
        assertEquals(0, items.findByPlayerIdAndItemKey(playerId, "newbiePack").orElseThrow().getCount());
    }

    private int race(Callable<Boolean> operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Boolean> request = () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return operation.call();
        };
        try {
            Future<Boolean> first = executor.submit(request), second = executor.submit(request);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            start.countDown(); executor.shutdownNow();
        }
    }
}
