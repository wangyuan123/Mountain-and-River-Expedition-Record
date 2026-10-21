package com.wargame.service;

import com.wargame.model.dto.PrivateChatDtos;
import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.security.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.util.*;

@Service
public class PrivateChatService {
    private final PrivateChatMessageRepository messages;
    private final PlayerRepository players;
    private final RateLimiter limiter;
    private final WebSocketPushService push;
    private final ChatService chat;
    private final PrivateChatConversationRepository sessions;
    private final GameWebSocketHandler presence;
    private final FriendContactRepository contacts;

    public PrivateChatService(PrivateChatMessageRepository messages, PlayerRepository players,
                              RateLimiter limiter, WebSocketPushService push, ChatService chat,
                              PrivateChatConversationRepository sessions, GameWebSocketHandler presence,
                              FriendContactRepository contacts) {
        this.messages = messages;
        this.players = players;
        this.limiter = limiter;
        this.push = push;
        this.chat = chat;
        this.sessions = sessions;
        this.presence = presence;
        this.contacts = contacts;
    }

    public PrivateChatDtos.Peer resolve(Long me, String username) {
        Player peer = players.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        validatePeer(me, peer);
        return peerDto(me, peer);
    }

    private Player peer(Long me, Long id) {
        if (id == null) throw new IllegalArgumentException("请选择私聊玩家");
        Player peer = players.findById(id).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        validatePeer(me, peer);
        return peer;
    }

    private void validatePeer(Long me, Player peer) {
        if (me.equals(peer.getId())) throw new IllegalArgumentException("不能与自己私聊");
        if (!peer.accountActive()) throw new IllegalArgumentException("该玩家当前不可接收私聊");
    }

