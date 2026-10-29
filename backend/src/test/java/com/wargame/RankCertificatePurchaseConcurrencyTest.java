package com.wargame;

import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立已提交事务验证重复购买与失败回滚，避免测试外层事务掩盖真实并发。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RankCertificatePurchaseConcurrencyTest extends BaseServiceTest {
    @Autowired ShopController shop;
    @Autowired PlayerItemRepository items;
    @Autowired PlatformTransactionManager transactions;

    private Player fixture(String name) {
        return new TransactionTemplate(transactions).execute(status -> {
            createTestWorld();
            Player player = createTestPlayer(name, 30);
            var resources = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow();
            resources.setDiamond(1000);
            resourcesRepository.save(resources);
            return player;
        });
    }

    private void authenticate(Player player) {
        var principal = UserPrincipal.from(player);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentPurchasesChargeOnceForMissingAndZeroStock(boolean zeroStock) throws Exception {
        Player player = fixture("rank-buy-race-" + zeroStock);
        if (zeroStock) {
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    items.save(new PlayerItem(null, player.getId(), "rank_certificate_2", 0, 0L)));
        }
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> purchase = () -> {
            authenticate(player);
            ready.countDown();
            try {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                var response = shop.buy(new GameDtos.ShopBuyRequest("rank_certificate_2")).getBody();
                if (!Boolean.TRUE.equals(response.get("success"))) {
                    assertEquals("已持有该晋升凭证，无需重复购买", response.get("message"));
                    return false;
                }
                return true;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try {
            var first = pool.submit(purchase);
            var second = pool.submit(purchase);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(980, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getDiamond());
            assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), "rank_certificate_2").orElseThrow().getCount());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedPurchaseRollsBackDiamondsAndInventoryAndAllowsRetry() {
        Player player = fixture("rank-buy-rollback");
        authenticate(player);
        try {
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertEquals(true, shop.buy(new GameDtos.ShopBuyRequest("rank_certificate_2")).getBody().get("success"));
                throw new IllegalStateException("模拟入仓后事务失败");
            }));
            assertEquals(1000, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getDiamond());
            assertTrue(items.findByPlayerIdAndItemKey(player.getId(), "rank_certificate_2").isEmpty());
            assertEquals(true, shop.buy(new GameDtos.ShopBuyRequest("rank_certificate_2")).getBody().get("success"));
            assertEquals(980, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getDiamond());
            assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), "rank_certificate_2").orElseThrow().getCount());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
