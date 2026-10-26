package com.wargame.service;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.dto.FriendDtos;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.security.RateLimiter;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class FriendService {
    private final FriendContactRepository contacts;
    private final FriendGroupRepository groups;
    private final PlayerRepository players;
    private final ScoutReportRepository scoutReports;
    private final GameWebSocketHandler presence;
    private final FriendRequestRepository requests;
    private final RateLimiter limiter;
    private final WebSocketPushService pushService;

    public FriendService(FriendContactRepository contacts, FriendGroupRepository groups,
                         PlayerRepository players, ScoutReportRepository scoutReports, GameWebSocketHandler presence,
                         FriendRequestRepository requests, RateLimiter limiter,
                         WebSocketPushService pushService) {
        this.contacts = contacts; this.groups = groups; this.players = players; this.scoutReports = scoutReports;
        this.presence = presence; this.requests = requests;
        this.limiter = limiter; this.pushService = pushService;
    }

    @Transactional(readOnly = true)
    public FriendDtos.ListResponse list(Long me) {
        List<FriendDtos.Group> result = new ArrayList<>();
        result.add(new FriendDtos.Group(0L, "我的好友"));
        groups.findByOwnerIdOrderByIdAsc(me).forEach(g -> result.add(new FriendDtos.Group(g.getId(), g.getName())));
        List<FriendContact> rows = contacts.findByOwnerIdOrderByIdAsc(me);
        Map<Long, Player> peers = new HashMap<>();
        players.findAllById(rows.stream().map(FriendContact::getPeerId).toList()).forEach(p -> peers.put(p.getId(), p));
        List<FriendDtos.Request> pending = requests.findBySenderIdOrRecipientIdOrderByIdDesc(me, me).stream().map(r -> {
            Long peerId = me.equals(r.getSenderId()) ? r.getRecipientId() : r.getSenderId();
            return new FriendDtos.Request(r.getId(), profile(me, peerId), me.equals(r.getSenderId()), r.getCreatedAt());
        }).toList();
        return new FriendDtos.ListResponse(result, rows.stream().filter(c -> peers.containsKey(c.getPeerId()))
                .map(c -> dto(peers.get(c.getPeerId()), c)).toList(), pending, settings(player(me)));
    }

    /** 优先按完整账号名查找，再按数字 ID 查找，避免数字账号名被误识别。 */
    @Transactional(readOnly = true)
    public FriendDtos.Profile search(Long me, String query) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.length() > 50) throw new IllegalArgumentException("请输入玩家名或 ID");
        Player target = players.findByUsername(value).orElseGet(() -> {
            try { return players.findById(Long.valueOf(value)).orElse(null); }
            catch (NumberFormatException e) { return null; }
        });
        if (target == null || !target.accountActive()) throw new IllegalArgumentException("玩家不存在或账号不可用");
        return profile(me, target.getId());
    }

    /** 仅向拥有当前有效成功侦察快照的查看者返回玩家主城坐标。 */
    @Transactional(readOnly = true)
    public FriendDtos.Profile profile(Long me, Long peerId) {
        Player target = target(me, peerId);
        FriendDtos.Profile p = dto(target, contacts.findByOwnerIdAndPeerId(me, peerId).orElse(null));
        String state = requests.findBySenderIdAndRecipientId(me, peerId).isPresent() ? "OUTGOING"
                : requests.findBySenderIdAndRecipientId(peerId, me).isPresent() ? "INCOMING" : "NONE";
        boolean locationAuthorized = hasCurrentScoutLocation(me, target);
        return new FriendDtos.Profile(p.id(), p.username(), p.displayName(), p.avatar(), p.level(), p.militaryRank(),
                p.prestige(), p.cityName(), p.faction(), p.online(), p.available(), p.friend(), p.blocked(), p.groupId(), state,
                locationAuthorized ? target.getCityPosX() : null, locationAuthorized ? target.getCityPosY() : null);
    }

    /** 遵循收件人的申请权限；需审批时只保存申请，同意后才建立双方好友关系。 */
    @Transactional
    public FriendDtos.AddResult add(Long me, Long peerId, Long groupId) {
        lockPair(me, peerId);
        Player target = target(me, peerId);
        if (!target.accountActive()) throw new IllegalArgumentException("该玩家账号不可用");
        if (contacts.blocksChat(me, peerId)) throw new IllegalArgumentException("请先解除黑名单限制");
        FriendContact contact = contact(me, peerId);
        if (contact.isFriend()) return new FriendDtos.AddResult(dto(target, contact), false);
        if ("NONE".equals(target.getFriendRequestPolicy())) throw new IllegalArgumentException("该玩家已关闭好友申请");
        if (requests.findBySenderIdAndRecipientId(me, peerId).isPresent()) return new FriendDtos.AddResult(profile(me, peerId), true);
        if (requests.findBySenderIdAndRecipientId(peerId, me).isPresent())
            throw new IllegalArgumentException("对方已申请添加你，请在好友申请中处理");
        // 申请被拒绝或撤回后也保留短暂冷却，防止反复申请骚扰同一玩家。
        if (!limiter.allow("FRIEND_PAIR:" + me + ":" + peerId, 1, 60000L)
                || !limiter.allow("FRIEND_REQUEST:" + me, 10, 60000L))
            throw new IllegalArgumentException("好友申请过于频繁，请稍后再试");
        if ("ANYONE".equals(target.getFriendRequestPolicy())) {
            befriend(me, peerId, group(me, groupId));
            return new FriendDtos.AddResult(profile(me, peerId), false);
        }
        requests.save(new FriendRequest(me, peerId));
        if (pushService != null) pushService.pushToPlayer(peerId, "friend_request", Map.of("type", "request", "senderId", me));
        return new FriendDtos.AddResult(profile(me, peerId), true);
    }

    @Transactional
    public void remove(Long me, Long peerId) {
        lockPair(me, peerId);
        removeContact(me, peerId);
        removeContact(peerId, me);
    }

    /** 拉黑不删除好友；非好友也可拉黑，解除时删除无好友关系的空记录。 */
    @Transactional
    public FriendDtos.Profile block(Long me, Long peerId, boolean blocked) {
        lockPair(me, peerId);
        Player target = target(me, peerId);
        FriendContact contact = contact(me, peerId);
        contact.setBlocked(blocked);
        if (blocked) clearRequests(me, peerId);
        if (!blocked && !contact.isFriend()) {
            if (contact.getId() != null) contacts.delete(contact);
        } else contacts.save(contact);
        return dto(target, contact);
    }

    @Transactional
    public FriendDtos.Profile move(Long me, Long peerId, Long groupId) {
        lock(me);
        FriendContact contact = contacts.findByOwnerIdAndPeerId(me, peerId)
                .filter(FriendContact::isFriend).orElseThrow(() -> new IllegalArgumentException("请先添加好友"));
        contact.setGroupId(group(me, groupId));
        contacts.save(contact);
        return dto(target(me, peerId), contact);
    }

    @Transactional
    public FriendDtos.Group saveGroup(Long me, Long id, String raw) {
        lock(me);
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 20 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("分组名称须为 1 至 20 个可见字符");
        if (name.equals("我的好友") || name.equals("黑名单")) throw new IllegalArgumentException("请使用其他分组名称");
        List<FriendGroup> existing = groups.findByOwnerIdOrderByIdAsc(me);
        if (existing.stream().anyMatch(g -> !Objects.equals(g.getId(), id) && g.getName().equalsIgnoreCase(name)))
            throw new IllegalArgumentException("分组名称已存在");
        if (id == null && existing.size() >= 20) throw new IllegalArgumentException("最多创建 20 个自定义分组");
        FriendGroup group = id == null ? new FriendGroup(me, name) : ownedGroup(me, id);
        group.setName(name);
        groups.save(group);
        return new FriendDtos.Group(group.getId(), group.getName());
    }

    /** 删除自定义分组时将好友移回默认分组，禁止操作其他账号的分组。 */
    @Transactional
    public void deleteGroup(Long me, Long id) {
        lock(me);
        FriendGroup group = ownedGroup(me, id);
        contacts.findByOwnerIdAndGroupId(me, id).forEach(c -> { c.setGroupId(null); contacts.save(c); });
        groups.delete(group);
    }

    /** 仅申请接收方可同意；拒绝和撤回均只允许申请当事人操作。 */
    @Transactional
    public void respond(Long me, Long requestId, boolean accept) {
        FriendRequest request = requests.findById(requestId).orElseThrow(() -> new IllegalArgumentException("申请已处理或不存在"));
        if (!me.equals(request.getRecipientId()) && (!me.equals(request.getSenderId()) || accept))
            throw new IllegalArgumentException("无权处理该申请");
        lockPair(request.getSenderId(), request.getRecipientId());
        // 加锁后重读，避免另一设备已撤回的申请被再次接受。
        request = requests.findLockedById(requestId).orElseThrow(() -> new IllegalArgumentException("申请已处理或不存在"));
        Long senderId = request.getSenderId();
        Long recipientId = request.getRecipientId();
        if (accept) {
            if (contacts.blocksChat(request.getSenderId(), request.getRecipientId())) throw new IllegalArgumentException("请先解除黑名单限制");
            if (!player(request.getSenderId()).accountActive()) throw new IllegalArgumentException("该玩家账号不可用");
            befriend(request.getSenderId(), request.getRecipientId(), null);
        } else requests.delete(request);
        if (pushService != null) {
            pushService.pushToPlayer(senderId, "friend_request", Map.of("type", accept ? "accepted" : "rejected", "recipientId", recipientId));
            pushService.pushToPlayer(recipientId, "friend_request", Map.of("type", "handled", "requestId", requestId));
        }
    }

    @Transactional
    public FriendDtos.Settings updateSettings(Long me, FriendDtos.Settings request) {
        lock(me);
        if (!Set.of("APPROVAL", "ANYONE", "NONE").contains(request.friendRequestPolicy() == null ? "" : request.friendRequestPolicy()))
            throw new IllegalArgumentException("无效的好友申请权限");
        Player player = player(me);
        player.setFriendRequestPolicy(request.friendRequestPolicy());
        player.setBlockStrangerMessages(request.blockStrangerMessages());
        players.save(player);
        return settings(player);
    }

    private FriendDtos.Settings settings(Player p) { return new FriendDtos.Settings(p.getFriendRequestPolicy(), p.isBlockStrangerMessages()); }
    private Player player(Long id) { return players.findById(id).orElseThrow(() -> new IllegalArgumentException("玩家不存在")); }
    private void removeContact(Long me, Long peerId) {
        contacts.findByOwnerIdAndPeerId(me, peerId).ifPresent(c -> {
            if (c.isBlocked()) { c.setFriend(false); c.setGroupId(null); contacts.save(c); }
            else contacts.delete(c);
        });
    }
    private void clearRequests(Long first, Long second) {
        requests.findBySenderIdAndRecipientId(first, second).ifPresent(requests::delete);
        requests.findBySenderIdAndRecipientId(second, first).ifPresent(requests::delete);
    }
    private void befriend(Long sender, Long recipient, Long groupId) {
        FriendContact first = contact(sender, recipient);
        if (!first.isFriend()) first.setGroupId(groupId);
        first.setFriend(true); contacts.save(first);
        FriendContact second = contact(recipient, sender);
        second.setFriend(true); contacts.save(second);
        clearRequests(sender, recipient);
    }

    /** 固定 ID 顺序锁定双方，串行化申请、删除和私聊发送，避免相互添加时死锁。 */
    private void lockPair(Long first, Long second) {
        if (second == null || first.equals(second)) throw new IllegalArgumentException("请选择其他玩家");
        lock(Math.min(first, second)); lock(Math.max(first, second));
    }

    private void lock(Long me) { players.lockById(me).orElseThrow(() -> new IllegalArgumentException("玩家不存在")); }
    private Player target(Long me, Long peerId) {
        if (peerId == null || me.equals(peerId)) throw new IllegalArgumentException("不能将自己添加为好友");
        return players.findById(peerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
    }
    private FriendContact contact(Long me, Long peerId) {
        return contacts.findByOwnerIdAndPeerId(me, peerId).orElseGet(() -> new FriendContact(me, peerId));
    }
    private FriendGroup ownedGroup(Long me, Long id) {
        return groups.findByIdAndOwnerId(id, me).orElseThrow(() -> new IllegalArgumentException("分组不存在"));
    }
    private Long group(Long me, Long id) { return id == null || id == 0 ? null : ownedGroup(me, id).getId(); }
    private FriendDtos.Profile dto(Player p, FriendContact c) {
        return new FriendDtos.Profile(p.getId(), p.getUsername(), p.getDisplayName(), p.getAvatar(), p.getLevel(),
                p.getMilitaryRank(), p.getPrestige(), p.getCityName(), p.getFaction(), presence.isPlayerOnline(p.getId()),
                p.accountActive(), c != null && c.isFriend(), c != null && c.isBlocked(), c == null || c.getGroupId() == null ? 0L : c.getGroupId(), "NONE",
                null, null);
    }

    /**
     * 校验查看者是否拥有仍有效、成功且仍指向该玩家当前主城的侦察报告。
     * @param viewerId 查看者玩家 ID
     * @param target 被查看玩家
     * @return 报告可授权当前坐标时返回 true
     */
    private boolean hasCurrentScoutLocation(Long viewerId, Player target) {
        Integer x = target.getCityPosX();
        Integer y = target.getCityPosY();
        if (!target.accountActive() || x == null || y == null) return false;
        long now = System.currentTimeMillis();
        return scoutReports.findByPlayerIdAndTypeAndTargetXAndTargetYAndDetailExpiresAtGreaterThanOrderByCreatedAtDesc(
                viewerId, "scout", x, y, now)
                .stream().anyMatch(report -> report.getData() != null
                        && report.getDetailExpiresAt() != null && report.getDetailExpiresAt() > now
                        && matchesCurrentPlayerScout(report, target, x, y));
    }

    /** 旧版报告缺少目标玩家身份快照，不能用于授权当前坐标。 */
    private static boolean matchesCurrentPlayerScout(ScoutReport report, Player target, Integer x, Integer y) {
        Map<String, Object> data = JsonUtil.parseObjMap(report.getData());
        return "player".equals(data.get("targetKind"))
                && Boolean.TRUE.equals(data.get("showCityInfo"))
                && Objects.equals(target.getId(), asLong(data.get("targetPlayerId")))
                && Objects.equals(x.longValue(), asLong(data.get("x")))
                && Objects.equals(y.longValue(), asLong(data.get("y")));
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
