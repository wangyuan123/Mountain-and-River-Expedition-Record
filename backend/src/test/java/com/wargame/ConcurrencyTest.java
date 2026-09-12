package com.wargame;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.WebSocketPushService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrencyTest extends BaseServiceTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired ShopController shop;
    @Autowired PlayerItemRepository items;
    @Autowired WebSocketPushService push;
    @MockBean GameWebSocketHandler sockets;

    @Test
    void simultaneousPurchasesCannotSpendTheSameDiamondsOrKeepRolledBackItems() throws Exception {
        Player player = createTestPlayer("race-" + UUID.randomUUID(), 30);
        Resources res = getResources(player.getId());
        res.setDiamond(200);
        resourcesRepository.save(res);
        CyclicBarrier reads = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<Boolean> purchase = () -> {
            UserPrincipal principal = UserPrincipal.from(player);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            try {
                return new TransactionTemplate(transactions).execute(status -> {
                    assertEquals(200, getResources(player.getId()).getDiamond());
                    try { reads.await(10, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    assertEquals(true, shop.buy(new GameDtos.ShopBuyRequest("box_recruit_military"))
                            .getBody().get("success"));
                    return true;
                });
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                return false;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try {
            Future<Boolean> first = workers.submit(purchase);
            Future<Boolean> second = workers.submit(purchase);
            int committed = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, committed, "Only one transaction may spend the original balance");
            assertEquals(0, getResources(player.getId()).getDiamond());
            assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), "box_recruit_military")
                    .orElseThrow().getCount());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void notificationsAreDeliveredOnlyAfterCommitAndNeverAfterRollback() {
        when(sockets.isPlayerOnline(7L)).thenReturn(true);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            push.pushScoutReport(7L, Map.of("id", 1));
            verify(sockets, never()).sendToPlayer(anyLong(), anyString());
            status.setRollbackOnly();
        });
        verify(sockets, never()).sendToPlayer(anyLong(), anyString());
        transaction.executeWithoutResult(status -> {
            push.pushScoutReport(7L, Map.of("id", 2));
            verify(sockets, never()).sendToPlayer(anyLong(), anyString());
        });
        verify(sockets).sendToPlayer(eq(7L), contains("\"id\":2"));
    }

    @Test
    void settlementAndPurchaseCannotOverwriteEachOthersResourceChanges() throws Exception {
        Player player = createTestPlayer("tick-race-" + UUID.randomUUID(), 0);
        player.setLastTick(System.currentTimeMillis() - 3600_000L);
        playerRepository.save(player);
        createBuilding(player.getId(), "farm", 1);
        Resources initial = getResources(player.getId());
        initial.setDiamond(200);
        resourcesRepository.save(initial);
        CyclicBarrier reads = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        java.util.function.Function<Boolean, Boolean> operation = settle -> {
            UserPrincipal principal = UserPrincipal.from(player);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            try {
                return new TransactionTemplate(transactions).execute(status -> {
                    getResources(player.getId()); // Both transactions retain the same old version.
                    try { reads.await(10, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    if (settle) tickService.tick(player.getId());
                    else shop.buy(new GameDtos.ShopBuyRequest("box_recruit_military"));
                    return true;
                });
            } catch (OptimisticLockingFailureException e) {
                return false;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try {
            Future<Boolean> tick = workers.submit(() -> operation.apply(true));
            Future<Boolean> buy = workers.submit(() -> operation.apply(false));
            boolean tickCommitted = tick.get(20, TimeUnit.SECONDS);
            boolean buyCommitted = buy.get(20, TimeUnit.SECONDS);
            assertNotEquals(tickCommitted, buyCommitted, "A stale writer must roll back");
            Resources result = getResources(player.getId());
            assertEquals(tickCommitted ? 1040 : 1000, result.getFood());
            assertEquals(buyCommitted ? 0 : 200, result.getDiamond());
            assertEquals(buyCommitted ? 1 : 0, items.findByPlayerIdAndItemKey(player.getId(), "box_recruit_military")
                    .map(item -> item.getCount()).orElse(0));
            if (!tickCommitted) {
                tickService.tick(player.getId());
                assertEquals(1040, getResources(player.getId()).getFood());
                assertEquals(0, getResources(player.getId()).getDiamond(), "Fresh settlement preserves the purchase");
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
