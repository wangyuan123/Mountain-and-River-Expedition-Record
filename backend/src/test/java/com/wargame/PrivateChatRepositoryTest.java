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
        var a = repository.save(new PrivateChatMessage(null, 1L, 2L, "hello", 1L, false));
        var b = repository.save(new PrivateChatMessage(null, 2L, 1L, "reply", 2L, false));
        repository.save(new PrivateChatMessage(null, 3L, 4L, "secret", 3L, false));
        var page = PageRequest.of(0, 50);
        assertEquals(java.util.List.of(b.getId(), a.getId()), repository.history(1L, 2L, page).stream().map(PrivateChatMessage::getId).toList());
        assertTrue(repository.history(3L, 2L, page).isEmpty());
        assertEquals(1, repository.conversations(1L, page).size());
        assertEquals(b.getId(), repository.conversations(1L, page).get(0).getId());
    }
    @Test void readDoesNotConsumeNewMessagesOrOtherRecipients() {
        var old = repository.save(new PrivateChatMessage(null, 2L, 1L, "old", 1L, false));
        repository.save(new PrivateChatMessage(null, 2L, 1L, "new", 2L, false));
        repository.save(new PrivateChatMessage(null, 2L, 3L, "other", 3L, false));
        repository.markRead(1L, 2L, old.getId());
        assertEquals(1, repository.countBySenderIdAndRecipientIdAndReadByRecipientFalse(2L, 1L));
        assertEquals(1, repository.countBySenderIdAndRecipientIdAndReadByRecipientFalse(2L, 3L));
    }
}
