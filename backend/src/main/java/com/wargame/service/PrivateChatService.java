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

    public PrivateChatService(PrivateChatMessageRepository messages, PlayerRepository players,
                              RateLimiter limiter, WebSocketPushService push, ChatService chat,
                              PrivateChatConversationRepository sessions, GameWebSocketHandler presence) {
        this.messages = messages;
        this.players = players;
        this.limiter = limiter;
        this.push = push;
        this.chat = chat;
        this.sessions = sessions;
        this.presence = presence;
    }

    public PrivateChatDtos.Peer resolve(Long me, String username) {
        Player peer = players.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        validatePeer(me, peer);
        return peerDto(peer);
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
        return peerDto(target);
    }

    /** 消息会话与主动打开的空会话合并，返回最近 50 个对话及在线状态。 */
    @Transactional(readOnly = true)
    public List<PrivateChatDtos.Conversation> conversations(Long me) {
        Map<Long, PrivateChatDtos.Conversation> rows = new HashMap<>();
        Map<Long, Long> times = new HashMap<>();
        for (PrivateChatMessage m : messages.conversations(me, PageRequest.of(0, 50))) {
            Long id = me.equals(m.getSenderId()) ? m.getRecipientId() : m.getSenderId();
            Player p = players.findById(id).orElseThrow();
            rows.put(id, new PrivateChatDtos.Conversation(peerDto(p), dto(m),
                    messages.countBySenderIdAndRecipientIdAndReadByRecipientFalse(id, me)));
            times.put(id, m.getCreatedAt());
        }
        for (PrivateChatConversation c : sessions.findTop50ByOwnerIdOrderByOpenedAtDesc(me)) {
            Long id = c.getPeerId();
            if (!rows.containsKey(id)) {
                Player p = players.findById(id).orElseThrow();
                // 会话列表的消息查询有上限，补查较早的对话，不能误报为空会话。
                List<PrivateChatMessage> recent = messages.history(me, id, PageRequest.of(0, 1));
                rows.put(id, new PrivateChatDtos.Conversation(peerDto(p), recent.isEmpty() ? null : dto(recent.get(0)),
                        messages.countBySenderIdAndRecipientIdAndReadByRecipientFalse(id, me)));
            }
            times.merge(id, c.getOpenedAt(), Math::max);
        }
        // 删除只隐藏自己的列表；收到删除边界之后的新消息时会话重新出现。
        for (PrivateChatConversation c : sessions.findByOwnerId(me)) {
            PrivateChatDtos.Conversation row = rows.get(c.getPeerId());
            if (c.getDeletedThrough() > 0 && row != null
                    && (row.lastMessage() == null || row.lastMessage().id() <= c.getDeletedThrough())) {
                rows.remove(c.getPeerId());
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
        List<PrivateChatMessage> latest = messages.history(me, peerId, PageRequest.of(0, 1));
        long through = latest.isEmpty() ? 1L : latest.get(0).getId();
        session.setDeletedThrough(through);
        sessions.save(session);
        messages.markRead(me, peerId, through);
    }

    @Transactional(readOnly = true)
    public List<PrivateChatDtos.Message> history(Long me, Long peer) {
        List<PrivateChatMessage> result = new ArrayList<>(messages.history(me, peer, PageRequest.of(0, 50)));
        Collections.reverse(result);
        return result.stream().map(this::dto).toList();
    }

    /** 已读仅推进到客户端实际展示的消息，避免并发新消息被提前清除未读。 */
    @Transactional
    public void read(Long me, Long peer, Long through) {
        if (through == null || through < 1) throw new IllegalArgumentException("无效的已读消息");
        messages.markRead(me, peer, through);
    }

    /** 身份取自登录会话，持久化后仅向发送方和接收方推送。 */
    @Transactional
    public PrivateChatDtos.Message send(Long me, Long recipient, String raw) {
        peer(me, recipient);
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

    private PrivateChatDtos.Peer peerDto(Player p) {
        return new PrivateChatDtos.Peer(p.getId(), p.getUsername(), p.getAvatar(), presence.isPlayerOnline(p.getId()), p.accountActive());
    }

    private PrivateChatDtos.Message dto(PrivateChatMessage m) {
        Player sender = players.findById(m.getSenderId()).orElseThrow();
        return new PrivateChatDtos.Message(m.getId(), m.getSenderId(), m.getRecipientId(),
                sender.getUsername(), sender.getAvatar(), chat.filterContent(m.getContent()), m.getCreatedAt());
    }
}
