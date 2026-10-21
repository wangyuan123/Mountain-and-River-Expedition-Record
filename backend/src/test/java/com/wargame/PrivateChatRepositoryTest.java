package com.wargame;

import com.wargame.model.entity.PrivateChatMessage;
import com.wargame.repository.PrivateChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.PageRequest;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.flyway.enabled=false")
@ActiveProfiles("test")
class PrivateChatRepositoryTest {
    @Autowired PrivateChatMessageRepository repository;
    @Test void historyAndConversationsAreScopedToParticipant() {
        long now = System.currentTimeMillis();
        var a = repository.save(new PrivateChatMessage(null, 1L, 2L, "hello", now, false));
        var b = repository.save(new PrivateChatMessage(null, 2L, 1L, "reply", now + 1, false));
        repository.save(new PrivateChatMessage(null, 3L, 4L, "secret", now + 2, false));
        var page = PageRequest.of(0, 50);
        assertEquals(java.util.List.of(b.getId(), a.getId()), repository.history(1L, 2L, page).stream().map(PrivateChatMessage::getId).toList());
        assertTrue(repository.history(3L, 2L, page).isEmpty());
        assertEquals(1, repository.conversations(1L, page).size());
        assertEquals(b.getId(), repository.conversations(1L, page).get(0).getId());
    }
    @Test void readDoesNotConsumeNewMessagesOrOtherRecipients() {
        long now = System.currentTimeMillis();
        var old = repository.save(new PrivateChatMessage(null, 2L, 1L, "old", now, false));
        repository.save(new PrivateChatMessage(null, 2L, 1L, "new", now + 1, false));
        repository.save(new PrivateChatMessage(null, 2L, 3L, "other", now + 2, false));
        repository.markRead(1L, 2L, old.getId());
        assertEquals(1, repository.countBySenderIdAndRecipientIdAndReadByRecipientFalse(2L, 1L));
        assertEquals(1, repository.countBySenderIdAndRecipientIdAndReadByRecipientFalse(2L, 3L));
    }
}
