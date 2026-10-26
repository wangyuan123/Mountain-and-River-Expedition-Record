package com.wargame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.SharedIncomingReport;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.SharedIncomingReportRepository;
import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service @RequiredArgsConstructor
public class IncomingReportShareService {
    @org.springframework.beans.factory.annotation.Autowired private RadarService radar;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.config.RetentionPolicy retentionPolicy = new com.wargame.config.RetentionPolicy();
    private final SharedIncomingReportRepository shares;
    private final GuildMemberRepository members;
    private final PlayerRepository players;
    private final WorldViewService views;
    private final GuildChatService chat;
    private final ReportPrivacyService privacy;
    private final ObjectMapper mapper;

    /** 只接受来袭标识，复用本人军情的服务端投影；快照与聊天同事务提交，发送失败不留下分享。 */
    @Transactional
    public ChatDtos.GuildMessageResponse share(Long owner, String incomingId) {
        var member = members.findByPlayerId(owner)
                .orElseThrow(() -> new IllegalArgumentException("尚未加入任何军团"));
        var player = players.findById(owner)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        var incoming = incomingId != null && incomingId.startsWith("radar-")
                ? radar.shareable(owner, Long.valueOf(incomingId.substring(6))) : views.getIncoming(player).stream()
                .filter(item -> Objects.equals(String.valueOf(item.get("id")), incomingId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("来袭军情已结束或无权分享，请刷新军情"));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("incoming", incoming);
        snapshot.put("sharedAt", GameClock.now());
        snapshot.put("ownerName", player.getUsername());
        String token = UUID.randomUUID().toString();
        try {
            shares.save(new SharedIncomingReport(token, owner, member.getGuildId(), mapper.writeValueAsString(snapshot),
                    System.currentTimeMillis() + Math.max(1, retentionPolicy.getIncomingShareHours()) * 3_600_000L));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("来袭军情无法分享");
        }
        return chat.send(owner, "[军情:" + token + "]");
    }

    /** 每次读取都校验接收者仍在原军团；按分享时雷达权限保留快照，不按读者等级重新探明敌军。 */
    @Transactional(readOnly = true)
    public Map<String, Object> read(Long reader, String token) {
        var share = shares.findById(token)
                .orElseThrow(() -> new IllegalArgumentException("分享军情不存在"));
        members.findByPlayerId(reader).filter(member -> share.getGuildId().equals(member.getGuildId()))
                .orElseThrow(() -> new IllegalArgumentException("仅该军团成员可查看此军情"));
        if (share.getExpiresAt() <= System.currentTimeMillis()) throw new IllegalArgumentException("分享军情已过期");
        try {
            Map<String, Object> snapshot = mapper.readValue(share.getData(), new TypeReference<LinkedHashMap<String, Object>>() {});
            privacy.anonymize(List.of(snapshot));
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("分享军情无法读取");
        }
    }
}
