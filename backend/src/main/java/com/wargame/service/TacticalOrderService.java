package com.wargame.service;

import com.wargame.model.constants.StratagemRules;
import com.wargame.model.entity.Officer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 情报道具只读取当前城市指挥官的装备后学识，不叠加司令官或技能。 */
@Service
@RequiredArgsConstructor
public class TacticalOrderService {
    private final MarchTargetService targets;
    private final EquipmentService equipment;

    public Map<String, Object> effect(Long playerId, String itemKey) {
        Officer commander = targets.getCommander(playerId);
        int knowledge = Math.max(0, equipment.attributes(commander).knowledge());
        boolean accelerating = "marchOrd".equals(itemKey) || "warSpeedOrder".equals(itemKey);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("commanderId", commander == null ? 0L : commander.getId());
        effect.put("commanderName", commander == null ? "未任命" : commander.getName());
        effect.put("knowledge", knowledge);
        effect.put("baseRatio", 0.5);
        effect.put("knowledgeBonus", knowledge * 0.001);
        effect.put("ratio", accelerating ? StratagemRules.acceleration(knowledge) : StratagemRules.delay(knowledge));
        effect.put("cap", accelerating ? 0.8 : 0.7);
        effect.put("itemKey", itemKey);
        return effect;
    }

    /** 确认标识绑定服务端快照；倒计时流逝不失效，换任、换装或行军变更会失效。 */
    public String token(Map<String, Object> effect, Object... context) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((effect.toString() + java.util.Arrays.toString(context)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void requireToken(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw new IllegalArgumentException("指挥官或目标状态已变化，请刷新预览后重新确认");
    }
}
