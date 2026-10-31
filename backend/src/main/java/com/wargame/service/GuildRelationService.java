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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatService chatService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GuildChatService guildChatService;

    public void setChatService(ChatService chatService) { this.chatService = chatService; }
    public void setGuildChatService(GuildChatService guildChatService) { this.guildChatService = guildChatService; }

    public GuildRelationService(GuildRepository guildRepository, GuildMemberRepository guildMemberRepository,
                                GuildRelationRepository guildRelationRepository) {
        this.guildRepository = guildRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.guildRelationRepository = guildRelationRepository;
    }

    /**
     * 友好需对方接受后生效；任一方可解除同盟或声明敌对。
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
        lockGuilds(member.getGuildId(), targetGuildId);
        Guild target = guildRepository.findById(targetGuildId).orElseThrow(() -> new IllegalArgumentException("目标军团不存在"));
        Guild myGuild = guildRepository.findById(member.getGuildId()).orElse(null);
        String myName = myGuild != null ? myGuild.getName() : "本军团";
        String targetName = target.getName();

        String status = normalizeStatus(rawStatus);
        Long low = Math.min(member.getGuildId(), target.getId());
        Long high = Math.max(member.getGuildId(), target.getId());
        GuildRelation relation = guildRelationRepository.findByGuildLowIdAndGuildHighId(low, high).orElse(null);
        if (FRIENDLY.equals(status)) {
            if (relation != null && FRIENDLY.equals(relation.getStatus())) return Map.of("success", true, "message", "双方已是友好同盟");
            if (relation == null) {
                relation = new GuildRelation(); relation.setGuildLowId(low); relation.setGuildHighId(high); relation.setStatus(NEUTRAL);
            }
            if (relation.getFriendshipRequestedBy() != null && !member.getGuildId().equals(relation.getFriendshipRequestedBy()))
                throw new IllegalArgumentException("对方已发来友好邀请，请选择接受或拒绝");
            relation.setFriendshipRequestedBy(member.getGuildId());
            relation.setUpdatedAt(System.currentTimeMillis()); relation.setUpdatedByPlayerId(playerId);
            guildRelationRepository.save(relation);
            return Map.of("success", true, "message", "友好邀请已发送，等待对方管理者接受", "status", relation.getStatus(), "pending", true);
        }
        if (NEUTRAL.equals(status)) {
            boolean changed = relation != null && !NEUTRAL.equals(relation.getStatus());
            if (relation != null) guildRelationRepository.delete(relation);
            // 只撤销未生效邀请时没有同盟解除事件，不发布误导性的世界公告。
            if (changed) broadcastRelationNotice(member.getGuildId(), target.getId(), myName, targetName, NEUTRAL);
            return Map.of("success", true, "message", "已设为中立", "status", NEUTRAL);
        }
        if (relation == null) relation = new GuildRelation();
        relation.setGuildLowId(low);
        relation.setGuildHighId(high);
        relation.setStatus(status);
        relation.setFriendshipRequestedBy(null);
        relation.setUpdatedByPlayerId(playerId);
        relation.setUpdatedAt(System.currentTimeMillis());
        guildRelationRepository.save(relation);

        broadcastRelationNotice(member.getGuildId(), target.getId(), myName, targetName, status);
        return Map.of("success", true, "message", HOSTILE.equals(status) ? "已设为敌对军团" : "已设为友好军团", "status", status);
    }

    /** 邀请必须由对方现任管理者确认；拒绝保持原外交状态，撤回仅限发起方。 */
    @Transactional
    public Map<String, Object> respondFriendship(Long playerId, Long otherId, String action) {
        if (action == null || !java.util.Set.of("accept", "reject", "withdraw").contains(action))
            throw new IllegalArgumentException("无效的外交操作");
        GuildMember member = guildMemberRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalArgumentException("尚未加入军团"));
        if (!isManager(member)) throw new IllegalArgumentException("只有团长或管理员可以处理外交邀请");
        lockGuilds(member.getGuildId(), otherId);
        Long low = Math.min(member.getGuildId(), otherId), high = Math.max(member.getGuildId(), otherId);
        GuildRelation relation = guildRelationRepository.findByGuildLowIdAndGuildHighId(low, high)
                .orElseThrow(() -> new IllegalArgumentException("友好邀请已失效"));
        Long sender = relation.getFriendshipRequestedBy();
        if (sender == null) throw new IllegalArgumentException("友好邀请已处理或撤回");
        boolean own = member.getGuildId().equals(sender);
        if ("withdraw".equals(action) ? !own : own) throw new IllegalArgumentException("只能处理属于本方的外交邀请");
        relation.setFriendshipRequestedBy(null);
        relation.setUpdatedByPlayerId(playerId); relation.setUpdatedAt(System.currentTimeMillis());
        if ("accept".equals(action)) {
            relation.setStatus(FRIENDLY);
            Guild mine = guildRepository.findById(member.getGuildId()).orElseThrow();
            Guild other = guildRepository.findById(otherId).orElseThrow();
            broadcastRelationNotice(mine.getId(), other.getId(), mine.getName(), other.getName(), FRIENDLY);
        }
        guildRelationRepository.save(relation);
        return Map.of("success", true, "message", "accept".equals(action) ? "双方友好同盟已生效"
                : "withdraw".equals(action) ? "友好邀请已撤回" : "友好邀请已拒绝");
    }

    /** 按固定军团ID顺序锁定，串行化空关系创建与双方同时确认。 */
    private void lockGuilds(Long first, Long second) {
        if (second == null || first.equals(second)) throw new IllegalArgumentException("请选择其他军团");
        guildRepository.lockById(Math.min(first, second)).orElseThrow(() -> new IllegalArgumentException("军团不存在"));
        guildRepository.lockById(Math.max(first, second)).orElseThrow(() -> new IllegalArgumentException("军团不存在"));
    }

    @Transactional
    public void removeGuild(Long guildId) {
        guildRelationRepository.deleteByGuildLowIdOrGuildHighId(guildId, guildId);
    }

    private void broadcastRelationNotice(Long myGuildId, Long targetGuildId, String myName, String targetName, String status) {
        String worldMsg;
        String myGuildMsg;
        String targetGuildMsg;

        if (HOSTILE.equals(status)) {
            worldMsg = "【外交公告】「" + myName + "」已将「" + targetName + "」设定为敌对阵营！烽烟四起！";
            myGuildMsg = "【军团外交】我团已将「" + targetName + "」设定为敌对阵营！全军进入战备状态！";
            targetGuildMsg = "【军团外交】「" + myName + "」已将我团设定为敌对阵营！请全体成员提高警惕！";
        } else if (FRIENDLY.equals(status)) {
            worldMsg = "【外交公告】「" + myName + "」与「" + targetName + "」缔结友好同盟！守望相助！";
            myGuildMsg = "【军团外交】我团与「" + targetName + "」正式结为友好同盟！";
            targetGuildMsg = "【军团外交】「" + myName + "」与我团正式结为友好同盟！";
        } else {
            worldMsg = "【外交公告】「" + myName + "」与「" + targetName + "」解除了外交立场，恢复中立。";
            myGuildMsg = "【军团外交】我团与「" + targetName + "」已恢复中立关系。";
            targetGuildMsg = "【军团外交】「" + myName + "」与我团已恢复中立关系。";
        }

        if (chatService != null) {
            chatService.sendSystem(worldMsg);
        }
        if (guildChatService != null) {
            guildChatService.sendSystem(myGuildId, myGuildMsg);
            guildChatService.sendSystem(targetGuildId, targetGuildMsg);
        }
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

    /** 后勤支援限同一军团，外交上的友好关系不授予支援权限。 */
    @Transactional(readOnly = true)
    public boolean areGuildmates(Long firstPlayerId, Long secondPlayerId) {
        if (firstPlayerId == null || secondPlayerId == null || firstPlayerId.equals(secondPlayerId)) return false;
        GuildMember first = guildMemberRepository.findByPlayerId(firstPlayerId).orElse(null);
        GuildMember second = guildMemberRepository.findByPlayerId(secondPlayerId).orElse(null);
        return first != null && second != null && first.getGuildId().equals(second.getGuildId());
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
            item.put("friendshipInvitation", relation.getFriendshipRequestedBy() == null ? "none"
                    : guildId.equals(relation.getFriendshipRequestedBy()) ? "outgoing" : "incoming");
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
