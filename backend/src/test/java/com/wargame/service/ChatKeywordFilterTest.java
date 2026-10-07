package com.wargame.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ChatKeywordFilterTest {
    private final ChatKeywordFilter filter = new ChatKeywordFilter();

    @Test
    void coversEveryExplicitDictionaryEntry() throws Exception {
        for (String resource : List.of("/chat/chat-sensitive-words.txt", "/chat/political-sensitive-words.txt")) {
            try (var reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(resource), StandardCharsets.UTF_8))) {
                for (String word : reader.lines().toList()) {
                    if (word.isBlank() || word.startsWith("#")) continue;
                    assertEquals("*".repeat(word.length()), filter.filter(word), word);
                }
            }
        }
    }

    @Test
    void handlesMixedCaseFullWidthInvisibleAndInsertedSymbols() {
        for (String text : List.of("V.X", "q_q", "Ｖ．ｘ", "ＤｉＳｃＯｒＤ", "微❤", "微❤️", "薇-信", "v信", "微xIn", "微\u200B信", "微😊信", "V❤X", "收_装-备", "台-独")) {
            String result = filter.filter(text);
            assertNotEquals(text, result, text);
            assertFalse(result.codePoints().anyMatch(Character::isLetterOrDigit), text + " -> " + result);
        }
        assertEquals("*-*", filter.filter("台-独"));
        assertEquals("*.*", filter.filter("V.X"));
        assertEquals("q_＊", filter.filter("q_＊")); // 单个 q 不属于词库，不应命中。
    }

    @Test
    void masksOverlappingAndSuffixMatchesWithoutLosingCoverage() {
        var custom = new ChatKeywordFilter(List.of("aba", "bab", "bc", "abcd"));
        assertEquals("*****", custom.filter("ababa"));
        assertEquals("****", custom.filter("abcd"));
        assertEquals("x**", custom.filter("xbc"));
        assertEquals("**-**", filter.filter("微信-转账"));
    }

    @Test
    void keepsNormalMessagesAndStrictReportCredentials() {
        assertEquals("指挥部全员就绪！明天进攻北方城市", filter.filter("指挥部全员就绪！明天进攻北方城市"));
        assertEquals("<img src=x onerror=alert(1)>", filter.filter("<img src=x onerror=alert(1)>"));
        String share = "[战报:dc123456-1234-1234-1234-123456789abc]";
        assertEquals(share, filter.filter(share));
        assertNotEquals(share + "微信", filter.filter(share + "微信"));
        assertNull(filter.filter(null));
        assertEquals("", filter.filter(""));
    }

    @Test
    void supportsTenThousandTermsAndConcurrentReadOnlyRequests() throws Exception {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 10000; i++) words.add("term" + i + "end");
        var large = new ChatKeywordFilter(words);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 100; i++) tasks.add(() -> large.filter("prefix TERM9999END suffix"));
            for (var result : executor.invokeAll(tasks)) assertEquals("prefix *********** suffix", result.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
