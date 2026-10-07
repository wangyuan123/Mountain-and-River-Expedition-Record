package com.wargame.service;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** 玩家聊天共用的 Aho-Corasick 有限状态匹配器，启动时构建，请求间只读共享。 */
@Component
public class ChatKeywordFilter {
    private static final class Node {
        final Map<Integer, Node> next = new HashMap<>();
        Node failure;
        int longest;
    }

    private record Text(List<Integer> points, List<Integer> offsets, List<Integer> lengths) {}
    private final Node root = new Node();

    public ChatKeywordFilter() {
        this(loadWords());
    }

    /** 可注入测试词库；生产同时加载交易、联系方式、违规词和原政治词库。 */
    ChatKeywordFilter(Collection<String> words) {
        root.failure = root;
        for (String word : words) {
            List<Integer> points = normalize(word).points();
            if (points.isEmpty()) continue;
            Node node = root;
            for (int point : points) node = node.next.computeIfAbsent(point, ignored -> new Node());
            node.longest = Math.max(node.longest, points.size());
        }
        // 失败边复用已匹配后缀，避免为每个词或每个起点重新扫描消息。
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.next.values()) {
            child.failure = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node parent = queue.remove();
            for (var edge : parent.next.entrySet()) {
                Node fallback = parent.failure;
                while (fallback != root && !fallback.next.containsKey(edge.getKey())) fallback = fallback.failure;
                Node child = edge.getValue();
                child.failure = fallback.next.getOrDefault(edge.getKey(), root);
                child.longest = Math.max(child.longest, child.failure.longest);
                queue.add(child);
            }
        }
    }

    /** 遮盖全部重叠命中，保留原文分隔符与非命中字符；不替代 HTML 输出转义。 */
    public String filter(String content) {
        if (content == null || content.isEmpty()) return content;
        // 战报凭证只含严格 UUID，不包含玩家文字，不能让随机十六进制中的 dc 破坏分享。
        if (content.matches("^\\[战报:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\]$")) return content;
        Text text = normalize(content);
        int[] coverage = new int[text.points().size() + 1];
        Node node = root;
        for (int i = 0; i < text.points().size(); i++) {
            int point = text.points().get(i);
            while (node != root && !node.next.containsKey(point)) node = node.failure;
            node = node.next.getOrDefault(point, root);
            if (node.longest > 0) {
                // 同一结束点的短词已被最长词覆盖；差分标记使大量重叠匹配也保持线性处理。
                coverage[i + 1 - node.longest]++;
                coverage[i + 1]--;
            }
        }
        char[] result = content.toCharArray();
        int active = 0;
        for (int i = 0; i < text.points().size(); i++) {
            active += coverage[i];
            if (active > 0) Arrays.fill(result, text.offsets().get(i), text.offsets().get(i) + text.lengths().get(i), '*');
        }
        return new String(result);
    }

    /** 按码点折叠大小写及兼容字符，忽略插入符号；“微”后的心形按“信”识别，其余心形按分隔符处理。 */
    private static Text normalize(String content) {
        List<Integer> points = new ArrayList<>(), offsets = new ArrayList<>(), lengths = new ArrayList<>();
        for (int offset = 0; offset < content.length();) {
            int point = content.codePointAt(offset);
            int length = Character.charCount(point);
            String folded = Normalizer.normalize(new String(Character.toChars(point)), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            for (int normalized : folded.codePoints().toArray()) {
                if (normalized == 0x2764 && !points.isEmpty() && points.get(points.size() - 1) == (int) '微') normalized = '信';
                if (Character.isLetterOrDigit(normalized)) {
                    points.add(normalized);
                    offsets.add(offset);
                    lengths.add(length);
                }
            }
            offset += length;
        }
        return new Text(points, offsets, lengths);
    }

    private static List<String> loadWords() {
        List<String> words = new ArrayList<>();
        for (String resource : List.of("/chat/chat-sensitive-words.txt", "/chat/political-sensitive-words.txt")) {
            var input = ChatKeywordFilter.class.getResourceAsStream(resource);
            if (input == null) throw new IllegalStateException("聊天词库缺失: " + resource);
            int before = words.size();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                reader.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(words::add);
            } catch (IOException | java.io.UncheckedIOException error) {
                throw new IllegalStateException("聊天词库读取失败: " + resource, error);
            }
            if (words.size() == before) throw new IllegalStateException("聊天词库为空: " + resource);
        }
        return words;
    }
}
