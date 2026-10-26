package com.wargame;

import com.wargame.model.entity.Resources;
import com.wargame.service.ResourceOverflowService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceOverflowServiceTest extends BaseServiceTest {
    @Autowired private ResourceOverflowService overflow;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepareOverflowState() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS resource_overflow_state (resource_id BIGINT NOT NULL, resource_type VARCHAR(16) NOT NULL, stage TINYINT NOT NULL, last_period BIGINT NOT NULL, PRIMARY KEY (resource_id, resource_type))");
        jdbc.update("DELETE FROM resource_overflow_state");
        jdbc.update("DELETE FROM game_schedule WHERE task_key = 'resource-overflow'");
        jdbc.update("INSERT INTO game_schedule (task_key, last_period) VALUES ('resource-overflow', 0)");
    }

    @Test
    void establishesBaselineThenReducesOverflowAcrossTwoPeriodsAndResetsAfterRecovery() {
        var player = createTestPlayer("resource-overflow", 30);
        Resources balance = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        balance.setFood(1_000_000_101L);
        balance.setSteel(1_000_000_100L);
        balance.setGold(1_000_000_101L);
        balance.setDiamond(77);
        resourcesRepository.saveAndFlush(balance);

        overflow.checkPeriod(100);
        assertEquals(1_000_000_101L, resourcesRepository.findById(balance.getId()).orElseThrow().getFood(),
                "首次启用只建立基线，不处罚已有库存");

        overflow.checkPeriod(101);
        Resources firstCheck = resourcesRepository.findById(balance.getId()).orElseThrow();
        assertEquals(1_000_000_050L, firstCheck.getFood(), "首次巡检损失超额的一半并向上取整");
        assertEquals(1_000_000_050L, firstCheck.getSteel());
        assertEquals(1_000_000_050L, firstCheck.getGold(), "黄金同样受库存巡检阈值约束");
        assertEquals(77, firstCheck.getDiamond(), "钻石不纳入库存巡检");
        assertEquals(1, scoutReportRepository.findByPlayerId(player.getId()).size(), "同城同周期合并为一份报告");
        var firstReport = JsonUtil.parseObjMap(scoutReportRepository.findByPlayerId(player.getId()).get(0).getData());
        assertEquals("resource_loss", scoutReportRepository.findByPlayerId(player.getId()).get(0).getType());
        assertEquals("仓库溢出", firstReport.get("cause"));
        assertEquals("TestCity", firstReport.get("cityName"));
        assertEquals(3, ((java.util.List<?>) firstReport.get("losses")).size());

        overflow.checkPeriod(101);
        assertEquals(1, scoutReportRepository.findByPlayerId(player.getId()).size(), "同周期重复调度不得重复扣损或生成战报");

        overflow.checkPeriod(102);
        Resources secondCheck = resourcesRepository.findById(balance.getId()).orElseThrow();
        assertEquals(ResourceOverflowService.STOCK_LIMIT, secondCheck.getFood(), "下一周期清除全部剩余超额");
        assertEquals(ResourceOverflowService.STOCK_LIMIT, secondCheck.getSteel());
        assertEquals(ResourceOverflowService.STOCK_LIMIT, secondCheck.getGold());

        secondCheck.setFood(ResourceOverflowService.STOCK_LIMIT + 20);
        resourcesRepository.saveAndFlush(secondCheck);
        overflow.checkPeriod(103);
        assertEquals(ResourceOverflowService.STOCK_LIMIT + 10,
                resourcesRepository.findById(balance.getId()).orElseThrow().getFood(), "清除超限状态后再次超限按首次巡检处理");
    }

    @Test
    void tracksEachResourceAndCityIndependently() {
        var world = createTestWorld();
        var player = createTestPlayer("resource-overflow-isolation", 30);
        Resources main = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        main.setFood(ResourceOverflowService.STOCK_LIMIT + 100);
        resourcesRepository.saveAndFlush(main);

        overflow.checkPeriod(200);
        overflow.checkPeriod(201);
        main = resourcesRepository.findById(main.getId()).orElseThrow();
        assertEquals(ResourceOverflowService.STOCK_LIMIT + 50, main.getFood());

        main.setFood(ResourceOverflowService.STOCK_LIMIT);
        main.setSteel(ResourceOverflowService.STOCK_LIMIT + 20);
        resourcesRepository.saveAndFlush(main);
        overflow.checkPeriod(202);
        main = resourcesRepository.findById(main.getId()).orElseThrow();
        assertEquals(ResourceOverflowService.STOCK_LIMIT + 10, main.getSteel(), "钢铁首次超限独立按首次阶段处理");

        var branch = new com.wargame.model.entity.PlayerCity();
        branch.setOwnerId(player.getId());
        branch.setCitySlot(1);
        branch.setName("分城");
        branch.setWorldId(world.getId());
        branch.setX(20);
        branch.setY(20);
        branch.setReadyAt(0L);
        branch = playerCityRepository.saveAndFlush(branch);
        Resources branchBalance = new Resources();
        branchBalance.setPlayerId(player.getId());
        branchBalance.setCitySlot(1);
        branchBalance.setFood(ResourceOverflowService.STOCK_LIMIT + 20);
        resourcesRepository.saveAndFlush(branchBalance);

        overflow.checkPeriod(203);
        assertEquals(ResourceOverflowService.STOCK_LIMIT + 10,
                resourcesRepository.findById(branchBalance.getId()).orElseThrow().getFood(), "分城资源独立从首次阶段开始");
        assertTrue(scoutReportRepository.findByPlayerId(player.getId()).size() >= 3,
                "不同城市的损耗分别生成战报");
    }
}
