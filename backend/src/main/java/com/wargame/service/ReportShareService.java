package com.wargame.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.SharedReport;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class ReportShareService {
    private final ScoutReportRepository reports;
    private final SharedReportRepository shares;
    private final ChatService world;
    private final GuildChatService guild;
    private final PrivateChatService privateChat;
    private final ReportPrivacyService privacy;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.config.RetentionPolicy retentionPolicy = new com.wargame.config.RetentionPolicy();

    /** 分享和频道发言在同一事务内，资格检查失败不生成可访问凭证。 */
    @Transactional
    public void share(Long owner, Long reportId, String channel, Long recipient) {
        var report = reports.findById(reportId).filter(r -> owner.equals(r.getPlayerId()))
                .orElseThrow(() -> new IllegalArgumentException("战报不存在或无权分享"));
        if (!"battle".equals(report.getType())) throw new IllegalArgumentException("目前仅支持分享战斗战报");
        long now = System.currentTimeMillis();
        if (report.getData() == null || report.getDetailExpiresAt() == null ||
                (report.getStarredAt() == null && report.getDetailExpiresAt() <= now)) {
            throw new IllegalArgumentException("战报详情已过期，无法分享");
        }
        long expiresAt = report.getStarredAt() == null
                ? Math.min(now + retentionPolicy.days(retentionPolicy.getReportShareDays()), report.getDetailExpiresAt())
                : now + retentionPolicy.days(retentionPolicy.getReportShareDays());
        String token = UUID.randomUUID().toString();
        shares.save(new SharedReport(token, reportId, owner, expiresAt));
        String content = "[战报:" + token + "]";
        switch (channel == null ? "" : channel) {
            case "world" -> world.send(owner, content);
            case "guild" -> guild.send(owner, content);
            case "private" -> privateChat.send(owner, recipient, content);
            default -> throw new IllegalArgumentException("请选择分享频道");
        }
    }

    /** 登录玩家凭随机链接读取分享战报，保留注销名称脱敏规则。 */
    @Transactional(readOnly = true)
    public Map<String, Object> read(String token) {
        var share = shares.findById(token).orElseThrow(() -> new IllegalArgumentException("分享战报不存在"));
        if (share.getExpiresAt() <= System.currentTimeMillis()) throw new IllegalArgumentException("分享战报已过期");
        var report = reports.findById(share.getReportId()).filter(r -> share.getOwnerId().equals(r.getPlayerId()))
                .orElseThrow(() -> new IllegalArgumentException("分享战报已失效"));
        if (report.getData() == null || report.getDetailExpiresAt() == null ||
                (report.getStarredAt() == null && report.getDetailExpiresAt() <= System.currentTimeMillis())) {
            throw new IllegalArgumentException("分享战报已过期");
        }
        try {
            Map<String, Object> result = mapper.readValue(report.getData(), new TypeReference<LinkedHashMap<String, Object>>() {});
            result.put("id", report.getId());
            result.put("type", "battle");
            result.put("time", report.getCreatedAt());
            result.put("expiresAt", report.getStarredAt() == null ? Math.min(share.getExpiresAt(), report.getDetailExpiresAt()) : share.getExpiresAt());
            privacy.anonymize(List.of(result));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("战报内容无法读取");
        }
    }
}
