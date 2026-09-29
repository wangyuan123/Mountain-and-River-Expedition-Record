package com.wargame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 每个后端实例只服务一个大区；数据库、定时任务和实时连接随实例隔离。 */
@Component
public class GameServerIdentity {
    public static final String DEFAULT_ID = "jiangsu-1";

    private final String id;
    private final String name;

    public GameServerIdentity(@Value("${game.server.id:jiangsu-1}") String id,
                              @Value("${game.server.name:江苏一区}") String name) {
        if (id == null || !id.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("无效的服务器大区 ID");
        this.id = id;
        this.name = name;
    }

    public String id() { return id; }
    public String name() { return name; }

    /** 旧客户端仅能落入原有江苏一区，不能误注册到新增大区。 */
    public void requireSelected(String selected) {
        if ((selected == null || selected.isBlank()) && DEFAULT_ID.equals(id)) return;
        if (!id.equals(selected)) throw new IllegalArgumentException("所选服务器大区与当前服务器不一致，请重新选择");
    }
}
