package com.wargame;

import com.wargame.service.PrisonerService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证俘虏候选和容量裁剪使用稳定的最大余数分配，不依赖随机数。 */
class PrisonerServiceTest {
    @Test
    void apportionUsesLargestRemainderAndStableKeyTieBreak() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("infantry", 1);
        weights.put("tank", 1);
        weights.put("truck", 1);
        assertEquals(Map.of("infantry", 1, "tank", 1), PrisonerService.apportion(weights, 2));
    }

    @Test
    void apportionNeverExceedsWeightsOrRequestedAmount() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("infantry", 100);
        weights.put("tank", 20);
        assertEquals(Map.of("infantry", 50, "tank", 10), PrisonerService.apportion(weights, 60));
        assertEquals(Map.of("infantry", 100, "tank", 20), PrisonerService.apportion(weights, 999));
    }
}
