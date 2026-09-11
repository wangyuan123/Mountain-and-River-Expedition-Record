package com.wargame.model.dto;

import java.util.List;

public final class ChatDtos {

    private ChatDtos() {}

    public record SendRequest(String content) {}

    public record MessageResponse(Long id, Long playerId, String username, String content, Long ts) {}

    public record HistoryResponse(List<MessageResponse> messages) {}
}
