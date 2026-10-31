package com.wargame;

import com.wargame.service.PopulationEconomy;
import com.wargame.service.TickService;
import com.wargame.model.entity.Resources;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 同一段正常游戏时间使用不同结算频率，人口及税收必须一致。 */
class PopulationEconomyTest {
    private PopulationEconomy.Result settle(double people, double gold, double morale, double resentment, int tax,
                                            int cap, long from, long to) {
        double rate = tax > 50 ? (tax - 50) * .05 : tax <= 20 ? -(25 - tax) * .05 : 0;
        return PopulationEconomy.settle(people, gold, morale, resentment, tax, rate, cap, tax / 50.0,
                0, 20_000_000, from, to, new Resources(), List.of());
    }

    @Test void newResidentsOnlyPayTaxAfterTheyArrive() {
        var result = settle(0, 0, 70, 0, 30, 6000, 0, 3_600_000);
        assertEquals(180, result.population(), 1e-6);
        assertEquals(54, result.gold(), 1e-6);
    }

    @Test void growthAndTaxAreIndependentOfSettlementFrequency() {
        for (double initial : new double[]{0, 1190, 1500}) {
            var once = settle(initial, 0, 70, 0, 30, 1200, 0, 3_600_000);
            var split = new PopulationEconomy.Result(initial, 0);
            for (int i = 0; i < 60; i++) split = settle(split.population(), split.gold(), 70, 0, 30, 1200,
                    i * 60_000L, (i + 1) * 60_000L);
            assertEquals(once.population(), split.population(), 1e-5);
            assertEquals(once.gold(), split.gold(), 1e-5);
        }
    }

    @Test void movingMoraleAndFlightUseTheSameTimeline() {
        var once = settle(1400, 0, 40, 15, 70, 1200, 0, 7_200_000);
        var split = new PopulationEconomy.Result(1400, 0);
        double[] sentiment = {40, 15};
        for (int i = 0; i < 120; i++) {
            split = settle(split.population(), split.gold(), sentiment[0], sentiment[1], 70, 1200,
                    i * 60_000L, (i + 1) * 60_000L);
            sentiment = TickService.settleSentiment(sentiment[0], sentiment[1], 70, 1, 1.0 / 60);
        }
        assertEquals(once.population(), split.population(), 1e-5);
        assertEquals(once.gold(), split.gold(), 1e-5);
    }
}
