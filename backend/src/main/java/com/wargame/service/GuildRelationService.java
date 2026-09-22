package com.wargame.service;

import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.GuildRelation;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRelationRepository;
import com.wargame.repository.GuildRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GuildRelationService {
    public static final String NEUTRAL = "neutral";
    public static final String FRIENDLY = "friendly";
    public static final String HOSTILE = "hostile";

    private final GuildRepository guildRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final GuildRelationRepository guildRelationRepository;

    public GuildRelationService(GuildRepository guildRepository, GuildMemberRepository guildMemberRepository,
                                GuildRelationRepository guildRelationRepository) {
        this.guildRepository = guildRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.guildRelationRepository = guildRelationRepository;
    }

    /**
     * 更新当前管理者所属军团与目标军团的共享外交关系。
     * @param playerId 操作玩家 ID，必须是团长或管理员
     * @param targetGuildId 目标军团 ID
     * @param rawStatus hostile、friendly 或 neutral
     * @return 更新后的关系摘要
     */
    @Transactional
    public Map<String, Object> updateRelation(Long playerId, Long targetGuildId, String rawStatus) {
        GuildMember member = guildMemberRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        if (!isManager(member)) throw new IllegalArgumentException("只有团长或管理员可以设置军团关系");
        if (targetGuildId == null || member.getGuildId().equals(targetGuildId)) {
            throw new IllegalArgumentException("请选择其他军团");
        }
        Guild target = guildRepository.findById(targetGuildId).orElseThrow(() -> new IllegalArgumentException("目标军团不存在"));
        String status = normalizeStatus(rawStatus);
        Long low = Math.min(member.getGuildId(), target.getId());
        Long high = Math.max(member.getGuildId(), target.getId());
        GuildRelation relation = guildRelationRepository.findByGuildLowIdAndGuildHighId(low, high).orElse(null);
        if (NEUTRAL.equals(status)) {
            if (relation != null) guildRelationRepository.delete(relation);
            return Map.of("success", true, "message", "已设为中立", "status", NEUTRAL);
        }
        if (relation == null) relation = new GuildRelation();
        relation.setGuildLowId(low);
        relation.setGuildHighId(high);
        relation.setStatus(status);
        relation.setUpdatedByPlayerId(playerId);
        relation.setUpdatedAt(System.currentTimeMillis());
        guildRelationRepository.save(relation);
        return Map.of("success", true, "message", HOSTILE.equals(status) ? "已设为敌对军团" : "已设为友好军团", "status", status);
    }

    /** 返回两名玩家所属军团的关系；同军团成员默认视为友好。 */
    @Transactional(readOnly = true)
    public String relationshipBetweenPlayers(Long firstPlayerId, Long secondPlayerId) {
        if (firstPlayerId == null || secondPlayerId == null || firstPlayerId.equals(secondPlayerId)) return FRIENDLY;
        GuildMember first = guildMemberRepository.findByPlayerId(firstPlayerId).orElse(null);
        GuildMember second = guildMemberRepository.findByPlayerId(secondPlayerId).orElse(null);
        if (first == null || second == null) return NEUTRAL;
        if (first.getGuildId().equals(second.getGuildId())) return FRIENDLY;
        return relationshipBetweenGuilds(first.getGuildId(), second.getGuildId());
    }

    public boolean areHostile(Long firstPlayerId, Long secondPlayerId) {
        return HOSTILE.equals(relationshipBetweenPlayers(firstPlayerId, secondPlayerId));
    }

    public boolean areFriendly(Long firstPlayerId, Long secondPlayerId) {
        return FRIENDLY.equals(relationshipBetweenPlayers(firstPlayerId, secondPlayerId));
    }

    /** 返回军团详情页所需的全部已设置关系。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRelations(Long guildId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GuildRelation relation : guildRelationRepository.findByGuildLowIdOrGuildHighIdOrderByUpdatedAtDesc(guildId, guildId)) {
            Long otherGuildId = guildId.equals(relation.getGuildLowId()) ? relation.getGuildHighId() : relation.getGuildLowId();
            Guild other = guildRepository.findById(otherGuildId).orElse(null);
            if (other == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("guildId", other.getId());
            item.put("name", other.getName());
            item.put("icon", other.getIcon());
            item.put("status", relation.getStatus());
            item.put("updatedAt", relation.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    private String relationshipBetweenGuilds(Long firstGuildId, Long secondGuildId) {
        Long low = Math.min(firstGuildId, secondGuildId);
        Long high = Math.max(firstGuildId, secondGuildId);
        return guildRelationRepository.findByGuildLowIdAndGuildHighId(low, high)
                .map(GuildRelation::getStatus)
                .orElse(NEUTRAL);
    }

    private boolean isManager(GuildMember member) {
        return "leader".equals(member.getRole()) || "admin".equals(member.getRole());
    }

    private String normalizeStatus(String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.trim().toLowerCase(Locale.ROOT);
        if (!HOSTILE.equals(status) && !FRIENDLY.equals(status) && !NEUTRAL.equals(status)) {
            throw new IllegalArgumentException("关系状态只能是敌对、友好或中立");
        }
        return status;
    }
}
