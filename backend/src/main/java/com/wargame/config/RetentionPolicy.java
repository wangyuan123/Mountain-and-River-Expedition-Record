package com.wargame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 新记录写入明确期限；修改配置不会追溯缩短已有记录。 */
@Data
@Component
@ConfigurationProperties(prefix = "game.retention")
public class RetentionPolicy {
    public static final long DAY = 86_400_000L;
    private int worldChatDays = 7;
    private int guildChatDays = 30;
    private int privateChatDays = 90;
    private int processedMailDays = 30;
    private int unreadMailDays = 60;
    private int giftClaimDays = 30;
    private int giftNoticeDays = 7;
    private int reportDetailDays = 30;
    private int reportSummaryDays = 30;
    private int reportShareDays = 7;
    private int incomingShareHours = 24;
    private int pvpDays = 30;
    private int activityHistoryDays = 90;

    public long days(int value) { return Math.max(1, value) * DAY; }
}
