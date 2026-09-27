package com.wargame;

import com.wargame.model.entity.Building;
import com.wargame.model.entity.MarketOrder;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.BuildingRepository;
import com.wargame.repository.MarketOrderRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.MarketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class MarketServiceTest {

    @Autowired
    private MarketService marketService;

    @Autowired
    private MarketOrderRepository marketOrderRepository;

    @Autowired
    private ResourcesRepository resourcesRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private PlayerRepository playerRepository;

    private Player seller;
    private Player buyer;

    @BeforeEach
    void setUp() {
        marketOrderRepository.deleteAll();

        seller = new Player();
        seller.setUsername("seller_test_" + System.currentTimeMillis());
        seller.setPasswordHash("pass");
        seller = playerRepository.save(seller);

        buyer = new Player();
        buyer.setUsername("buyer_test_" + System.currentTimeMillis());
        buyer.setPasswordHash("pass");
        buyer = playerRepository.save(buyer);

        // 为卖方添加交易所与资源
        Building ex1 = new Building();
        ex1.setPlayerId(seller.getId());
        ex1.setCitySlot(0);
        ex1.setType("exchange");
        ex1.setLevel(5);
        buildingRepository.save(ex1);

        Resources sRes = new Resources();
        sRes.setPlayerId(seller.getId());
        sRes.setCitySlot(0);
        sRes.setFood(5000);
        sRes.setSteel(5000);
        sRes.setOil(5000);
        sRes.setRare(5000);
        sRes.setGold(1000);
        resourcesRepository.save(sRes);

        // 为买方添加交易所与黄金
        Building ex2 = new Building();
        ex2.setPlayerId(buyer.getId());
        ex2.setCitySlot(0);
        ex2.setType("exchange");
        ex2.setLevel(2);
        buildingRepository.save(ex2);

        Resources bRes = new Resources();
        bRes.setPlayerId(buyer.getId());
        bRes.setCitySlot(0);
        bRes.setFood(100);
        bRes.setSteel(100);
        bRes.setOil(100);
        bRes.setRare(100);
        bRes.setGold(50000);
        resourcesRepository.save(bRes);
    }

    @Test
    void testExchangeRatesAndSlotCap() {
        assertEquals(5, marketService.getExchangeLevel(seller.getId(), 0));
        assertEquals(0.70, marketService.getExchangeRate(5), 0.001); // 0.50 + 5*0.04 = 0.70
        assertEquals(0.08, marketService.getTaxRate(5), 0.001);     // 0.10 - 4*0.005 = 0.08
        // 基础2 + exLv(5)/2 = 4 槽位
        assertEquals(4, marketService.getMarketOrderSlotCap(seller.getId(), 0));
    }

    @Test
    void testCreateOrderAndCancel() {
        // 上架 1000 钢铁，单价 5 黄金
        MarketOrder order = marketService.createOrder(seller.getId(), 0, "steel", 1000, 5);
        assertNotNull(order.getId());
        assertEquals("ACTIVE", order.getStatus());
        assertEquals(5000L, order.getTotalPrice());

        // 校验卖方资源被锁定扣除
        Resources sRes = resourcesRepository.findByPlayerIdAndCitySlot(seller.getId(), 0).orElseThrow();
        assertEquals(4000, sRes.getSteel());

        // 下架撤销订单
        MarketOrder cancelled = marketService.cancelOrder(seller.getId(), 0, order.getId());
        assertEquals("CANCELLED", cancelled.getStatus());

        // 校验资源全额返还
        sRes = resourcesRepository.findByPlayerIdAndCitySlot(seller.getId(), 0).orElseThrow();
        assertEquals(5000, sRes.getSteel());
    }

    @Test
    void testBuyOrder() {
        // 卖方上架 500 稀矿，单价 20 黄金 (总价 10000 黄金)
        MarketOrder order = marketService.createOrder(seller.getId(), 0, "rare", 500, 20);

        // 不能购买自己的订单
        assertThrows(IllegalArgumentException.class, () -> {
            marketService.buyOrder(seller.getId(), 0, order.getId());
        });

        // 买方购买该订单
        MarketOrder bought = marketService.buyOrder(buyer.getId(), 0, order.getId());
        assertEquals("SOLD", bought.getStatus());
        assertEquals(buyer.getId(), bought.getBuyerId());

        // 买方扣除 10000 黄金，增加 500 稀矿
        Resources bRes = resourcesRepository.findByPlayerIdAndCitySlot(buyer.getId(), 0).orElseThrow();
        assertEquals(40000, bRes.getGold());
        assertEquals(600, bRes.getRare());

        // 卖方结算：总价 10000，税率 8% (Lv.5 交易所)，卖家实得 9200 黄金
        Resources sRes = resourcesRepository.findByPlayerIdAndCitySlot(seller.getId(), 0).orElseThrow();
        assertEquals(1000 + 9200, sRes.getGold());

        // 重复购买被拦截
        assertThrows(IllegalArgumentException.class, () -> {
            marketService.buyOrder(buyer.getId(), 0, order.getId());
        });
    }

    @Test
    void testSystemExchange() {
        // 卖方交易所 Lv.5，兑换率 70%
        // 用 1000 粮食兑换石油 -> 获得 700 石油
        Map<String, Object> result = marketService.systemExchange(seller.getId(), 0, "food", "oil", 1000);
        assertEquals(700, result.get("gain"));

        Resources sRes = resourcesRepository.findByPlayerIdAndCitySlot(seller.getId(), 0).orElseThrow();
        assertEquals(4000, sRes.getFood());
        assertEquals(5700, sRes.getOil());
    }
}
