package com.wargame.model.constants;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 玩家可选头像与前端 historical 资源库保持一致，不接受已删除的旧头像或外部 URL。 */
public final class AvatarDef {
    public static final List<String> PRESETS = List.of(
            "img/avatars/historical/rank-private-v1.webp",
            "img/avatars/historical/rank-corporal-v1.webp",
            "img/avatars/historical/rank-sergeant-v1.webp",
            "img/avatars/historical/rank-lieutenant-v1.webp",
            "img/avatars/historical/rank-captain-v1.webp",
            "img/avatars/historical/rank-major-v1.webp",
            "img/avatars/historical/rank-colonel-v1.webp",
            "img/avatars/historical/rank-general-v1.webp");

    private AvatarDef() {}

    public static boolean isPreset(String avatar) {
        return avatar != null && PRESETS.contains(avatar);
    }

    /** 仅在创建账号时随机一次，随玩家记录保存，读取资料不会重新抽取。 */
    public static String randomAvatar() {
        return PRESETS.get(ThreadLocalRandom.current().nextInt(PRESETS.size()));
    }
}
