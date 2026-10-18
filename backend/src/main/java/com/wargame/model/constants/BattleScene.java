package com.wargame.model.constants;

/** 战场开局缓冲只由真实战斗对象决定，工事与城墙不额外叠加距离。 */
public enum BattleScene {
    PLAYER(1850), NPC(1000), WILD(500);

    private final int buffer;

    BattleScene(int buffer) { this.buffer = buffer; }

    public int buffer() { return buffer; }
}
