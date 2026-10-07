package com.wargame.model.dto;

public final class PrivateChatDtos {
    private PrivateChatDtos() {}
    public record SendRequest(Long recipientId, String content) {}
    public record ReadRequest(Long throughId) {}
    public record Peer(Long id, String username, String avatar, boolean online, boolean available) {}
    public record Message(Long id, Long playerId, Long recipientId, String username, String avatar, String content, Long ts) {}
    public record Conversation(Peer peer, Message lastMessage, long unread) {}
}
