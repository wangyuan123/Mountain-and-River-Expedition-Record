package com.wargame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 日历型游戏任务也保存游戏时间进度，不能在恢复时跨过停服时段。 */
@Entity
@Table(name = "game_schedule")
public class GameScheduleState {
    @Id @Column(name = "task_key", length = 64) private String key;
    @Column(name = "last_period", nullable = false) private long lastPeriod;
}
