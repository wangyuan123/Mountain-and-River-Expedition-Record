package com.wargame;

import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.dto.GameDtos;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用合成入库失败验证真实事务回滚，不构造异常大库存或修改实际游戏数据。 */
class ShopInventoryAtomicityTest extends BaseServiceTest {
    @Autowired ShopController shop;
    @Autowired PlayerItemRepository items;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean InventoryService inventory;

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedIntakeRollsBackChargedDiamondsAndAllowsANormalRetry() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        var player = transaction.execute(status -> {
            var p = createTestPlayer("synthetic-intake-rollback", 30);
            var balance = getResources(p.getId()); balance.setDiamond(1000); resourcesRepository.save(balance);
            return p;
        });
        UserPrincipal principal = UserPrincipal.from(player);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            doThrow(new IllegalArgumentException("合成入库失败")).when(inventory).add(player.getId(), "skillBook", 1);
            assertThrows(IllegalArgumentException.class, () -> shop.buy(new GameDtos.ShopBuyRequest("skillBook")));
            assertEquals(1000, transaction.<Integer>execute(status -> getResources(player.getId()).getDiamond()).intValue());
            assertTrue(items.findByPlayerIdAndItemKey(player.getId(), "skillBook").isEmpty());
            doCallRealMethod().when(inventory).add(player.getId(), "skillBook", 1);
            assertEquals(true, shop.buy(new GameDtos.ShopBuyRequest("skillBook")).getBody().get("success"));
            assertEquals(920, transaction.<Integer>execute(status -> getResources(player.getId()).getDiamond()).intValue());
            assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), "skillBook").orElseThrow().getCount());
        } finally {
            doCallRealMethod().when(inventory).add(player.getId(), "skillBook", 1);
            SecurityContextHolder.clearContext();
        }
    }
}
