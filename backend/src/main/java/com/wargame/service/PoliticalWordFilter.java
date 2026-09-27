package com.wargame.service;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class PoliticalWordFilter {

    private static final String WORDS_RESOURCE = "/chat/political-sensitive-words.txt";

    private final List<String> words;

    public PoliticalWordFilter() {
        this.words = loadWords();
    }

    /** 按随服务发布的词库遮盖政治敏感词，保留原消息中的分隔符。 */
    public String filter(String content) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> sourceOffsets = new ArrayList<>();
        List<Integer> sourceLengths = new ArrayList<>();

        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            String folded = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            for (int i = 0; i < folded.length();) {
                int foldedPoint = folded.codePointAt(i);
                if (Character.isLetterOrDigit(foldedPoint)) {
                    normalized.appendCodePoint(foldedPoint);
                    for (int j = 0; j < Character.charCount(foldedPoint); j++) {
                        sourceOffsets.add(offset);
                        sourceLengths.add(Character.charCount(codePoint));
                    }
                }
                i += Character.charCount(foldedPoint);
            }
            offset += Character.charCount(codePoint);
        }

        // 在折叠文本中查找可识别词条，再映射回原文字符；夹入的空格和标点原样保留。
        boolean[] masked = new boolean[content.length()];
        for (String word : words) {
            for (int match = normalized.indexOf(word); match >= 0;
                 match = normalized.indexOf(word, match + 1)) {
                for (int i = match; i < match + word.length(); i++) {
                    int start = sourceOffsets.get(i);
                    for (int j = start; j < start + sourceLengths.get(i); j++) masked[j] = true;
                }
            }
        }

        char[] result = content.toCharArray();
        for (int i = 0; i < result.length; i++) {
            if (masked[i]) result[i] = '*';
        }
        return new String(result);
    }

    private List<String> loadWords() {
        InputStream input = PoliticalWordFilter.class.getResourceAsStream(WORDS_RESOURCE);
        if (input == null) throw new IllegalStateException("世界频道政治敏感词库缺失: " + WORDS_RESOURCE);

        Set<String> loaded = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (word.isEmpty() || word.startsWith("#")) continue;
                String normalized = normalizeWord(word);
                if (!normalized.isEmpty()) loaded.add(normalized);
            }
        } catch (IOException e) {
            throw new IllegalStateException("世界频道政治敏感词库读取失败", e);
        }
        if (loaded.isEmpty()) throw new IllegalStateException("世界频道政治敏感词库为空");
        return List.copyOf(loaded);
    }

    private String normalizeWord(String word) {
        String folded = Normalizer.normalize(word, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder();
        folded.codePoints().filter(Character::isLetterOrDigit).forEach(normalized::appendCodePoint);
        return normalized.toString();
    }
}
