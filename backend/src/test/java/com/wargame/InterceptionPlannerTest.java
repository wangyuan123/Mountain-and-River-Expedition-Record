package com.wargame;

import com.wargame.model.entity.March;
import com.wargame.service.InterceptionPlanner;
import com.wargame.service.MarchRouteService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InterceptionPlannerTest {
    private March enemy(long arrival) {
        March enemy = new March();
        enemy.setFromX(0); enemy.setFromY(0); enemy.setTargetX(10); enemy.setTargetY(0);
        enemy.setStartAt(0L); enemy.setArriveAt(arrival);
        return enemy;
    }
    private InterceptionPlanner.Plan plan(March enemy, long now) {
        return InterceptionPlanner.plan(enemy, 10, 0, 1, now,
                p -> new MarchRouteService.Route("land", List.of(List.of(10, 0), List.of((int) Math.round(p.x()), 0)), 10 - p.x(), 0, 100));
    }
    @Test
    void preparationBoundaryUsesFullFiveGameSecondsAndNeverCityEndpoint() {
        var plan = plan(enemy(10_000), 0);
        assertTrue(plan.prepared());
        assertTrue(plan.readyAt() <= plan.clashAt());
        assertTrue(plan.clashAt() - plan.readyAt() <= 3);
        assertTrue(plan.clashAt() < 10_000);
        assertTrue(plan.point().x() < 10);
        assertEquals(5_000, plan.readyAt() - plan.deployAt());
    }
    @Test
    void canStillAmbushWhenValidWindowIsMuchSmallerThanSamplingStep() {
        var plan = plan(enemy(6_002), 0);
        assertTrue(plan.prepared());
        assertTrue(plan.clashAt() < 6_002);
        assertTrue(plan.deployAt() >= 1_000);
    }
    @Test
    void tooLittlePreparationFallsBackToUrgentAndTooLittleMovementRejects() {
        var urgent = plan(enemy(5_000), 0);
        assertFalse(urgent.prepared());
        assertTrue(urgent.deployAt() <= urgent.clashAt());
        assertThrows(IllegalArgumentException.class, () -> plan(enemy(1_000), 0));
        assertThrows(IllegalArgumentException.class, () -> plan(enemy(10_000), 10_000));
    }
    @Test
    void usesPolylineAndRecomputesCrossingForNewTimelineWithoutChangingGeometry() {
        March enemy = enemy(20_000);
        enemy.setRouteData("[[0,0],[0,10],[10,0]]");
        var plan = plan(enemy, 0);
        String signature = InterceptionPlanner.signature(enemy);
        assertNotEquals(0, plan.point().y());
        long previous = plan.clashAt();
        enemy.setArriveAt(30_000L);
        assertEquals(signature, InterceptionPlanner.signature(enemy));
        assertEquals(Math.round(previous * 1.5), InterceptionPlanner.crossingAt(enemy, plan.enemyProgress()), 2);
        enemy.setTargetX(11);
        assertNotEquals(signature, InterceptionPlanner.signature(enemy));
    }
    @Test
    void routeValidationCanRejectAnOtherwiseReachableDeployment() {
        assertThrows(IllegalArgumentException.class, () -> InterceptionPlanner.plan(enemy(20_000), 10, 0, 1, 0,
                point -> { throw new IllegalArgumentException("无法通行"); }));
    }
}
