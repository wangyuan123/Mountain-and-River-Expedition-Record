package com.wargame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 每个大区数据库仅存一个游戏时间检查点，不修改已有活动的起止时间。 */
@Entity
@Table(name = "game_clock")
public class GameClockState {
    @Id @Column(name = "singleton_id") private Integer id;
    @Column(name = "game_time", nullable = false) private long gameTime;
    @Column(name = "owner_token", length = 36) private String ownerToken;
    @Column(name = "heartbeat_at", nullable = false) private long heartbeatAt;
}
