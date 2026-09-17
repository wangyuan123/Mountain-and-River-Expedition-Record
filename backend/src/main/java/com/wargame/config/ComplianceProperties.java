package com.wargame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** 日期必须由运营审核后配置，没有覆盖的日期拒绝未成年人准入。 */
@Data
@Component
@ConfigurationProperties(prefix = "game.compliance")
public class ComplianceProperties {
    // TODO：产品完善后恢复默认启用；防沉迷实现与数据结构暂时保留。
    // private boolean enabled = true;
    private boolean enabled = false;
    private boolean localFixtures;
    private String dataKey = "";
    private String calendarFrom = "";
    private String calendarThrough = "";
    private String calendarSource = "";
    private String policyVersion = "cn-2021-v1";
    private List<String> extraOpenDates = new ArrayList<>();
    private List<String> closedDates = new ArrayList<>();
    private String supportUrl = "";
}
