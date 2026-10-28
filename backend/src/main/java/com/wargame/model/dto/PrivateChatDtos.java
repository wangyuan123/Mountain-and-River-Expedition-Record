package com.wargame.model.dto;

public final class PrivateChatDtos {
    private PrivateChatDtos() {}
    public record SendRequest(Long recipientId, String content) {}
    public record ReadRequest(Long throughId) {}
    public record Peer(Long id, String username, String avatar, boolean online, boolean available, boolean blocked) {}
    public record History(java.util.List<Message> messages, long clearedThrough) {}
    public record Message(
            Long id,
            Long playerId,
            Long recipientId,
            String username,
            String avatar,
            String content,
            Long ts,
            Integer vipLevel,
            Boolean vipBadgeVisible,
            String avatarFrame) {
        public Message(Long id, Long playerId, Long recipientId, String username, String avatar, String content, Long ts) {
            this(id, playerId, recipientId, username, avatar, content, ts, 0, false, "default");
        }
    }
    public record Conversation(Peer peer, Message lastMessage, long unread) {}
}
