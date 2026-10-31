package com.wargame.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.ArmyUnit;
import com.wargame.model.entity.Mail;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.ArmyUnitRepository;
import com.wargame.repository.MailRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.util.JsonUtil;
import com.wargame.util.PlayerNames;
import com.wargame.model.constants.CommanderRenamePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;

import java.util.*;

/**
 * 邮件服务。
 * <p>
 * 公共 API：
 * <ul>
 *   <li>{@link #listInbox}        - 收件箱</li>
 *   <li>{@link #listOutbox}       - 发件箱</li>
 *   <li>{@link #listSystem}       - 系统邮件</li>
 *   <li>{@link #listUnread}       - 未读邮件</li>
 *   <li>{@link #unreadCount}      - 未读数（用于导航栏红点）</li>
 *   <li>{@link #send}             - 发邮件（玩家间）</li>
 *   <li>{@link #sendSystem}       - 系统发邮件（给指定玩家）</li>
 *   <li>{@link #markRead}         - 标记已读</li>
 *   <li>{@link #claimAttach}      - 领取附件（资源与部队入账）</li>
 *   <li>{@link #delete}           - 删除一封（仅收件人）</li>
 *   <li>{@link #seedForNewPlayer} - 新玩家系统邮件种子（来自 GameStateService.initializeNewPlayer）</li>
 * </ul>
 */
@Service
public class MailService {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private com.wargame.service.TickService resourceEconomy;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private GarrisonFoodService garrisonFood;
    @org.springframework.beans.factory.annotation.Autowired private ArmyPopulationService populationCosts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.repository.PlayerItemRepository playerItems;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.repository.PlayerGuideRepository guides;

    @org.springframework.beans.factory.annotation.Autowired private AccountService accounts;
    @org.springframework.beans.factory.annotation.Autowired private JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.config.RetentionPolicy policy;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.repository.CitySurrenderOfferRepository surrenderOffers;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired private UnitUnlockService unitUnlocks;

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** 允许的附件资源类型 - 与 Resources 字段对齐 */
    private static final Set<String> ALLOWED_ATTACH = Set.of(
            "food", "steel", "oil", "rare", "gold", "diamond");

    private static final TypeReference<List<Map<String, Object>>> ATTACH_LIST_TYPE =
            new TypeReference<>() {};

    private final MailRepository mailRepository;
    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final WebSocketPushService pushService;
    private final ArmyUnitRepository armyUnitRepository;

    public MailService(MailRepository mailRepository,
                       PlayerRepository playerRepository,
                       ResourcesRepository resourcesRepository,
                       WebSocketPushService pushService,
                       ArmyUnitRepository armyUnitRepository) {
        this.mailRepository = mailRepository;
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.pushService = pushService;
        this.armyUnitRepository = armyUnitRepository;
    }

    // ================================================================
    //  列表 / 计数
    // ================================================================

