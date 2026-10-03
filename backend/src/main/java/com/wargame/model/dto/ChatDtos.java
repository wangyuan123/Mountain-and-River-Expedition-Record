package com.wargame.model.dto;

import java.util.List;

public final class ChatDtos {

    private ChatDtos() {}

    public record SendRequest(String content) {}

    /** 世界频道消息；avatar 使用玩家当前选定的预设头像，系统消息为空。 */
    public record MessageResponse(Long id, Long playerId, String username, String content, Long ts, String avatar) {}

    public record HistoryResponse(List<MessageResponse> messages) {}

    /** 军团频道消息；包含所在军团ID、角色职务与消息类型。 */
    public record GuildMessageResponse(Long id, Long guildId, Long playerId, String username, String role, String content, Long ts, String avatar, String type) {}

    public record GuildHistoryResponse(List<GuildMessageResponse> messages) {}
}
