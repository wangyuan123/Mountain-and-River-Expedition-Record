package com.wargame;

import com.wargame.service.TickService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证正常经济区间的切分一致性，不依赖请求频率或随机取整。 */
class EconomyAccrualTest {
    @Test
    void appeasementPreservesFractionsAndClearsThemAtBounds() {
        var city = new com.wargame.model.entity.PlayerCity();
        city.setMorale(70); city.setMoraleAccrual(.4);
        city.setResentment(3); city.setResentmentAccrual(.6);
        city.adjustSentiment(10, -1);
        assertEquals(80, city.getMorale()); assertEquals(.4, city.getMoraleAccrual(), 1e-9);
        assertEquals(2, city.getResentment()); assertEquals(.6, city.getResentmentAccrual(), 1e-9);
        city.adjustSentiment(100, -100);
        assertEquals(100, city.getMorale()); assertEquals(0, city.getMoraleAccrual());
        assertEquals(0, city.getResentment()); assertEquals(0, city.getResentmentAccrual());
    }
    @Test
    void moraleAndResentmentFollowTheSameTimelineAcrossPartitions() {
        for (int tax : new int[]{0,20,30,70,100}) {
            double[] once = TickService.settleSentiment(40, 15, tax, tax > 50 ? (tax-50)*.05 : tax <=20 ? -(25-tax)*.05 : 0, 4);
            double[] split = {40,15};
            for (int i=0;i<240;i++) split = TickService.settleSentiment(split[0],split[1],tax,tax > 50 ? (tax-50)*.05 : tax <=20 ? -(25-tax)*.05 : 0,1.0/60);
            assertArrayEquals(once,split,1e-7,"税率="+tax);
        }
    }

    @Test
    void capacityAndUpkeepUseContinuousNetFlowsIncludingExcessStock() {
        for (double initial : new double[]{0,150,200,300}) {
            for (double income : new double[]{20,100}) {
                double once=TickService.settleBalance(initial,income,40,1,200), split=initial;
                for(int i=0;i<60;i++) split=TickService.settleBalance(split,income,40,1.0/60,200);
                assertEquals(once,split,1e-7);
            }
        }
    }
}