    public List<Map<String, Object>> listInbox(Long playerId) {
        return listPage(playerId, "inbox", null, 100).get("mails") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    public List<Map<String, Object>> listOutbox(Long playerId) {
        return listPage(playerId, "outbox", null, 100).get("mails") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    public List<Map<String, Object>> listSystem(Long playerId) {
        return listPage(playerId, "system", null, 100).get("mails") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    public List<Map<String, Object>> listUnread(Long playerId) {
        return listPage(playerId, "unread", null, 100).get("mails") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    public long unreadCount(Long playerId) {
        long now = System.currentTimeMillis();
        return jdbc.queryForObject("SELECT COUNT(*) FROM mails WHERE to_player_id = ? AND is_read = FALSE " +
                "AND (retention_expires_at IS NULL OR retention_expires_at > ?) " +
                "AND (attachment_policy <> 'TIMED_GIFT' OR is_claimed=TRUE OR cleanup_after IS NULL OR cleanup_after > ?)",
                Long.class, playerId, now, now);
    }

    /** 邮件列表只取预览字段，使用 (created_at,id) 游标避免全量加载正文与附件。 */
    public Map<String, Object> listPage(Long playerId, String folder, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        long now = System.currentTimeMillis();
        long cursorTime = 0, cursorId = 0;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", -1);
            try {
                if (parts.length != 2) throw new NumberFormatException();
                cursorTime = Long.parseLong(parts[0]);
                cursorId = Long.parseLong(parts[1]);
                if (cursorTime < 0 || cursorId < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("邮件分页游标无效");
            }
        }
        StringBuilder sql = new StringBuilder("SELECT id, from_player_id, from_name, to_name, type, is_system, subject, LEFT(body, 120) AS body_preview, " +
                "is_read, is_claimed, created_at, surrender_offer_id, attachment_policy, attachment_expires_at, attachment_expired_at, retention_expires_at, " +
                "(attach_json IS NOT NULL AND attach_json NOT IN ('', '[]', 'null')) AS has_attachment " +
                "FROM mails WHERE ");
        List<Object> args = new ArrayList<>();
        if ("outbox".equals(folder)) {
            sql.append("from_player_id = ? ");
        } else {
            sql.append("to_player_id = ? ");
        }
        args.add(playerId);
        if ("system".equals(folder)) sql.append("AND is_system = TRUE ");
        if ("unread".equals(folder)) {
            sql.append("AND is_read = FALSE ");
        }
        sql.append("AND (retention_expires_at IS NULL OR retention_expires_at > ?) "); args.add(now);
        sql.append("AND (attachment_policy <> 'TIMED_GIFT' OR is_claimed=TRUE OR cleanup_after IS NULL OR cleanup_after > ?) "); args.add(now);
        if (cursor != null && !cursor.isBlank()) {
            sql.append("AND (created_at < ? OR (created_at = ? AND id < ?)) ");
            args.add(cursorTime); args.add(cursorTime); args.add(cursorId);
        }
        sql.append("ORDER BY created_at DESC, id DESC LIMIT ?"); args.add(limit + 1);
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("from", Boolean.TRUE.equals(rs.getObject("is_system")) && "战争指挥部".equals(rs.getString("from_name")) ? "系统" : rs.getString("from_name"));
            item.put("to", rs.getString("to_name"));
            item.put("type", rs.getString("type"));
            item.put("system", rs.getBoolean("is_system"));
            item.put("subject", rs.getString("subject"));
            item.put("bodyPreview", rs.getString("body_preview"));
            item.put("read", rs.getBoolean("is_read"));
            item.put("claimed", rs.getBoolean("is_claimed"));
            item.put("ts", rs.getLong("created_at"));
            item.put("surrenderOfferId", (Long) rs.getObject("surrender_offer_id"));
            item.put("hasAttachment", rs.getBoolean("has_attachment"));
            item.put("attachmentExpiresAt", rs.getObject("attachment_expires_at"));
            item.put("retentionExpiresAt", rs.getObject("retention_expires_at"));
            item.put("attachmentExpired", !rs.getBoolean("is_claimed") && (rs.getObject("attachment_expired_at") != null ||
                    ("TIMED_GIFT".equals(rs.getString("attachment_policy")) && rs.getObject("attachment_expires_at") != null && rs.getLong("attachment_expires_at") <= now)));
            return item;
        }, args.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows.remove(rows.size() - 1);
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Map<String, Object> last = rows.get(rows.size() - 1);
            nextCursor = last.get("ts") + ":" + last.get("id");
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("folder", folder);
        page.put("mails", rows);
        page.put("items", rows);
        page.put("hasMore", hasMore);
        page.put("nextCursor", nextCursor);
        page.put("unread", unreadCount(playerId));
        page.put("retentionPolicy", Map.of("processedReadDays", policy.getProcessedMailDays(), "unreadWithoutPendingAttachmentDays", policy.getUnreadMailDays(),
                "timedGiftGraceDays", policy.getGiftNoticeDays(), "protectedAttachmentsExpire", false));
        return page;
    }

    // ================================================================
    //  发邮件
    // ================================================================

    /**
     * 玩家互发邮件。
     * <p>
     * 校验：
     * <ol>
     *   <li>to 玩家名必须存在</li>
     *   <li>不能给自己发</li>
     *   <li>主题必填，正文可选</li>
     *   <li>若带附件，资源类型合法 + 发件人资源够扣</li>
     * </ol>
     * 成功后通过 WebSocket 推送"收到新邮件"事件给收件人。
     */
    @Transactional
    public Map<String, Object> send(Long fromPlayerId, String toName, String subject,
                                    String body, List<Map<String, Object>> attach) {
        if (toName == null || toName.isBlank()) {
            throw new IllegalArgumentException("收件人不能为空");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        if (subject.length() > 80) {
            throw new IllegalArgumentException("主题不超过 80 字符");
        }

        Player from = playerRepository.findById(fromPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("发件人不存在"));
        Player to = playerRepository.findByDisplayNameKey(CommanderRenamePolicy.nameKey(toName))
                .or(() -> findUnnamedCommander(toName))
                .or(() -> playerRepository.findByUsername(toName))
                .orElseThrow(() -> new IllegalArgumentException("收件人不存在: " + toName));
        if (to.getId().equals(fromPlayerId)) {
            throw new IllegalArgumentException("不能给自己发邮件");
        }

        // 发件也会扣资产；按账号 ID 固定顺序锁定双方，避免相互寄信反向等待或并发透支。
        for (Long id : new TreeSet<>(List.of(fromPlayerId, to.getId()))) accounts.lockPlayer(id);
        from = playerRepository.findById(fromPlayerId).orElseThrow();
        to = playerRepository.findById(to.getId()).orElseThrow();
        if (to.deletionDue(System.currentTimeMillis())) throw new IllegalArgumentException("收件人不存在");

        // 附件校验 + 资源扣减
        List<Map<String, Object>> normalizedAttach = normalizeAndDeductAttach(fromPlayerId, attach);

        Mail mail = new Mail();
        mail.setFromPlayerId(fromPlayerId);
        mail.setFromName(PlayerNames.commanderName(from));
        mail.setToPlayerId(to.getId());
        mail.setToName(PlayerNames.commanderName(to));
        mail.setType("player");
        mail.setIsSystem(false);
        mail.setSubject(subject);
        mail.setBody(body == null ? "" : body);
        mail.setAttachJson(JsonUtil.toJson(normalizedAttach));
        mail.setIsRead(false);
        mail.setIsClaimed(normalizedAttach.isEmpty());
        mail.setAttachmentPolicy(normalizedAttach.isEmpty() ? "NONE" : "PROTECTED");
        mail.setAttachmentSourceType("PLAYER_TRANSFER");
        mail.setAttachmentSourceId(UUID.randomUUID().toString());
        mail.setCreatedAt(System.currentTimeMillis());
        mail = mailRepository.save(mail);

        // 推送 (在线才推)
        pushService.pushToPlayer(to.getId(), "mail", Map.of(
                "id", mail.getId(),
                "from", mail.getFromName(),
                "subject", mail.getSubject()
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "已发送给 " + PlayerNames.commanderName(to));
        result.put("mail", toDto(mail));
        return result;
    }

    /**
     * 系统发邮件（供业务事件钩子调用，例如战斗结算、奖励派发）。
     * 不发 WebSocket 推送以外的副作用；附件由调用方在外部处理资源入账。
     */
    @Transactional
    public Mail sendSystem(Long toPlayerId, String fromName, String type,
                           String subject, String body, List<Map<String, Object>> attach) {
        return sendSystem(toPlayerId, fromName, type, subject, body, attach, null);
    }

    /** System mail variant carrying an interactive city surrender offer reference. */
    @Transactional
    public Mail sendSystem(Long toPlayerId, String fromName, String type,
                           String subject, String body, List<Map<String, Object>> attach,
                           Long surrenderOfferId) {
        if (attach != null && !attach.isEmpty()) log.warn("Unclassified system attachment protected: player={} type={}", toPlayerId, type);
        return sendSystem(toPlayerId, fromName, type, subject, body, attach, surrenderOfferId,
                attach == null || attach.isEmpty() ? "NONE" : "PROTECTED", "LEGACY_UNKNOWN", null, null);
    }

    /** 新增资产入口显式声明来源；限时福利必须带业务 ID 和不可变截止时间。 */
    @Transactional
    public Mail sendSystem(Long toPlayerId, String fromName, String type, String subject, String body,
                           List<Map<String, Object>> attach, Long surrenderOfferId,
                           String attachmentPolicy, String sourceType, String sourceId, Long expiresAt) {
        if (!Set.of("NONE", "PROTECTED", "TIMED_GIFT").contains(attachmentPolicy)) throw new IllegalArgumentException("附件策略无效");
        if ("TIMED_GIFT".equals(attachmentPolicy) && (expiresAt == null || sourceId == null || expiresAt <= System.currentTimeMillis())) throw new IllegalArgumentException("限时赠礼必须有有效业务来源和领取期限");
        Player to = playerRepository.findById(toPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("收件人不存在: " + toPlayerId));
        to = accounts.lockPlayer(toPlayerId);
        if (to.deletionDue(System.currentTimeMillis())) return null;
        Mail mail = new Mail();
        mail.setFromPlayerId(null);
        mail.setFromName(fromName == null ? "系统" : fromName);
        mail.setToPlayerId(to.getId());
        mail.setToName(PlayerNames.commanderName(to));
        mail.setType(type == null ? "system" : type);
        mail.setIsSystem(true);
        mail.setSubject(subject == null ? "(无主题)" : subject);
        mail.setBody(body == null ? "" : body);
        mail.setAttachJson(JsonUtil.toJson(attach == null ? List.of() : attach));
        mail.setIsRead(false);
        mail.setIsClaimed(false);
        mail.setCreatedAt(System.currentTimeMillis());
        mail.setSurrenderOfferId(surrenderOfferId);
        mail.setAttachmentPolicy(attachmentPolicy);
        mail.setAttachmentSourceType(sourceType == null ? "LEGACY_UNKNOWN" : sourceType);
        mail.setAttachmentSourceId(sourceId);
        mail.setAttachmentExpiresAt(expiresAt);
        mail = mailRepository.save(mail);
        pushService.pushToPlayer(to.getId(), "mail", Map.of(
                "id", mail.getId(),
                "from", mail.getFromName(),
                "subject", mail.getSubject()
        ));
        return mail;
    }

    // ================================================================
    //  标记 / 领取 / 删除
    // ================================================================

    @Transactional
    public void markRead(Long playerId, Long mailId) {
        Mail mail = mailRepository.findByIdForUpdate(mailId).orElse(null);
        if (mail == null || !playerId.equals(mail.getToPlayerId())) {
            // 不抛错: 标记已读对前端 UI 是幂等的
            log.debug("markRead noop: player={} mail={} (not owner or already read)", playerId, mailId);
            return;
        }
        long now = System.currentTimeMillis();
        if (mail.getRetentionExpiresAt() != null && mail.getRetentionExpiresAt() <= now) return;
        if (!Boolean.TRUE.equals(mail.getIsRead())) {
            mail.setIsRead(true);
            updateRetention(mail, now);
            mailRepository.saveAndFlush(mail);
        }
    }

    /** 数据库批量已读，不加载正文；有效关联业务和待领资产不启动普通保留倒计时。 */
    @Transactional
    public int markAllRead(Long playerId) {
        entityManager.flush();
        long now = System.currentTimeMillis();
        String processed = "(is_claimed=TRUE OR attach_json IS NULL OR TRIM(attach_json) IN ('','[]','null')) AND NOT EXISTS (SELECT 1 FROM city_surrender_offers o WHERE o.id=mails.surrender_offer_id AND o.status IN ('PENDING','ACCEPTED'))";
        int changed = jdbc.update("UPDATE mails SET processed_real_at=CASE WHEN " + processed + " THEN ? ELSE processed_real_at END, " +
                "retention_expires_at=CASE WHEN " + processed + " THEN ?+processed_retention_ms ELSE retention_expires_at END,is_read=TRUE WHERE to_player_id=? AND is_read=FALSE AND (retention_expires_at IS NULL OR retention_expires_at>?)", now, now, playerId, now);
        entityManager.clear();
        return changed;
    }

    /**
     * 领取附件：把 attach_json 里的资源加到收件人 resources 表，标记 is_claimed=true。
     * 所有邮件附件都只能领取一次；道具附件仅允许系统邮件和显式白名单。
     */
    @Transactional
    public Map<String, Object> claimAttach(Long playerId, Long mailId) {
        accounts.lockPlayer(playerId);
        Mail mail = mailRepository.findByIdForUpdate(mailId)
                .orElseThrow(() -> new IllegalArgumentException("邮件不存在"));
        if (!mail.getToPlayerId().equals(playerId)) {
            throw new IllegalArgumentException("无权领取该邮件附件");
        }
        if (Boolean.TRUE.equals(mail.getIsClaimed())) {
            throw new IllegalArgumentException("附件已领取");
        }
        if (expiredGift(mail, System.currentTimeMillis())) return expireGift(mail, System.currentTimeMillis());
        List<Map<String, Object>> attach = parseAttach(mail.getAttachJson());
        if (attach.isEmpty()) {
            throw new IllegalArgumentException("该邮件没有附件");
        }

        Map<String, Long> resDeltas = new LinkedHashMap<>();
        Map<String, Integer> armyDeltas = new LinkedHashMap<>();
        Map<String, Integer> itemDeltas = new LinkedHashMap<>();

        for (Map<String, Object> a : attach) {
            String t = String.valueOf(a.get("type"));
            int qty = attachmentQuantity(a.get("qty"));

            if ("item".equals(t)) {
                String itemId = String.valueOf(a.get("itemId"));
                if (!Boolean.TRUE.equals(mail.getIsSystem()) ||
                        !com.wargame.model.constants.CommanderRenamePolicy.ITEM_KEY.equals(itemId))
                    throw new IllegalArgumentException("不支持的道具附件");
                mergeCount(itemDeltas, itemId, qty);
            } else if (ALLOWED_ATTACH.contains(t)) {
                mergeResource(resDeltas, t, qty);
            } else if (UnitDef.UNITS.containsKey(t)) {
                mergeCount(armyDeltas, t, qty);
            } else if ("army".equalsIgnoreCase(t)) {
                String unit = a.containsKey("unit") ? String.valueOf(a.get("unit")) : String.valueOf(a.get("unitType"));
                if (UnitDef.UNITS.containsKey(unit)) {
                    mergeCount(armyDeltas, unit, qty);
                } else {
                    throw new IllegalArgumentException("不支持的部队附件");
                }
            } else if (t.startsWith("army_")) {
                String unit = t.substring(5);
                if (UnitDef.UNITS.containsKey(unit)) {
                    mergeCount(armyDeltas, unit, qty);
                } else {
                    throw new IllegalArgumentException("不支持的部队附件");
                }
            } else {
                throw new IllegalArgumentException("不支持的附件类型");
            }
        }

        // 整封附件先完成资格检查，未达标时资源、部队与待领取标记全部保留。
        List<UnitUnlockService.Result> blocked = armyDeltas.keySet().stream()
                .map(unit -> unitUnlocks.evaluate(playerId, cityScope.slot(playerId), unit, true))
                .filter(unlock -> !unlock.eligible()).toList();
        if (!blocked.isEmpty()) {
            return Map.of("success", false, "message", blocked.stream().map(unitUnlocks::message).reduce((a, b) -> a + "；" + b).orElse(""),
                    "unlocks", blocked, "missingPrerequisites", blocked.stream().flatMap(u -> u.missing().stream()).toList());
        }
        // 必要锁及资格检查之后重新取真实时间；锁等待和慢检查不能绕过领取截止。
        long now = System.currentTimeMillis();
        if (expiredGift(mail, now)) return expireGift(mail, now);
        // 奖兵前先结清旧兵力粮账，混合附件中的粮食也不能提前抵扣历史耗粮。
        if (!armyDeltas.isEmpty()) cityScope.selected(playerId).ifPresent(c -> garrisonFood.activate(c, com.wargame.util.GameClock.now()));
        validateArmyAndItems(playerId, armyDeltas, itemDeltas);
        if (!resDeltas.isEmpty()) {
            applyResources(playerId, resDeltas, +1);
        }
        if (!armyDeltas.isEmpty()) {
            applyArmyUnits(playerId, armyDeltas);
        }
        for (Map.Entry<String, Integer> delta : itemDeltas.entrySet()) {
            com.wargame.model.entity.PlayerItem item = playerItems.findByPlayerIdAndItemKey(playerId, delta.getKey())
                    .orElseGet(() -> {
                        var created = new com.wargame.model.entity.PlayerItem();
                        created.setPlayerId(playerId);
                        created.setItemKey(delta.getKey());
                        created.setCount(0);
                        return created;
                    });
            item.setCount(Math.addExact(item.getCount(), delta.getValue()));
            item.setUpdatedAt(now);
            playerItems.save(item);
        }

        mail.setIsClaimed(true);
        mail.setIsRead(true);
        mail.setAttachmentPolicy("NONE");
        mail.setCleanupAfter(null);
        mail.setRetentionExpiresAt(null);
        updateRetention(mail, now);
        mailRepository.saveAndFlush(mail);
        log.info("Mail claim: player={} mail={} attach={}", playerId, mailId, attach);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "附件已领取");
        result.put("mailId", mailId);
        return result;
    }

    @Transactional
    public void delete(Long playerId, Long mailId) {
        int n = mailRepository.deleteByIdAndOwner(mailId, playerId);
        if (n == 0) {
            // 不存在或不归该玩家所有, 直接吞掉错误, 避免前端做一次额外查询
            log.debug("delete mail noop: player={} mail={}", playerId, mailId);
        }
    }

    /** 按需读取邮件正文与附件；列表永远不加载这些完整字段。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long playerId, Long mailId) {
        Mail mail = mailRepository.findOwned(mailId, playerId)
                .orElseThrow(() -> new IllegalArgumentException("邮件不存在或无权查看"));
        long now = System.currentTimeMillis();
        if ((mail.getRetentionExpiresAt() != null && mail.getRetentionExpiresAt() <= now) ||
                (!Boolean.TRUE.equals(mail.getIsClaimed()) && "TIMED_GIFT".equals(mail.getAttachmentPolicy()) && mail.getCleanupAfter() != null && mail.getCleanupAfter() <= now)) {
            throw new IllegalArgumentException("邮件已过期");
        }
        Map<String, Object> result = toDto(mail);
        result.put("attachmentExpired", mail.getAttachmentExpiredAt() != null ||
                ("TIMED_GIFT".equals(mail.getAttachmentPolicy()) && mail.getAttachmentExpiresAt() != null && mail.getAttachmentExpiresAt() <= now));
        result.put("attachmentExpiresAt", mail.getAttachmentExpiresAt());
        return result;
    }

    private boolean expiredGift(Mail mail, long now) {
        return !Boolean.TRUE.equals(mail.getIsClaimed()) && "TIMED_GIFT".equals(mail.getAttachmentPolicy()) && mail.getAttachmentExpiresAt() != null && mail.getAttachmentExpiresAt() <= now;
    }

    private Map<String, Object> expireGift(Mail mail, long now) {
        mail.setAttachmentExpiredAt(mail.getAttachmentExpiredAt() == null ? now : mail.getAttachmentExpiredAt());
        if (mail.getCleanupAfter() == null) mail.setCleanupAfter(mail.getAttachmentExpiresAt() + policy.days(policy.getGiftNoticeDays()));
        mail.setRetentionExpiresAt(mail.getCleanupAfter());
        mailRepository.saveAndFlush(mail);
        return new LinkedHashMap<>(Map.of("success", false, "message", "附件已过期"));
    }

    private boolean activeBusiness(Mail mail) {
        return mail.getSurrenderOfferId() != null && surrenderOffers.findById(mail.getSurrenderOfferId())
                .map(o -> Set.of("PENDING", "ACCEPTED").contains(o.getStatus())).orElse(false);
    }

    /** 关联业务终结后才开始计时；不使用可暂停的游戏时间反推真实期限。 */
    private void updateRetention(Mail mail, long now) {
        if (activeBusiness(mail)) { mail.setRetentionExpiresAt(null); mail.setProcessedRealAt(null); return; }
        if (expiredGift(mail, now)) { expireGift(mail, now); return; }
        if (mail.hasPendingAttachment()) return;
        if (Boolean.TRUE.equals(mail.getIsRead())) {
            if (mail.getProcessedRealAt() == null) mail.setProcessedRealAt(now);
            mail.setRetentionExpiresAt(mail.getProcessedRealAt() + mail.getProcessedRetentionMs());
        } else if (mail.getRetentionExpiresAt() == null) {
            mail.setRetentionExpiresAt(mail.getCreatedAt() + mail.getUnreadRetentionMs());
        }
    }

    /** 业务终结和邮件期限在同一事务提交；只锁邮件，不在邮件锁后反向锁账号。 */
    @Transactional
    public void businessEnded(Long offerId, long endedAt) {
        entityManager.flush();
        jdbc.update("UPDATE mails SET processed_real_at=CASE WHEN is_read=TRUE THEN ? ELSE NULL END,retention_expires_at=CASE WHEN is_read=TRUE THEN ?+processed_retention_ms ELSE created_at+unread_retention_ms END WHERE surrender_offer_id=? AND retention_expires_at IS NULL AND (is_claimed=TRUE OR attach_json IS NULL OR TRIM(attach_json) IN ('','[]','null'))", endedAt, endedAt, offerId);
        // JDBC 批量更新后刷新实体，避免同一事务已有邮件对象在提交时覆盖期限。
        entityManager.clear();
    }

    /** 后台与领取共用账号→邮件锁顺序；终结状态不可逆，复核后才允许删除。 */
    @Transactional
    public boolean refreshRetention(long id, long now) {
        Long player = jdbc.query("SELECT to_player_id FROM mails WHERE id=?", rs -> rs.next() ? rs.getLong(1) : null, id);
        if (player == null) return false;
        accounts.lockPlayer(player);
        Mail mail = mailRepository.findByIdForUpdate(id).orElse(null);
        if (mail == null || activeBusiness(mail)) return false;
        Long before = mail.getRetentionExpiresAt();
        if (mail.getSurrenderOfferId() != null && mail.getProcessedRealAt() == null && Boolean.TRUE.equals(mail.getIsRead()) && !mail.hasPendingAttachment()) {
            surrenderOffers.findById(mail.getSurrenderOfferId()).map(com.wargame.model.entity.CitySurrenderOffer::getEndedRealAt)
                    .ifPresent(mail::setProcessedRealAt);
        }
        updateRetention(mail, now);
        mailRepository.saveAndFlush(mail);
        return !Objects.equals(before, mail.getRetentionExpiresAt());
    }

    @Transactional
    public boolean deleteExpired(long id, long now) {
        refreshRetention(id, now);
        Mail mail = mailRepository.findByIdForUpdate(id).orElse(null);
        if (mail == null || activeBusiness(mail) || mail.hasPendingAttachment() || mail.getRetentionExpiresAt() == null || mail.getRetentionExpiresAt() > now) return false;
        mailRepository.delete(mail);
        mailRepository.flush();
        return true;
    }

    // ================================================================
    //  新玩家种子邮件
    // ================================================================

    /**
     * 首次进入游戏只发欢迎、通告及改名卡；完成礼包由引导毕业事件发放。
     * 由 {@link GameStateService#initializeNewPlayer} 调用。
     */
    @Transactional
    public void seedForNewPlayer(Long playerId) {
        long existing = mailRepository.countByToPlayerIdAndIsReadFalse(playerId);
        issueCommanderRenameGift(playerId);
        long now = System.currentTimeMillis();
        // 避免重复种子 (重置账号时也会再次调用)
        if (existing > 0) {
            return;
        }
        Player p = playerRepository.findById(playerId).orElse(null);
        if (p == null) return;

        Mail welcome = new Mail();
        welcome.setFromPlayerId(null);
        welcome.setFromName("系统");
        welcome.setToPlayerId(playerId);
        welcome.setToName(PlayerNames.commanderName(p));
        welcome.setType("system");
        welcome.setIsSystem(true);
        welcome.setSubject("欢迎加入战局");
        welcome.setBody("指挥官您好，您已成功入伍。本邮件附赠黄金×10000，请领取附件；初始道具礼包请前往【仓库】查收。"
                + "完成主城建造后，将陆续解锁更多军事设施与功能。祝您战运亨通！");
        welcome.setAttachJson(JsonUtil.toJson(List.of(
                // 欢迎与真实毕业两封固定资源邮件各1万黄金，合计2万，仍需主动领取。
                Map.of("type", "gold", "qty", 10000)
        )));
        welcome.setIsRead(false);
        welcome.setIsClaimed(false);
        welcome.setAttachmentPolicy("PROTECTED");
        welcome.setAttachmentSourceType("NEW_PLAYER");
        welcome.setAttachmentSourceId("welcome:" + playerId);
        welcome.setCreatedAt(now - 3600_000L);
        mailRepository.save(welcome);

        Mail announce = new Mail();
        announce.setFromPlayerId(null);
        announce.setFromName("远征军司令部");
        announce.setToPlayerId(playerId);
        announce.setToName(PlayerNames.commanderName(p));
        announce.setType("alliance");
        announce.setIsSystem(true);
        announce.setSubject("战区通告：滇缅战备指引");
        announce.setBody("指挥官，您已抵达滇缅战区，将与英美盟军协同对日作战，共同守护滇缅公路这条国际援华通道。"
                + "请先建设前进基地，保障粮食、钢铁与石油供应，再逐步训练部队、侦察周边敌情，为后续协同作战做好准备。");
        announce.setAttachJson("[]");
        announce.setIsRead(true);
        announce.setIsClaimed(true);
        announce.setCreatedAt(now - 3 * 3600_000L);
        mailRepository.save(announce);
    }

    /** 真实毕业后寄送一次完成礼包；永久回执防止领完删信后重发，并兼容已提前寄出的旧邮件。 */
    @Transactional
    public void issueOnboardingCompletionGift(Long playerId) {
        accounts.lockPlayer(playerId);
        if (guides.findByPlayerIdAndStepId(playerId, "ob3_completed").isEmpty()) {
            throw new IllegalArgumentException("请先完成新手指引");
        }
        String receipt = "ob3_reward_completion_mail";
        if (guides.findByPlayerIdAndStepId(playerId, receipt).isPresent()) return;
        String sourceId = "reward:" + playerId;
        // 沿用旧来源编号，不向已收到或已领取旧邮件的玩家重复发放。
        if (!mailRepository.existsByToPlayerIdAndAttachmentSourceTypeAndAttachmentSourceId(playerId, "NEW_PLAYER", sourceId)) {
            Mail gift = sendSystem(playerId, "系统", "reward", "新手礼包已发放",
                    "感谢您完成新手指引！礼包内容：黄金×10000 / 钻石×50。请领取本邮件附件。",
                    List.of(Map.of("type", "gold", "qty", 10000), Map.of("type", "diamond", "qty", 50)),
                    null, "PROTECTED", "NEW_PLAYER", sourceId, null);
            if (gift == null) return;
        }
        guides.save(new com.wargame.model.entity.PlayerGuide(playerId, receipt, "done", System.currentTimeMillis()));
    }

    // ================================================================
    //  内部 helpers
    // ================================================================

    /** 玩家行锁和永久标记保证每个账号一份；未领取附件不设置过期时间。 */
    @Transactional
    public void issueCommanderRenameGift(Long playerId) {
        Player player = accounts.lockPlayer(playerId);
        if (player.isCommanderRenameGiftSent() || Boolean.TRUE.equals(player.getIsBot()) || "DELETED".equals(player.getAccountStatus())) return;
        Mail gift = sendSystem(playerId, "系统", "reward", "统帅改名卡赠礼",
                "赠送您1张统帅改名卡。请领取附件后前往仓库使用。首次用卡无需等待，此后每次成功改名需间隔30天（现实时间）；改名卡暂不在商城出售。",
                List.of(Map.of("type", "item", "itemId", "commanderRenameCard", "qty", 1)), null,
                "PROTECTED", "COMMANDER_RENAME_GIFT", "commander-rename:" + playerId, null);
        if (gift == null) return;
        player.setCommanderRenameGiftSent(true);
        playerRepository.save(player);
    }

    private Optional<Player> findUnnamedCommander(String name) {
        if (!name.matches("统帅[1-9][0-9]*")) return Optional.empty();
        try {
            return playerRepository.findById(Long.parseLong(name.substring(2)))
                    .filter(player -> player.getDisplayName() == null || player.getDisplayName().isBlank());
        } catch (NumberFormatException invalidId) {
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> normalizeAndDeductAttach(Long fromPlayerId, List<Map<String, Object>> attach) {
        if (attach == null || attach.isEmpty()) return List.of();
        if (attach.size() > 6) throw new IllegalArgumentException("附件最多 6 项");

        // 先校验 + 累计需要扣的资源
        Map<String, Long> need = new LinkedHashMap<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> a : attach) {
            Object t = a.get("type");
            Object q = a.get("qty");
            if (t == null || q == null) throw new IllegalArgumentException("附件格式错误");
            String type = t.toString();
            if (!ALLOWED_ATTACH.contains(type)) {
                throw new IllegalArgumentException("不支持的附件类型: " + type);
            }
            int qty = attachmentQuantity(q);
            mergeResource(need, type, qty);
            normalized.add(Map.of("type", type, "qty", qty));
        }
        resourceEconomy.beforeResourceChange(fromPlayerId, cityScope.slot(fromPlayerId));
        // 校验余额
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(fromPlayerId, cityScope.slot(fromPlayerId))
                .orElseThrow(() -> new IllegalArgumentException("发件人资源不存在"));
        for (Map.Entry<String, Long> e : need.entrySet()) {
            long have = getRes("diamond".equals(e.getKey()) ? cityScope.wallet(fromPlayerId) : res, e.getKey());
            if (have < e.getValue()) {
                throw new IllegalArgumentException("资源不足: " + labelOf(e.getKey())
                        + " (需要 " + e.getValue() + ", 现有 " + have + ")");
            }
        }
        // 扣减
        applyResources(fromPlayerId, need, -1);
        return normalized;
    }

    /** 整组余额先校验后写入；资源使用 long，钻石仍遵守账号钱包的 int 存储上限。 */
    private void applyResources(Long playerId, Map<String, Long> delta, int sign) {
        if (delta == null || delta.isEmpty()) return;
        resourceEconomy.beforeResourceChange(playerId, cityScope.slot(playerId));
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))
                .orElseThrow(() -> new IllegalArgumentException("玩家资源不存在: " + playerId));
        Map<String, Long> after = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : delta.entrySet()) {
            Resources balance = "diamond".equals(e.getKey()) ? cityScope.wallet(playerId) : res;
            long current = getRes(balance, e.getKey()), amount = e.getValue();
            long limit = "diamond".equals(e.getKey()) ? Integer.MAX_VALUE : Long.MAX_VALUE;
            if (amount <= 0 || (sign < 0 ? current < amount : current > limit - amount))
                throw new IllegalArgumentException("附件数量或" + labelOf(e.getKey()) + "余额超出范围");
            after.put(e.getKey(), sign < 0 ? current - amount : current + amount);
        }
        for (Map.Entry<String, Long> e : after.entrySet()) {
            Resources balance = "diamond".equals(e.getKey()) ? cityScope.wallet(playerId) : res;
            if ("diamond".equals(e.getKey())) {
                balance.setDiamond(e.getValue().intValue());
            } else {
                switch (e.getKey()) {
                    case "food" -> balance.setFood(e.getValue()); case "steel" -> balance.setSteel(e.getValue());
                    case "oil" -> balance.setOil(e.getValue()); case "rare" -> balance.setRare(e.getValue());
                    case "gold" -> balance.setGold(e.getValue());
                    default -> throw new IllegalArgumentException("不支持的附件资源");
                }
            }
            resourcesRepository.save(balance);
        }
        resourcesRepository.save(res);
    }

    /** 单项维持既有正整数契约，拒绝小数和截断后变正的大数。 */
    private static int attachmentQuantity(Object value) {
        try {
            int amount = Integer.parseInt(String.valueOf(value));
            if (amount > 0) return amount;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("附件数量必须为有效正整数");
    }

    /** 同类资源可以重复，但总数必须在真实存储类型范围内。 */
    private static void mergeResource(Map<String, Long> totals, String type, int quantity) {
        long sum;
        try { sum = Math.addExact(totals.getOrDefault(type, 0L), quantity); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("附件总量超出范围"); }
        if ("diamond".equals(type) && sum > Integer.MAX_VALUE) throw new IllegalArgumentException("钻石附件总量超出范围");
        totals.put(type, sum);
    }

    private static void mergeCount(Map<String, Integer> totals, String key, int quantity) {
        try { totals.merge(key, quantity, Math::addExact); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("附件总量超出范围"); }
    }

    /** 在任何发奖前检查部队和道具库存，防止混合附件只发一部分后才发现溢出。 */
    private void validateArmyAndItems(Long player, Map<String, Integer> army, Map<String, Integer> items) {
        for (var e : army.entrySet()) {
            long stock = armyUnitRepository.findByPlayerIdAndCitySlotAndType(player, cityScope.slot(player), e.getKey()).stream()
                    .mapToLong(a -> Objects.requireNonNullElse(a.getCount(), 0)).sum();
            if (stock > Integer.MAX_VALUE - (long) e.getValue()) throw new IllegalArgumentException("部队数量超出范围");
        }
        for (var e : items.entrySet()) {
            int stock = playerItems.findByPlayerIdAndItemKey(player, e.getKey()).map(i -> Objects.requireNonNullElse(i.getCount(), 0)).orElse(0);
            if (stock > Integer.MAX_VALUE - (long) e.getValue()) throw new IllegalArgumentException("道具数量超出范围");
        }
    }

    private void applyArmyUnits(Long playerId, Map<String, Integer> armyDeltas) {
        if (armyDeltas == null || armyDeltas.isEmpty()) return;
        int citySlot = cityScope != null ? cityScope.slot(playerId) : 0;
        for (Map.Entry<String, Integer> entry : armyDeltas.entrySet()) {
            String unitType = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) continue;
            populationCosts.grant(playerId, unitType, count);
            List<ArmyUnit> list = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, citySlot, unitType);
            ArmyUnit u;
            if (list.isEmpty()) {
                u = new ArmyUnit();
                u.setPlayerId(playerId);
                u.setCitySlot(citySlot);
                u.setType(unitType);
                u.setCount(count);
            } else {
                u = list.get(0);
                u.setCount((u.getCount() != null ? u.getCount() : 0) + count);
            }
            armyUnitRepository.save(u);
        }
    }

    private static long getRes(Resources r, String key) {
        return switch (key) {
            case "food"    -> r.getFood()    != null ? r.getFood()    : 0L;
            case "steel"   -> r.getSteel()   != null ? r.getSteel()   : 0L;
            case "oil"     -> r.getOil()     != null ? r.getOil()     : 0L;
            case "rare"    -> r.getRare()    != null ? r.getRare()    : 0L;
            case "gold"    -> r.getGold()    != null ? r.getGold()    : 0L;
            case "diamond" -> r.getDiamond() != null ? r.getDiamond() : 0L;
            default -> 0L;
        };
    }

    private static void setRes(Resources r, String key, int v) {
        switch (key) {
            case "food"    -> r.setFood(v);
            case "steel"   -> r.setSteel(v);
            case "oil"     -> r.setOil(v);
            case "rare"    -> r.setRare(v);
            case "gold"    -> r.setGold(v);
            case "diamond" -> r.setDiamond(v);
            default -> { /* ignore */ }
        }
    }

    private static String labelOf(String key) {
        return switch (key) {
            case "food"    -> "粮食";
            case "steel"   -> "钢铁";
            case "oil"     -> "石油";
            case "rare"    -> "稀矿";
            case "gold"    -> "黄金";
            case "diamond" -> "钻石";
            default -> UnitDef.UNITS.containsKey(key) ? UnitDef.UNITS.get(key).name() : key;
        };
    }

    private List<Map<String, Object>> parseAttach(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JsonUtil.getMapper().readValue(json, ATTACH_LIST_TYPE);
        } catch (Exception e) {
            log.warn("parseAttach failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> toDto(List<Mail> mails) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Mail m : mails) out.add(toDto(m));
        return out;
    }

    private Map<String, Object> toDto(Mail m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", m.getId());
        // 兼容历史宣战通知，列表和详情统一使用新的系统发件人名称。
        d.put("from", Boolean.TRUE.equals(m.getIsSystem()) && "战争指挥部".equals(m.getFromName())
                ? "系统" : m.getFromName());
        d.put("to", m.getToName());
        d.put("type", m.getType());
        d.put("system", Boolean.TRUE.equals(m.getIsSystem()));
        d.put("subject", m.getSubject());
        d.put("body", m.getBody());
        d.put("attach", parseAttach(m.getAttachJson()));
        d.put("read", Boolean.TRUE.equals(m.getIsRead()));
        d.put("claimed", Boolean.TRUE.equals(m.getIsClaimed()));
        d.put("ts", m.getCreatedAt());
        d.put("surrenderOfferId", m.getSurrenderOfferId());
        d.put("attachmentExpired", m.getAttachmentExpiredAt() != null);
        d.put("retentionExpiresAt", m.getRetentionExpiresAt());
        d.put("attachmentExpiresAt", m.getAttachmentExpiresAt());
        return d;
    }
}