    /** 打开会话不会发出消息或打扰对方；锁定所有者以防并发创建重复会话。 */
    @Transactional
    public PrivateChatDtos.Peer open(Long me, Long peerId) {
        Player target = players.findById(peerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        if (me.equals(peerId)) throw new IllegalArgumentException("不能与自己私聊");
        players.lockById(me).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        PrivateChatConversation session = sessions.findByOwnerIdAndPeerId(me, peerId)
                .orElseGet(() -> new PrivateChatConversation(null, me, peerId, 0L));
        session.setOpenedAt(System.currentTimeMillis());
        session.setDeletedThrough(0L);
        sessions.save(session);
        return peerDto(me, target);
    }

    /** 消息会话与主动打开的空会话合并，返回最近 50 个对话及在线状态。 */
    @Transactional(readOnly = true)
    public List<PrivateChatDtos.Conversation> conversations(Long me) {
        Map<Long, PrivateChatDtos.Conversation> rows = new HashMap<>();
        Map<Long, Long> times = new HashMap<>();
        Map<Long, PrivateChatConversation> owned = new HashMap<>();
        sessions.findByOwnerId(me).forEach(c -> owned.put(c.getPeerId(), c));
        long now = System.currentTimeMillis();
        for (PrivateChatMessage m : messages.conversationsVisible(me, now, PageRequest.of(0, 50))) {
            Long id = me.equals(m.getSenderId()) ? m.getRecipientId() : m.getSenderId();
            Player p = players.findById(id).orElseThrow();
            long cleared = Optional.ofNullable(owned.get(id)).map(PrivateChatConversation::getClearedThrough).orElse(0L);
            rows.put(id, new PrivateChatDtos.Conversation(peerDto(me, p), dto(m),
                    messages.countUnreadVisible(id, me, now, cleared)));
            times.put(id, m.getCreatedAt());
        }
        for (PrivateChatConversation c : sessions.findTop50ByOwnerIdOrderByOpenedAtDesc(me)) {
            Long id = c.getPeerId();
            if (!rows.containsKey(id)) {
                Player p = players.findById(id).orElseThrow();
                // 会话列表的消息查询有上限，补查较早的对话，不能误报为空会话。
                long cleared = c.getClearedThrough();
                List<PrivateChatMessage> recent = messages.historyVisible(me, id, cleared, now, PageRequest.of(0, 1));
                rows.put(id, new PrivateChatDtos.Conversation(peerDto(me, p), recent.isEmpty() ? null : dto(recent.get(0)),
                        messages.countUnreadVisible(id, me, now, cleared)));
            }
            times.merge(id, c.getOpenedAt(), Math::max);
        }
        // 删除只隐藏自己的列表；收到删除边界之后的新消息时会话重新出现。
        for (PrivateChatConversation c : owned.values()) {
            PrivateChatDtos.Conversation row = rows.get(c.getPeerId());
            if (c.getDeletedThrough() > 0 && row != null
                    && (row.lastMessage() == null || row.lastMessage().id() <= c.getDeletedThrough())) {
                rows.remove(c.getPeerId());
            } else if (row != null && c.getClearedThrough() > 0) {
                PrivateChatDtos.Message last = row.lastMessage();
                if (last != null && last.id() <= c.getClearedThrough()) {
                    rows.put(c.getPeerId(), new PrivateChatDtos.Conversation(row.peer(), null, 0));
                }
            }
        }
        return rows.values().stream().sorted(Comparator
                .comparingLong((PrivateChatDtos.Conversation c) -> times.get(c.peer().id())).reversed())
                .limit(50).toList();
    }

    /** 删除会话不删除双方历史，同时清除删除时已收到消息的未读。 */
    @Transactional
    public void deleteConversation(Long me, Long peerId) {
        players.lockById(me).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        PrivateChatConversation session = sessions.findByOwnerIdAndPeerId(me, peerId)
                .orElseGet(() -> new PrivateChatConversation(null, me, peerId, System.currentTimeMillis()));
        List<PrivateChatMessage> latest = messages.historyVisible(me, peerId, 0L, System.currentTimeMillis(), PageRequest.of(0, 1));
        long through = latest.isEmpty() ? 1L : latest.get(0).getId();
        session.setDeletedThrough(through);
        sessions.save(session);
        messages.markRead(me, peerId, through, 0L, System.currentTimeMillis());
    }

    @Transactional(readOnly = true)
    public List<PrivateChatDtos.Message> history(Long me, Long peer) {
        return historyPage(me, peer).messages();
    }

    /** 返回自己的清空边界，客户端合并实时消息时也必须丢弃边界内的缓存。 */
    @Transactional(readOnly = true)
    public PrivateChatDtos.History historyPage(Long me, Long peer) {
        long cleared = sessions.findByOwnerIdAndPeerId(me, peer).map(PrivateChatConversation::getClearedThrough).orElse(0L);
        List<PrivateChatMessage> result = new ArrayList<>(messages.historyVisible(me, peer, cleared, System.currentTimeMillis(), PageRequest.of(0, 50)));
        Collections.reverse(result);
        return new PrivateChatDtos.History(result.stream().map(this::dto).toList(), cleared);
    }

    /** 清空只推进当前账号的历史边界，重新打开仍为空，对方的历史保持可见。 */
    @Transactional
    public void clear(Long me, Long peerId) {
        players.lockById(me).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        if (peerId == null || me.equals(peerId) || !players.existsById(peerId)) throw new IllegalArgumentException("玩家不存在");
        PrivateChatConversation session = sessions.findByOwnerIdAndPeerId(me, peerId)
                .orElseGet(() -> new PrivateChatConversation(null, me, peerId, 0L));
        List<PrivateChatMessage> latest = messages.historyVisible(me, peerId, 0L, System.currentTimeMillis(), PageRequest.of(0, 1));
        if (!latest.isEmpty()) {
            long through = latest.get(0).getId();
            // 先将原可见窗口标为已读，再推进清空边界，避免新边界排除本次清空的消息。
            messages.markRead(me, peerId, through, session.getClearedThrough(), System.currentTimeMillis());
            session.setClearedThrough(Math.max(session.getClearedThrough(), through));
        }
        session.setOpenedAt(System.currentTimeMillis());
        session.setDeletedThrough(0L);
        sessions.save(session);
    }

    /** 已读仅推进到客户端实际展示的消息，避免并发新消息被提前清除未读。 */
    @Transactional
    public void read(Long me, Long peer, Long through) {
        if (through == null || through < 1) throw new IllegalArgumentException("无效的已读消息");
        long cleared = sessions.findByOwnerIdAndPeerId(me, peer).map(PrivateChatConversation::getClearedThrough).orElse(0L);
        messages.markRead(me, peer, through, cleared, System.currentTimeMillis());
    }

    /** 身份取自登录会话，持久化后仅向发送方和接收方推送。 */
    @Transactional
    public PrivateChatDtos.Message send(Long me, Long recipient, String raw) {
        if (recipient == null || me.equals(recipient)) throw new IllegalArgumentException("请选择其他玩家");
        // 和好友及隐私设置共用玩家锁；权限变更提交后，任何发送入口都不能绕过。
        players.lockById(Math.min(me, recipient)).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        players.lockById(Math.max(me, recipient)).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        Player target = peer(me, recipient);
        if (contacts.blocksChat(me, recipient)) throw new IllegalArgumentException("黑名单限制，无法发送私聊");
        if (target.isBlockStrangerMessages() && !contacts.findByOwnerIdAndPeerId(recipient, me)
                .map(FriendContact::isFriend).orElse(false)) throw new IllegalArgumentException("对方已屏蔽陌生人私信");
        String content = raw == null ? "" : raw.replaceAll("[\\u0000-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\uFEFF]", "").trim();
        if (content.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        if (content.length() > 80) throw new IllegalArgumentException("消息不能超过 80 字");
        // 冷却按发送者累计，切换收件人不能绕过防刷屏限制。
        if (!limiter.allow("PRIVATE_CHAT_CD:" + me, 1, 5000L)) {
            throw new IllegalArgumentException("发言过于频繁，请 5 秒后再试");
        }
        PrivateChatMessage saved = messages.save(new PrivateChatMessage(null, me, recipient,
                chat.filterContent(content), System.currentTimeMillis(), false));
        PrivateChatDtos.Message response = dto(saved);
        push.pushToPlayer(me, "private_chat", response);
        push.pushToPlayer(recipient, "private_chat", response);
        return response;
    }

    private PrivateChatDtos.Peer peerDto(Long me, Player p) {
        return new PrivateChatDtos.Peer(p.getId(), p.getUsername(), p.getAvatar(), presence.isPlayerOnline(p.getId()), p.accountActive(), contacts.blocksChat(me, p.getId()));
    }

    private PrivateChatDtos.Message dto(PrivateChatMessage m) {
        Player sender = players.findById(m.getSenderId()).orElseThrow();
        return new PrivateChatDtos.Message(m.getId(), m.getSenderId(), m.getRecipientId(),
                sender.getUsername(), sender.getAvatar(), chat.filterContent(m.getContent()), m.getCreatedAt());
    }
}
