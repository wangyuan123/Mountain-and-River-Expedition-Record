package com.wargame.util;

import java.util.function.LongSupplier;

/** 游戏活动只读取已持久化的运行时间；鉴权、合规与运维日志仍使用真实时间。 */
public final class GameClock {
    private static volatile LongSupplier source = System::currentTimeMillis;

    private GameClock() {}

    public static long now() { return source.getAsLong(); }

    /** 安装运行时钟；关闭时仅撤销自己的注册，避免旧容器覆盖新容器的时钟。 */
    public static synchronized AutoCloseable use(LongSupplier clock) {
        LongSupplier previous = source;
        source = clock;
        return () -> {
            synchronized (GameClock.class) {
                if (source == clock) source = previous;
            }
        };
    }
}
