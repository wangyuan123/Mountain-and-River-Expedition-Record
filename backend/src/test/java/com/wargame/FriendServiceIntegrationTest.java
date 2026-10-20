package com.wargame;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.dto.FriendDtos;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.security.RateLimiter;
import com.wargame.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Import({FriendService.class, PrivateChatService.class, RateLimiter.class})
class FriendServiceIntegrationTest {
    @Autowired FriendService friends;
    @Autowired PrivateChatService chat;
    @Autowired PlayerRepository players;
    @Autowired FriendContactRepository contacts;
    @Autowired FriendRequestRepository requests;
    @Autowired FriendGroupRepository groups;
    @Autowired PrivateChatMessageRepository messages;
    @Autowired EntityManager em;
    @MockBean GameWebSocketHandler presence;
    @MockBean WebSocketPushService push;
    @MockBean ChatService worldChat;
    Player alice, bob, charlie;

    @BeforeEach void setup() {
        alice = player("alice"); bob = player("bob"); charlie = player("charlie");
        when(worldChat.filterContent(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }
    Player player(String name) {
        Player p = new Player(); p.setUsername(name); p.setPasswordHash("test-only");
        return players.saveAndFlush(p);
    }
    void directFriends() {
        friends.updateSettings(bob.getId(), new FriendDtos.Settings("ANYONE", false));
        assertFalse(friends.add(alice.getId(), bob.getId(), 0L).pending());
    }

    @Test void defaultApprovalRequiresRecipientConsentAndIsIdempotent() {
        assertEquals("APPROVAL", friends.list(bob.getId()).settings().friendRequestPolicy());
        assertEquals("我的好友", friends.list(alice.getId()).groups().get(0).name());
        assertTrue(friends.add(alice.getId(), bob.getId(), 0L).pending());
        assertTrue(friends.add(alice.getId(), bob.getId(), 0L).pending());
        assertEquals(1, requests.count());
        assertEquals(0, contacts.count());
        var request = friends.list(bob.getId()).requests().get(0);
        assertFalse(request.outgoing());
        assertEquals("INCOMING", friends.profile(bob.getId(), alice.getId()).requestState());
        assertThrows(IllegalArgumentException.class, () -> friends.respond(alice.getId(), request.id(), true));
        assertThrows(IllegalArgumentException.class, () -> friends.respond(charlie.getId(), request.id(), true));
        assertThrows(IllegalArgumentException.class, () -> friends.respond(charlie.getId(), request.id(), false));
        friends.respond(bob.getId(), request.id(), true);
        em.flush(); em.clear();
        assertEquals(0, requests.count());
        assertTrue(friends.profile(alice.getId(), bob.getId()).friend());
        assertTrue(friends.profile(bob.getId(), alice.getId()).friend());
        verify(push, atLeastOnce()).pushToPlayer(eq(bob.getId()), eq("friend_request"), any());
        verify(push, atLeastOnce()).pushToPlayer(eq(alice.getId()), eq("friend_request"), any());
    }

    @Test void rejectionWithdrawalAndPrivacyPoliciesCannotBeBypassed() {
        friends.updateSettings(bob.getId(), new FriendDtos.Settings("NONE", true));
        em.flush(); em.clear();
        assertThrows(IllegalArgumentException.class, () -> friends.add(alice.getId(), bob.getId(), 0L));
        assertThrows(IllegalArgumentException.class, () -> chat.send(alice.getId(), bob.getId(), "hello"));
        assertEquals(0, messages.count()); verifyNoInteractions(push);
        friends.updateSettings(bob.getId(), new FriendDtos.Settings("APPROVAL", true));
        friends.add(alice.getId(), bob.getId(), 0L);
        var request = requests.findBySenderIdAndRecipientId(alice.getId(), bob.getId()).orElseThrow();
        friends.respond(bob.getId(), request.getId(), false);
        em.flush();
        assertEquals(0, contacts.count());
        assertThrows(IllegalArgumentException.class, () -> friends.add(alice.getId(), bob.getId(), 0L));
        friends.add(charlie.getId(), bob.getId(), 0L);
        var outgoing = requests.findBySenderIdAndRecipientId(charlie.getId(), bob.getId()).orElseThrow();
        friends.respond(charlie.getId(), outgoing.getId(), false);
        em.flush(); assertEquals(0, requests.count());
    }

    @Test void strangerMessagesCanBeEnabledWhileApprovedFriendsCanAlwaysSend() {
        friends.updateSettings(bob.getId(), new FriendDtos.Settings("ANYONE", true));
        friends.add(alice.getId(), bob.getId(), 0L);
        assertEquals("friend", chat.send(alice.getId(), bob.getId(), "friend").content());
        assertThrows(IllegalArgumentException.class, () -> chat.send(charlie.getId(), bob.getId(), "stranger"));
        friends.updateSettings(bob.getId(), new FriendDtos.Settings("NONE", false));
        assertEquals("allowed", chat.send(charlie.getId(), bob.getId(), "allowed").content());
        assertThrows(IllegalArgumentException.class, () -> friends.updateSettings(bob.getId(), new FriendDtos.Settings("invalid", false)));
    }

    @Test void groupsArePrivateAndDeletingAGroupMovesEvenBlockedFriendsToDefault() {
        directFriends();
        Long group = friends.saveGroup(alice.getId(), null, "同袍").id();
        assertEquals(group, friends.move(alice.getId(), bob.getId(), group).groupId());
        assertEquals(0L, friends.profile(bob.getId(), alice.getId()).groupId());
        assertThrows(IllegalArgumentException.class, () -> friends.move(bob.getId(), alice.getId(), group));
        assertThrows(IllegalArgumentException.class, () -> friends.deleteGroup(bob.getId(), group));
        assertThrows(IllegalArgumentException.class, () -> friends.saveGroup(alice.getId(), null, "同袍"));
        assertThrows(IllegalArgumentException.class, () -> friends.saveGroup(alice.getId(), null, "我的好友"));
        assertThrows(IllegalArgumentException.class, () -> friends.saveGroup(alice.getId(), null, "x\ny"));
        assertThrows(IllegalArgumentException.class, () -> friends.deleteGroup(alice.getId(), 0L));
        assertEquals("先锋", friends.saveGroup(alice.getId(), group, "先锋").name());
        friends.block(alice.getId(), bob.getId(), true);
        friends.deleteGroup(alice.getId(), group);
        em.flush(); em.clear();
        assertEquals(0L, friends.profile(alice.getId(), bob.getId()).groupId());
        assertTrue(friends.profile(alice.getId(), bob.getId()).blocked());
    }

    @Test void blacklistsBlockBothDirectionsPreserveGroupsAndSurviveFriendRemoval() {
        directFriends();
        friends.block(alice.getId(), bob.getId(), true);
        assertThrows(IllegalArgumentException.class, () -> chat.send(alice.getId(), bob.getId(), "blocked"));
        assertThrows(IllegalArgumentException.class, () -> chat.send(bob.getId(), alice.getId(), "blocked"));
        assertEquals(0, messages.count());
        friends.block(alice.getId(), bob.getId(), false);
        assertTrue(friends.profile(alice.getId(), bob.getId()).friend());
        assertEquals("restored", chat.send(bob.getId(), alice.getId(), "restored").content());
        friends.block(alice.getId(), bob.getId(), true);
        friends.remove(alice.getId(), bob.getId());
        assertFalse(friends.profile(bob.getId(), alice.getId()).friend());
        assertTrue(friends.profile(alice.getId(), bob.getId()).blocked());
        friends.block(alice.getId(), bob.getId(), false);
        em.flush(); assertEquals(0, contacts.count());
    }

    @Test void blockingStrangerCancelsRequestsWithoutCreatingFriendship() {
        friends.add(alice.getId(), bob.getId(), 0L);
        friends.block(bob.getId(), alice.getId(), true);
        em.flush();
        assertEquals(0, requests.count());
        assertFalse(friends.profile(bob.getId(), alice.getId()).friend());
        assertThrows(IllegalArgumentException.class, () -> friends.add(alice.getId(), bob.getId(), 0L));
        friends.block(bob.getId(), alice.getId(), false);
        em.flush(); assertEquals(0, contacts.count());
    }

    @Test void clearingOnlyOwnHistoryPersistsAfterReopenAndNewMessagesRemainVisible() {
        var old = messages.saveAndFlush(new PrivateChatMessage(null, bob.getId(), alice.getId(), "old", 1L, false));
        chat.clear(alice.getId(), bob.getId());
        em.flush(); em.clear();
        chat.open(alice.getId(), bob.getId());
        assertEquals(old.getId(), chat.historyPage(alice.getId(), bob.getId()).clearedThrough());
        assertTrue(chat.history(alice.getId(), bob.getId()).isEmpty());
        assertEquals(1, chat.history(bob.getId(), alice.getId()).size());
        assertNull(chat.conversations(alice.getId()).get(0).lastMessage());
        assertEquals(0, chat.conversations(alice.getId()).get(0).unread());
        var next = messages.saveAndFlush(new PrivateChatMessage(null, bob.getId(), alice.getId(), "new", 2L, false));
        assertEquals(java.util.List.of(next.getId()), chat.history(alice.getId(), bob.getId()).stream().map(m -> m.id()).toList());
        assertEquals(1, chat.conversations(alice.getId()).get(0).unread());
        chat.deleteConversation(alice.getId(), bob.getId());
        assertTrue(chat.conversations(alice.getId()).isEmpty());
        chat.open(alice.getId(), bob.getId());
        assertEquals(1, chat.history(alice.getId(), bob.getId()).size());
    }

    @Test void selfMissingInactiveAndForeignIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> friends.add(alice.getId(), alice.getId(), 0L));
        assertThrows(IllegalArgumentException.class, () -> friends.search(alice.getId(), "missing"));
        bob.setAccountStatus("DELETED"); players.saveAndFlush(bob);
        assertThrows(IllegalArgumentException.class, () -> friends.add(alice.getId(), bob.getId(), 0L));
        assertThrows(IllegalArgumentException.class, () -> friends.search(alice.getId(), "bob"));
        assertThrows(IllegalArgumentException.class, () -> friends.move(alice.getId(), charlie.getId(), 99L));
        assertEquals(charlie.getId(), friends.search(alice.getId(), charlie.getId().toString()).id());
    }
}
