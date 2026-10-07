package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.config.GameWebSocketHandler;
import com.wargame.repository.*;
import com.wargame.security.RateLimiter;
import com.wargame.service.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;

class PrivateChatServiceTest {
    PrivateChatMessageRepository messages;
    PlayerRepository players;
    WebSocketPushService push;
    PrivateChatService service;
    Player recipient;
    PrivateChatConversationRepository sessions;
    GameWebSocketHandler presence;
    @BeforeEach void setup() {
        messages = mock(PrivateChatMessageRepository.class);
        players = mock(PlayerRepository.class);
        push = mock(WebSocketPushService.class);
        var chat = mock(ChatService.class);
        when(chat.filterContent(anyString())).thenReturn("filtered");
        sessions = mock(PrivateChatConversationRepository.class);
        presence = mock(GameWebSocketHandler.class);
        service = new PrivateChatService(messages, players, new RateLimiter(), push, chat, sessions, presence);
        var sender = new Player(); sender.setId(1L); sender.setUsername("alice"); sender.setPrestige(0);
        recipient = new Player(); recipient.setId(2L); recipient.setUsername("bob");
        when(players.findById(1L)).thenReturn(Optional.of(sender));
        when(players.lockById(1L)).thenReturn(Optional.of(sender));
        when(players.findById(2L)).thenReturn(Optional.of(recipient));
        when(messages.save(any())).thenAnswer(inv -> { PrivateChatMessage m = inv.getArgument(0); m.setId(10L); return m; });
    }
    @Test void openingEmptyConversationPersistsOnlyForOwnerAndReportsPresence() {
        when(presence.isPlayerOnline(2L)).thenReturn(true);
        var peer = service.open(1L, 2L);
        assertTrue(peer.online());
        assertTrue(peer.available());
        verify(sessions).save(argThat(c -> c.getOwnerId().equals(1L) && c.getPeerId().equals(2L)));
        verifyNoInteractions(push, messages);
    }
    @Test void listIncludesSeveralEmptyConversationsAndKeepsTheirStatus() {
        var third = new Player(); third.setId(3L); third.setUsername("charlie");
        when(players.findById(3L)).thenReturn(Optional.of(third));
        when(sessions.findTop50ByOwnerIdOrderByOpenedAtDesc(1L)).thenReturn(java.util.List.of(
                new PrivateChatConversation(1L, 1L, 2L, 10L), new PrivateChatConversation(2L, 1L, 3L, 20L)));
        var rows = service.conversations(1L);
        assertEquals(2, rows.size());
        assertEquals(3L, rows.get(0).peer().id());
        assertNull(rows.get(0).lastMessage());
        assertFalse(rows.get(0).peer().online());
    }
    @Test void deletedEmptyConversationStaysHiddenUntilReopened() {
        var session = new PrivateChatConversation(1L, 1L, 2L, 10L);
        session.setDeletedThrough(1L);
        when(sessions.findByOwnerId(1L)).thenReturn(java.util.List.of(session));
        when(sessions.findTop50ByOwnerIdOrderByOpenedAtDesc(1L)).thenReturn(java.util.List.of(session));
        assertTrue(service.conversations(1L).isEmpty());
        when(sessions.findByOwnerIdAndPeerId(1L, 2L)).thenReturn(Optional.of(session));
        service.open(1L, 2L);
        assertEquals(0L, session.getDeletedThrough());
        assertEquals(1, service.conversations(1L).size());
    }
    @Test void deletingConversationOnlyUpdatesOwnerAndReadBoundary() {
        when(messages.history(eq(1L), eq(2L), any())).thenReturn(java.util.List.of(
                new PrivateChatMessage(15L, 2L, 1L, "hello", 1L, false)));
        service.deleteConversation(1L, 2L);
        verify(sessions).save(argThat(c -> c.getOwnerId().equals(1L) && c.getDeletedThrough().equals(15L)));
        verify(messages).markRead(1L, 2L, 15L);
        verifyNoInteractions(push);
    }
    @Test void sendsOnlyToBothParticipantsWithoutPrestigeRequirement() {
        var response = service.send(1L, 2L, "hello");
        assertEquals("filtered", response.content());
        verify(push).pushToPlayer(1L, "private_chat", response);
        verify(push).pushToPlayer(2L, "private_chat", response);
        verifyNoMoreInteractions(push);
        assertThrows(IllegalArgumentException.class, () -> service.send(1L, 2L, "again"));
    }
    @Test void rejectsSelfInactiveMissingAndInvalidContentBeforeSaving() {
        assertThrows(IllegalArgumentException.class, () -> service.send(1L, 1L, "hello"));
        assertThrows(IllegalArgumentException.class, () -> service.send(1L, 9L, "hello"));
        assertThrows(IllegalArgumentException.class, () -> service.send(1L, 2L, " \u200B "));
        assertThrows(IllegalArgumentException.class, () -> service.send(1L, 2L, "a".repeat(81)));
        recipient.setAccountStatus("DELETED");
        assertThrows(IllegalArgumentException.class, () -> service.send(1L, 2L, "hello"));
        verify(messages, never()).save(any());
        verifyNoInteractions(push);
    }
}
