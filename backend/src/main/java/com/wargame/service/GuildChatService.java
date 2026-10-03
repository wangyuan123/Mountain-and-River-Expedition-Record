package com.wargame.service;

import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.GuildChatMessage;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.Player;
import com.wargame.repository.GuildChatMessageRepository;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class GuildChatService {

    public static final int MAX_LENGTH = 80;
    public static final long COOLDOWN_MS = 5000L; // 5 秒单次发言冷却
    private static final long REPEAT_BLOCK_MS = 60000L; // 60 秒内禁止连续发送相同内容
    private static final List<String> SENSITIVE_WORDS = List.of("傻逼", "操你", "草你", "fuck", "shit", "管理员", "gm");

    private record LastMessage(String content, long timestamp) {}
    private final ConcurrentMap<Long, LastMessage> lastMessages = new ConcurrentHashMap<>();

    private final GuildChatMessageRepository guildChatMessageRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final PlayerRepository playerRepository;
    private final RateLimiter rateLimiter;
    private final WebSocketPushService pushService;
    private final PoliticalWordFilter politicalWordFilter;

    public GuildChatService(GuildChatMessageRepository guildChatMessageRepository,
                            GuildMemberRepository guildMemberRepository,
                            PlayerRepository playerRepository,
                            RateLimiter rateLimiter,
                            WebSocketPushService pushService,
                            PoliticalWordFilter politicalWordFilter) {
        this.guildChatMessageRepository = guildChatMessageRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.playerRepository = playerRepository;
        this.rateLimiter = rateLimiter;
        this.pushService = pushService;
        this.politicalWordFilter = politicalWordFilter;
    }

    public List<ChatDtos.GuildMessageResponse> history(Long playerId) {
        GuildMember member = guildMemberRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalArgumentException("尚未加入任何军团"));
        Long guildId = member.getGuildId();
        List<GuildChatMessage> messages = guildChatMessageRepository.findTop50ByGuildIdOrderByCreatedAtDesc(guildId);

        List<GuildMember> allMembers = guildMemberRepository.findByGuildIdOrderByJoinedAtAsc(guildId);
        Map<Long, String> roles = new HashMap<>();
        for (GuildMember m : allMembers) {
            roles.put(m.getPlayerId(), m.getRole());
        }

        List<Long> playerIds = messages.stream()
                .map(GuildChatMessage::getPlayerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> avatars = new HashMap<>();
        if (!playerIds.isEmpty()) {
            List<Player> players = playerRepository.findAllById(playerIds);
            for (Player p : players) {
                avatars.put(p.getId(), p.getAvatar());
            }
        }

        List<ChatDtos.GuildMessageResponse> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            GuildChatMessage msg = messages.get(i);
            String role = msg.getPlayerId() != null ? roles.getOrDefault(msg.getPlayerId(), "member") : "system";
            String avatar = msg.getPlayerId() != null ? avatars.get(msg.getPlayerId()) : null;
            String type = msg.getPlayerId() == null ? "system" : "player";
            result.add(new ChatDtos.GuildMessageResponse(
                    msg.getId(),
                    msg.getGuildId(),
                    msg.getPlayerId(),
                    msg.getUsername(),
                    role,
                    msg.getContent(),
                    msg.getCreatedAt(),
                    avatar,
                    type
            ));
        }
        return result;
    }

    @Transactional
    public ChatDtos.GuildMessageResponse send(Long playerId, String rawContent) {
        GuildMember member = guildMemberRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalArgumentException("尚未加入任何军团"));
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));

        String content = normalize(rawContent);
        if (content.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        if (content.length() > MAX_LENGTH) throw new IllegalArgumentException("消息不能超过 " + MAX_LENGTH + " 字");

        long now = System.currentTimeMillis();

        // 1. 60秒内防重复内容刷屏
        LastMessage last = lastMessages.get(playerId);
        if (last != null && content.equalsIgnoreCase(last.content()) && (now - last.timestamp() < REPEAT_BLOCK_MS)) {
            throw new IllegalArgumentException("请勿连续发送相同内容刷屏");
        }

        // 2. 5秒冷却
        String cdKey = "GUILD_CHAT_CD:" + playerId;
        if (!rateLimiter.allow(cdKey, 1, COOLDOWN_MS)) {
            long waitSec = rateLimiter.retryAfterSeconds(cdKey, COOLDOWN_MS);
            throw new IllegalArgumentException("发言过于频繁，请 " + waitSec + " 秒后再试");
        }

        String originalContent = content;
        content = politicalWordFilter.filter(filterSensitiveWords(content));
        GuildChatMessage message = new GuildChatMessage(null, member.getGuildId(), playerId, player.getUsername(), content, now);
        message = guildChatMessageRepository.save(message);

        lastMessages.put(playerId, new LastMessage(originalContent, now));

        ChatDtos.GuildMessageResponse response = new ChatDtos.GuildMessageResponse(
                message.getId(),
                message.getGuildId(),
                message.getPlayerId(),
                message.getUsername(),
                member.getRole(),
                message.getContent(),
                message.getCreatedAt(),
                player.getAvatar(),
                "player"
        );

        pushToGuild(member.getGuildId(), response);
        return response;
    }

    @Transactional
    public ChatDtos.GuildMessageResponse sendSystem(Long guildId, String content) {
        if (guildId == null || content == null || content.isBlank()) return null;
        if (content.length() > MAX_LENGTH) content = content.substring(0, MAX_LENGTH);
        long now = System.currentTimeMillis();

        GuildChatMessage message = new GuildChatMessage(null, guildId, null, "系统", content, now);
        message = guildChatMessageRepository.save(message);

        ChatDtos.GuildMessageResponse response = new ChatDtos.GuildMessageResponse(
                message.getId(),
                guildId,
                null,
                "系统",
                "system",
                message.getContent(),
                message.getCreatedAt(),
                null,
                "system"
        );

        pushToGuild(guildId, response);
        return response;
    }

    private void pushToGuild(Long guildId, ChatDtos.GuildMessageResponse msg) {
        List<GuildMember> members = guildMemberRepository.findByGuildIdOrderByJoinedAtAsc(guildId);
        if (members == null || members.isEmpty()) return;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", msg.id());
        data.put("guildId", msg.guildId());
        data.put("playerId", msg.playerId());
        data.put("username", msg.username());
        data.put("role", msg.role());
        data.put("content", msg.content());
        data.put("ts", msg.ts());
        data.put("avatar", msg.avatar());
        data.put("type", msg.type());

        for (GuildMember m : members) {
            pushService.pushToPlayer(m.getPlayerId(), "guild_chat", data);
        }
    }

    private String normalize(String content) {
        if (content == null) return "";
        return content.replaceAll("[\\u0000-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\uFEFF]", "").trim();
    }

    private String filterSensitiveWords(String content) {
        String result = content;
        String lower = result.toLowerCase(Locale.ROOT);
        for (String word : SENSITIVE_WORDS) {
            String target = word.toLowerCase(Locale.ROOT);
            int index;
            while ((index = lower.indexOf(target)) >= 0) {
                StringBuilder replacement = new StringBuilder();
                for (int i = 0; i < target.length(); i++) replacement.append('*');
                result = result.substring(0, index) + replacement + result.substring(index + target.length());
                lower = result.toLowerCase(Locale.ROOT);
            }
        }
        return result;
    }
}
