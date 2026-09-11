package com.wargame.model.constants;

import java.util.Map;

/**
 * 星级颜色定义 - 对应 data.js 中 G.DATA.starColor
 */
public final class StarColor {

    private StarColor() {}

    public static final Map<Integer, String> STAR_COLOR = Map.of(
            1, "#bbb",
            2, "#7fc4ff",
            3, "#a070ff",
            4, "#ffa84a",
            5, "#ffe14a"
    );

    public static String getColor(int star) {
        return STAR_COLOR.get(star);
    }
}
