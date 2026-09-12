package com.wargame.service;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.ChatMessage;
import com.wargame.model.entity.Player;
import com.wargame.repository.ChatMessageRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ChatService {

    public static final int MAX_LENGTH = 80;
    public static final long COOLDOWN_MS = 5000L; // 5 秒单次发言冷却
    public static final long BURST_WINDOW_MS = 60000L; // 1 分钟滑动窗口
    public static final int BURST_MAX_COUNT = 5; // 1 分钟内最多发言 5 次
    public static final long DAILY_WINDOW_MS = 86400000L; // 24 小时
    public static final int DAILY_MAX_COUNT = 100; // 每天最多发言 100 次
    private static final long REPEAT_BLOCK_MS = 60000L; // 60 秒内禁止连续发送相同内容

    private static final List<String> SENSITIVE_WORDS = List.of("傻逼", "操你", "草你", "fuck", "shit", "管理员", "gm");

    private record LastMessage(String content, long timestamp) {}
    private final ConcurrentMap<Long, LastMessage> lastMessages = new ConcurrentHashMap<>();

    private final ChatMessageRepository chatMessageRepository;
    private final PlayerRepository playerRepository;
    private final RateLimiter rateLimiter;
    private final WebSocketPushService pushService;

    public ChatService(ChatMessageRepository chatMessageRepository,
                       PlayerRepository playerRepository,
                       RateLimiter rateLimiter,
                       WebSocketPushService pushService) {
        this.chatMessageRepository = chatMessageRepository;
        this.playerRepository = playerRepository;
        this.rateLimiter = rateLimiter;
        this.pushService = pushService;
    }

    public List<ChatDtos.MessageResponse> history() {
        List<ChatMessage> messages = chatMessageRepository.findTop50ByOrderByCreatedAtDesc();
        List<ChatDtos.MessageResponse> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) result.add(toDto(messages.get(i)));
        return result;
    }

    @Transactional
    public ChatDtos.MessageResponse send(Long playerId, String rawContent) {
        String content = normalize(rawContent);
        if (content.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        if (content.length() > MAX_LENGTH) throw new IllegalArgumentException("消息不能超过 " + MAX_LENGTH + " 字");

        long now = System.currentTimeMillis();

        // 1. 防重复内容刷屏（60 秒内禁止发送完全相同的内容）
        LastMessage last = lastMessages.get(playerId);
        if (last != null && content.equalsIgnoreCase(last.content()) && (now - last.timestamp() < REPEAT_BLOCK_MS)) {
            throw new IllegalArgumentException("请勿连续发送相同内容刷屏");
        }

        // 2. 每日发言次数限制（防止挂机脚本刷屏）
        String dailyKey = "CHAT_DAILY:" + playerId + ":" + LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (!rateLimiter.allow(dailyKey, DAILY_MAX_COUNT, DAILY_WINDOW_MS)) {
            throw new IllegalArgumentException("今日世界频道发言已达上限（" + DAILY_MAX_COUNT + "次），请明日再试");
        }

        // 3. 频次次数限制（1 分钟内最多发言 5 次）
        String burstKey = "CHAT_BURST:" + playerId;
        if (!rateLimiter.allow(burstKey, BURST_MAX_COUNT, BURST_WINDOW_MS)) {
            long waitSec = rateLimiter.retryAfterSeconds(burstKey, BURST_WINDOW_MS);
            throw new IllegalArgumentException("发言过于频繁，1分钟内最多发送 " + BURST_MAX_COUNT + " 次，请 " + waitSec + " 秒后再试");
        }

        // 4. 单次发言冷却时间（5 秒 CD）
        String cdKey = "CHAT_CD:" + playerId;
        if (!rateLimiter.allow(cdKey, 1, COOLDOWN_MS)) {
            long waitSec = rateLimiter.retryAfterSeconds(cdKey, COOLDOWN_MS);
            throw new IllegalArgumentException("发言过于频繁，请 " + waitSec + " 秒后再试");
        }

        content = filterSensitiveWords(content);
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        ChatMessage message = new ChatMessage(null, playerId, player.getUsername(), content, now);
        ChatDtos.MessageResponse response = toDto(chatMessageRepository.save(message));

        // 记录最后发送内容与时间戳
        lastMessages.put(playerId, new LastMessage(content, now));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", response.id());
        data.put("playerId", response.playerId());
        data.put("username", response.username());
        data.put("content", response.content());
        data.put("ts", response.ts());
        pushService.broadcast("chat", data);
        return response;
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

    /**
     * 发送系统消息: 玩家 A 对玩家 B 宣战 / 某玩家被打败 / 公告 等。
     * 系统消息也写入 chat_messages 表，保证重启或断线后仍能在世界频道显示。
     */
    public ChatDtos.MessageResponse sendSystem(String content) {
        if (content == null || content.isEmpty()) return null;
        if (content.length() > MAX_LENGTH) content = content.substring(0, MAX_LENGTH);
        long now = System.currentTimeMillis();
        // 系统消息持久化到聊天历史，重启或断线后仍可显示
        ChatMessage message = new ChatMessage(null, null, "系统", content, now);
        ChatDtos.MessageResponse saved = toDto(chatMessageRepository.save(message));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.id());
        data.put("playerId", null);
        data.put("username", "系统");
        data.put("type", "system");
        data.put("content", content);
        data.put("ts", saved.ts());
        pushService.broadcast("chat", data);
        return saved;
    }

    private ChatDtos.MessageResponse toDto(ChatMessage message) {
        return new ChatDtos.MessageResponse(message.getId(), message.getPlayerId(), message.getUsername(), message.getContent(), message.getCreatedAt());
    }
}
