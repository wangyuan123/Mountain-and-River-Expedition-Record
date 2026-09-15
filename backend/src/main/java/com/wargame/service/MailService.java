package com.wargame.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wargame.model.entity.Mail;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Resources;
import com.wargame.repository.MailRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li>{@link #claimAttach}      - 领取附件（资源入账）</li>
 *   <li>{@link #delete}           - 删除一封（仅收件人）</li>
 *   <li>{@link #seedForNewPlayer} - 新玩家系统邮件种子（来自 GameStateService.initializeNewPlayer）</li>
 * </ul>
 */
@Service
public class MailService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

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

    public MailService(MailRepository mailRepository,
                       PlayerRepository playerRepository,
                       ResourcesRepository resourcesRepository,
                       WebSocketPushService pushService) {
        this.mailRepository = mailRepository;
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.pushService = pushService;
    }

    // ================================================================
    //  列表 / 计数
    // ================================================================

    public List<Map<String, Object>> listInbox(Long playerId) {
        return toDto(mailRepository.findByToPlayerIdOrderByCreatedAtDesc(playerId));
    }

    public List<Map<String, Object>> listOutbox(Long playerId) {
        return toDto(mailRepository.findByFromPlayerIdOrderByCreatedAtDesc(playerId));
    }

    public List<Map<String, Object>> listSystem(Long playerId) {
        return toDto(mailRepository.findByToPlayerIdAndIsSystemTrueOrderByCreatedAtDesc(playerId));
    }

    public List<Map<String, Object>> listUnread(Long playerId) {
        List<Map<String, Object>> all = toDto(mailRepository.findByToPlayerIdOrderByCreatedAtDesc(playerId));
        all.removeIf(m -> Boolean.TRUE.equals(m.get("read")));
        return all;
    }

    public long unreadCount(Long playerId) {
        return mailRepository.countByToPlayerIdAndIsReadFalse(playerId);
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
        Player to = playerRepository.findByUsername(toName)
                .orElseThrow(() -> new IllegalArgumentException("收件人不存在: " + toName));
        if (to.getId().equals(fromPlayerId)) {
            throw new IllegalArgumentException("不能给自己发邮件");
        }

        // 附件校验 + 资源扣减
        List<Map<String, Object>> normalizedAttach = normalizeAndDeductAttach(fromPlayerId, attach);

        Mail mail = new Mail();
        mail.setFromPlayerId(fromPlayerId);
        mail.setFromName(from.getUsername());
        mail.setToPlayerId(to.getId());
        mail.setToName(to.getUsername());
        mail.setType("player");
        mail.setIsSystem(false);
        mail.setSubject(subject);
        mail.setBody(body == null ? "" : body);
        mail.setAttachJson(JsonUtil.toJson(normalizedAttach));
        mail.setIsRead(false);
        mail.setIsClaimed(normalizedAttach.isEmpty());
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
        result.put("message", "已发送给 " + to.getUsername());
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
        Player to = playerRepository.findById(toPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("收件人不存在: " + toPlayerId));
        Mail mail = new Mail();
        mail.setFromPlayerId(null);
        mail.setFromName(fromName == null ? "系统" : fromName);
        mail.setToPlayerId(to.getId());
        mail.setToName(to.getUsername());
        mail.setType(type == null ? "system" : type);
        mail.setIsSystem(true);
        mail.setSubject(subject == null ? "(无主题)" : subject);
        mail.setBody(body == null ? "" : body);
        mail.setAttachJson(JsonUtil.toJson(attach == null ? List.of() : attach));
        mail.setIsRead(false);
        mail.setIsClaimed(false);
        mail.setCreatedAt(System.currentTimeMillis());
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
        int n = mailRepository.markRead(mailId, playerId);
        if (n == 0) {
            // 不抛错: 标记已读对前端 UI 是幂等的
            log.debug("markRead noop: player={} mail={} (not owner or already read)", playerId, mailId);
        }
    }

    /**
     * 领取附件：把 attach_json 里的资源加到收件人 resources 表，标记 is_claimed=true。
     * 系统邮件不限制领取次数，玩家邮件只能领一次。
     */
    @Transactional
    public Map<String, Object> claimAttach(Long playerId, Long mailId) {
        Mail mail = mailRepository.findById(mailId)
                .orElseThrow(() -> new IllegalArgumentException("邮件不存在"));
        if (!mail.getToPlayerId().equals(playerId)) {
            throw new IllegalArgumentException("无权领取该邮件附件");
        }
        if (Boolean.TRUE.equals(mail.getIsClaimed())) {
            throw new IllegalArgumentException("附件已领取");
        }
        List<Map<String, Object>> attach = parseAttach(mail.getAttachJson());
        if (attach.isEmpty()) {
            throw new IllegalArgumentException("该邮件没有附件");
        }
        applyResources(playerId, attach, +1);
        mailRepository.markClaimed(mailId, playerId);
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

    // ================================================================
    //  新玩家种子邮件
    // ================================================================

    /**
     * 新玩家首次注册时塞入的系统邮件种子。
     * 由 {@link GameStateService#initializeNewPlayer} 调用。
     */
    @Transactional
    public void seedForNewPlayer(Long playerId) {
        long now = System.currentTimeMillis();
        // 避免重复种子 (重置账号时也会再次调用)
        long existing = mailRepository.countByToPlayerIdAndIsReadFalse(playerId);
        if (existing > 0) {
            return;
        }
        Player p = playerRepository.findById(playerId).orElse(null);
        if (p == null) return;

        Mail welcome = new Mail();
        welcome.setFromPlayerId(null);
        welcome.setFromName("系统");
        welcome.setToPlayerId(playerId);
        welcome.setToName(p.getUsername());
        welcome.setType("system");
        welcome.setIsSystem(true);
        welcome.setSubject("欢迎加入战局");
        welcome.setBody("指挥官您好，您已成功入伍。系统已为您准备了一份新手礼包，请前往【仓库】查收。"
                + "完成主城建造后，将陆续解锁更多军事设施与功能。祝您战运亨通！");
        welcome.setAttachJson(JsonUtil.toJson(List.of(
                Map.of("type", "gold", "qty", 5000)
        )));
        welcome.setIsRead(false);
        welcome.setIsClaimed(false);
        welcome.setCreatedAt(now - 3600_000L);
        mailRepository.save(welcome);

        Mail reward = new Mail();
        reward.setFromPlayerId(null);
        reward.setFromName("系统");
        reward.setToPlayerId(playerId);
        reward.setToName(p.getUsername());
        reward.setType("reward");
        reward.setIsSystem(true);
        reward.setSubject("新手礼包已发放");
        reward.setBody("感谢您完成新手指引！礼包内容：黄金×3000 / 钻石×50。请在仓库中查收。");
        reward.setAttachJson(JsonUtil.toJson(List.of(
                Map.of("type", "gold", "qty", 3000),
                Map.of("type", "diamond", "qty", 50)
        )));
        reward.setIsRead(true);  // 旧的已读
        reward.setIsClaimed(false);
        reward.setCreatedAt(now - 2 * 3600_000L);
        mailRepository.save(reward);

        Mail announce = new Mail();
        announce.setFromPlayerId(null);
        announce.setFromName("盟军总指挥部");
        announce.setToPlayerId(playerId);
        announce.setToName(p.getUsername());
        announce.setType("alliance");
        announce.setIsSystem(true);
        announce.setSubject("战时通告：东线战事升级");
        announce.setBody("各位指挥官，东线斯大林格勒方向战事告急，盟军总指挥部号召各部加快战备建设，"
                + "优先发展钢铁与石油产能。同时请保持对资源点的监控，避免被敌军抢占。");
        announce.setAttachJson("[]");
        announce.setIsRead(true);
        announce.setIsClaimed(true);
        announce.setCreatedAt(now - 3 * 3600_000L);
        mailRepository.save(announce);
    }

    // ================================================================
    //  内部 helpers
    // ================================================================

    private List<Map<String, Object>> normalizeAndDeductAttach(Long fromPlayerId, List<Map<String, Object>> attach) {
        if (attach == null || attach.isEmpty()) return List.of();
        if (attach.size() > 6) throw new IllegalArgumentException("附件最多 6 项");

        // 先校验 + 累计需要扣的资源
        Map<String, Integer> need = new LinkedHashMap<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> a : attach) {
            Object t = a.get("type");
            Object q = a.get("qty");
            if (t == null || q == null) throw new IllegalArgumentException("附件格式错误");
            String type = t.toString();
            if (!ALLOWED_ATTACH.contains(type)) {
                throw new IllegalArgumentException("不支持的附件类型: " + type);
            }
            int qty;
            try { qty = Integer.parseInt(q.toString()); }
            catch (Exception e) { throw new IllegalArgumentException("附件数量必须为整数"); }
            if (qty <= 0) throw new IllegalArgumentException("附件数量必须 > 0");
            need.merge(type, qty, Integer::sum);
            normalized.add(Map.of("type", type, "qty", qty));
        }
        // 校验余额
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(fromPlayerId, cityScope.slot(fromPlayerId))
                .orElseThrow(() -> new IllegalArgumentException("发件人资源不存在"));
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int have = getRes("diamond".equals(e.getKey()) ? cityScope.wallet(fromPlayerId) : res, e.getKey());
            if (have < e.getValue()) {
                throw new IllegalArgumentException("资源不足: " + labelOf(e.getKey())
                        + " (需要 " + e.getValue() + ", 现有 " + have + ")");
            }
        }
        // 扣减
        applyResources(fromPlayerId, need, -1);
        return normalized;
    }

    private void applyResources(Long playerId, Map<String, Integer> delta, int sign) {
        if (delta == null || delta.isEmpty()) return;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))
                .orElseThrow(() -> new IllegalArgumentException("玩家资源不存在: " + playerId));
        for (Map.Entry<String, Integer> e : delta.entrySet()) {
            int v = e.getValue() * sign;
            Resources balance = "diamond".equals(e.getKey()) ? cityScope.wallet(playerId) : res;
            int cur = getRes(balance, e.getKey());
            setRes(balance, e.getKey(), cur + v);
            resourcesRepository.save(balance);
        }
        resourcesRepository.save(res);
    }

    /** 兼容 List<Map> 和 Map<String,Integer> 两种入参 */
    private void applyResources(Long playerId, List<Map<String, Object>> attach, int sign) {
        Map<String, Integer> agg = new LinkedHashMap<>();
        for (Map<String, Object> a : attach) {
            String t = String.valueOf(a.get("type"));
            int q = ((Number) a.get("qty")).intValue();
            agg.merge(t, q, Integer::sum);
        }
        applyResources(playerId, agg, sign);
    }

    private static int getRes(Resources r, String key) {
        return switch (key) {
            case "food"    -> r.getFood()    != null ? r.getFood()    : 0;
            case "steel"   -> r.getSteel()   != null ? r.getSteel()   : 0;
            case "oil"     -> r.getOil()     != null ? r.getOil()     : 0;
            case "rare"    -> r.getRare()    != null ? r.getRare()    : 0;
            case "gold"    -> r.getGold()    != null ? r.getGold()    : 0;
            case "diamond" -> r.getDiamond() != null ? r.getDiamond() : 0;
            default -> 0;
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
            default -> key;
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
        return d;
    }
}
