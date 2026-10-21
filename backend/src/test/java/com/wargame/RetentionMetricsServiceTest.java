package com.wargame;

import com.wargame.service.RetentionMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 生产分支避免全表 COUNT，但仍必须盘点过期积压。 */
@ExtendWith(OutputCaptureExtension.class)
class RetentionMetricsServiceTest {
    @Test void mysqlEstimatesTotalsAndStillReadsIndexedExpiry(CapturedOutput output) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn("MySQL");
        when(jdbc.queryForList(startsWith("SELECT TABLE_ROWS"), any(Object[].class)))
                .thenReturn(List.of(Map.of("row_count", 12L)));
        when(jdbc.query(startsWith("SELECT MIN("), org.mockito.ArgumentMatchers.<ResultSetExtractor<Long>>any(), any(Object[].class)))
                .thenReturn(100L);
        when(jdbc.queryForMap(startsWith("SELECT COUNT(*) AS issued"), any(Object[].class)))
                .thenReturn(Map.of("issued", 1, "claimed", 0, "expired", 1));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM mails WHERE attachment_source_type='LEGACY_UNKNOWN'"), eq(Long.class)))
                .thenReturn(1L);
        new RetentionMetricsService(jdbc).collect();
        assertFalse(output.getAll().contains("Retention metrics unavailable"));
        assertTrue(output.getAll().contains("table=chat_messages rows=12"));
        assertTrue(output.getAll().contains("category=scout_reports.details oldestExpiredAt=100"));
        assertTrue(output.getAll().contains("Retention backlog older than one day"));
        verify(jdbc, never()).queryForObject("SELECT COUNT(*) FROM chat_messages", Long.class);
        verify(jdbc).query(contains("FROM chat_messages WHERE retention_expires_at<=?"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Long>>any(), any(Object[].class));
    }
}
