package com.wargame;

import com.wargame.model.constants.OfficerSkillDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验周期技能说明与服务端反击伤害系数的当前口径。 */
class OfficerSkillDescriptionTest {
    @Test
    void allPeriodicSkillsDescribeTheSameCombatAnchor() {
        for (String id : new String[]{"frenzy", "bulwark", "counter", "learn", "borrow_armor"}) {
            assertTrue(OfficerSkillDef.getSkill(id).desc()
                    .contains("首次交战回合及之后每隔2回合生效（如第4、7、10回合）"), id);
        }
    }

    @Test
    void counterReportShowsActualDamageCoefficientAtEachLevel() {
        OfficerSkillDef counter = OfficerSkillDef.getSkill("counter");
        int[] percents = {20, 40, 60, 80, 100};
        for (int level = 1; level <= 5; level++) {
            assertTrue(counter.descriptionAtLevel(level)
                    .contains("以当前剩余兵力总伤害的" + percents[level - 1] + "%反击"), "Lv." + level);
        }
        assertEquals(counter.descriptionAtLevel(5), counter.descriptionAtLevel(6));
    }
}
