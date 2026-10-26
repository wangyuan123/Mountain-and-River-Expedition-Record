package com.wargame;

import com.wargame.model.constants.CommanderRenamePolicy;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.Mail;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.CommanderRenameService;
import com.wargame.service.MailService;
import com.wargame.repository.MailRepository;
import com.wargame.service.DepotService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CommanderRenameTest extends BaseServiceTest {
    @Autowired private CommanderRenameService renameService;
    @Autowired private PlayerItemRepository items;
    @Autowired private MailService mailService;
    @Autowired private MailRepository mails;
    @Autowired private DepotService depot;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void consumesOneCardAndStartsThirtyDayRealTimeCooldown() {
        Player player = createTestPlayer("rename-owner", 30);
        addCard(player.getId());

        assertEquals("新统帅", renameService.rename(player.getId(), "新统帅"));
        Player saved = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals("rename-owner", saved.getUsername());
        assertEquals(1, saved.getCommanderRenameCount());
        assertTrue(saved.getCommanderRenamedAt() > 0);
        assertEquals(0, items.findByPlayerIdAndItemKey(player.getId(), CommanderRenamePolicy.ITEM_KEY).orElseThrow().getCount());
        assertEquals(saved.getCommanderRenamedAt() + CommanderRenamePolicy.COOLDOWN_MS,
                CommanderRenamePolicy.nextAllowedAt(saved.getCommanderRenamedAt()));
    }

    @Test
    void duplicateAndCooldownFailuresDoNotConsumeCard() {
        Player owner = createTestPlayer("rename-owner-2", 30);
        Player other = createTestPlayer("rename-other", 30);
        other.setDisplayName("占用名称");
        playerRepository.saveAndFlush(other);
        addCard(owner.getId());

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> renameService.rename(owner.getId(), " 占用名称 "));
        assertEquals("名称不能重复请重新修改", duplicate.getMessage());
        assertEquals(1, cardCount(owner.getId()));

        Player locked = playerRepository.findById(owner.getId()).orElseThrow();
        locked.setCommanderRenamedAt(System.currentTimeMillis());
        locked.setCommanderRenameCount(1);
        playerRepository.saveAndFlush(locked);
        assertThrows(IllegalArgumentException.class, () -> renameService.rename(owner.getId(), "另一个名"));
        assertEquals(1, cardCount(owner.getId()));
    }

    @Test
    void firstUseHasNoCooldownAndBoundaryIsInclusive() {
        assertEquals(0, CommanderRenamePolicy.nextAllowedAt(0));
        long renamedAt = 1_000_000L;
        assertEquals(renamedAt + CommanderRenamePolicy.COOLDOWN_MS,
                CommanderRenamePolicy.nextAllowedAt(renamedAt));
    }

    @Test
    void giftMailIsIdempotentAndDoesNotSuppressNewPlayerSeedMail() {
        Player player = createTestPlayer("rename-gift-owner", 30);

        mailService.seedForNewPlayer(player.getId());
        mailService.seedForNewPlayer(player.getId());

        assertEquals(1, mails.findByToPlayerIdAndIsSystemTrueOrderByCreatedAtDesc(player.getId()).stream()
                .filter(mail -> "统帅改名卡赠礼".equals(mail.getSubject())).count());
        assertTrue(mails.findByToPlayerIdAndIsSystemTrueOrderByCreatedAtDesc(player.getId()).stream()
                .anyMatch(mail -> "欢迎加入战局".equals(mail.getSubject())));
        assertTrue(playerRepository.findById(player.getId()).orElseThrow().isCommanderRenameGiftSent());
    }

    @Test
    void giftMovesIntoDepotOnlyAfterClaimAndCannotBeIssuedAgainAfterDeletion() {
        Player player = createTestPlayer("gift-claim", 30);
        mailService.issueCommanderRenameGift(player.getId());
        Mail gift = mails.findByToPlayerIdOrderByCreatedAtDesc(player.getId()).get(0);
        assertEquals(0, cardCount(player.getId()));
        assertEquals("PROTECTED", gift.getAttachmentPolicy());
        assertNull(gift.getAttachmentExpiresAt());
        assertNull(gift.getRetentionExpiresAt());

        assertEquals(true, mailService.claimAttach(player.getId(), gift.getId()).get("success"));
        assertEquals(1, cardCount(player.getId()));
        mails.deleteById(gift.getId());
        mails.flush();
        mailService.issueCommanderRenameGift(player.getId());
        assertTrue(mails.findByToPlayerIdOrderByCreatedAtDesc(player.getId()).isEmpty());
        assertEquals(1, cardCount(player.getId()));
    }

    @Test
    void duplicateClaimIsRejectedWithoutAddingAnotherCard() {
        Player player = createTestPlayer("gift-once", 30);
        mailService.issueCommanderRenameGift(player.getId());
        Long mailId = mails.findByToPlayerIdOrderByCreatedAtDesc(player.getId()).get(0).getId();
        mailService.claimAttach(player.getId(), mailId);
        assertThrows(IllegalArgumentException.class, () -> mailService.claimAttach(player.getId(), mailId));
        assertEquals(1, cardCount(player.getId()));
    }

    @Test
    void anotherPlayerCannotClaimTheGift() {
        Player owner = createTestPlayer("gift-secure", 30);
        Player other = createTestPlayer("gift-intruder", 30);
        mailService.issueCommanderRenameGift(owner.getId());
        Long mailId = mails.findByToPlayerIdOrderByCreatedAtDesc(owner.getId()).get(0).getId();
        assertThrows(IllegalArgumentException.class, () -> mailService.claimAttach(other.getId(), mailId));
        assertEquals(0, cardCount(other.getId()));
        assertFalse(mails.findById(mailId).orElseThrow().getIsClaimed());
    }

    @Test
    void playersCannotCreateItemAttachments() {
        Player sender = createTestPlayer("gift-forger", 30);
        Player receiver = createTestPlayer("gift-target", 30);
        assertThrows(IllegalArgumentException.class, () -> mailService.send(sender.getId(), receiver.getUsername(),
                "伪造附件", "", List.of(Map.of("type", "item", "itemId", CommanderRenamePolicy.ITEM_KEY, "qty", 1))));
        assertEquals(0, cardCount(receiver.getId()));
    }

    @Test
    void botsDoNotReceiveGift() {
        Player bot = createTestPlayer("gift-bot", 30);
        bot.setIsBot(true);
        playerRepository.saveAndFlush(bot);
        mailService.issueCommanderRenameGift(bot.getId());
        assertTrue(mails.findByToPlayerIdOrderByCreatedAtDesc(bot.getId()).isEmpty());
        assertFalse(playerRepository.findById(bot.getId()).orElseThrow().isCommanderRenameGiftSent());
    }

    @Test
    void expiredCooldownAllowsDepotRenameAndPublishesState() {
        Player player = createTestPlayer("rename-again", 30);
        player.setCommanderRenamedAt(System.currentTimeMillis() - CommanderRenamePolicy.COOLDOWN_MS);
        player.setCommanderRenameCount(1);
        playerRepository.saveAndFlush(player);
        addCard(player.getId());
        assertEquals(true, depot.useItem(player.getId(), CommanderRenamePolicy.ITEM_KEY, null, "再立威名").get("success"));
        entityManager.flush();
        entityManager.clear();
        Player saved = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(2, saved.getCommanderRenameCount());
        Map<?, ?> statePlayer = (Map<?, ?>) gameStateService.getGameState(player.getId()).get("player");
        assertEquals(2, statePlayer.get("commanderRenameCount"));
        assertEquals(CommanderRenamePolicy.nextAllowedAt(saved.getCommanderRenamedAt()), statePlayer.get("commanderNameRenameAvailableAt"));
        assertEquals(0, cardCount(player.getId()));
    }

    @Test
    void oldInterfaceCannotRenameWithoutCommanderCard() {
        Player player = createTestPlayer("rename-no-card", 30);
        PlayerItem officerCard = new PlayerItem();
        officerCard.setPlayerId(player.getId());
        officerCard.setItemKey("renameCard");
        officerCard.setCount(1);
        officerCard.setUpdatedAt(System.currentTimeMillis());
        items.saveAndFlush(officerCard);
        assertThrows(IllegalArgumentException.class, () -> gameStateService.setDisplayName(player.getId(), "不可免费"));
        assertEquals(0, playerRepository.findById(player.getId()).orElseThrow().getCommanderRenameCount());
        assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), "renameCard").orElseThrow().getCount());
    }

    @Test
    void rejectsNfcAndCaseDuplicateNames() {
        Player owner = createTestPlayer("rename-nfc", 30);
        Player other = createTestPlayer("rename-nfc-other", 30);
        other.setDisplayName("ÉCHO");
        playerRepository.saveAndFlush(other);
        addCard(owner.getId());
        assertEquals("名称不能重复请重新修改", assertThrows(IllegalArgumentException.class,
                () -> renameService.rename(owner.getId(), "e\u0301cho")).getMessage());
        assertEquals(1, cardCount(owner.getId()));
    }

    @Test
    void invalidOrUnchangedNameDoesNotConsumeCard() {
        Player player = createTestPlayer("原统帅", 30);
        addCard(player.getId());
        assertThrows(IllegalArgumentException.class, () -> renameService.rename(player.getId(), "原统帅"));
        assertThrows(IllegalArgumentException.class, () -> renameService.rename(player.getId(), "隐\u200b形"));
        assertEquals(1, cardCount(player.getId()));
        assertEquals(0, playerRepository.findById(player.getId()).orElseThrow().getCommanderRenamedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPlayersRacingForOneNameOnlyChargeTheWinner() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long[] playerIds = tx.execute(status -> {
            Player first = createTestPlayer("rename-race-a", 30);
            Player second = createTestPlayer("rename-race-b", 30);
            addCard(first.getId());
            addCard(second.getId());
            return new Long[]{first.getId(), second.getId()};
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> renameFirst = () -> attemptRename(playerIds[0], ready, start);
        Callable<Boolean> renameSecond = () -> attemptRename(playerIds[1], ready, start);
        try {
            Future<Boolean> first = executor.submit(renameFirst);
            Future<Boolean> second = executor.submit(renameSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, cardCount(playerIds[0]) + cardCount(playerIds[1]));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRenameRequestsForOnePlayerConsumeOnlyOneCard() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long playerId = tx.execute(status -> {
            Player player = createTestPlayer("rename-same-player-race", 30);
            addCard(player.getId());
            PlayerItem card = items.findByPlayerIdAndItemKey(player.getId(), CommanderRenamePolicy.ITEM_KEY).orElseThrow();
            card.setCount(2);
            items.saveAndFlush(card);
            return player.getId();
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> attemptRename(playerId, "同账号并发甲", ready, start));
            Future<Boolean> second = executor.submit(() -> attemptRename(playerId, "同账号并发乙", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, cardCount(playerId));
            assertEquals(1, playerRepository.findById(playerId).orElseThrow().getCommanderRenameCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean attemptRename(Long playerId, CountDownLatch ready, CountDownLatch start) throws Exception {
        return attemptRename(playerId, "并发唯一名", ready, start);
    }

    private boolean attemptRename(Long playerId, String name, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            renameService.rename(playerId, name);
            return true;
        } catch (IllegalArgumentException duplicate) {
            return false;
        }
    }

    private void addCard(Long playerId) {
        PlayerItem card = new PlayerItem();
        card.setPlayerId(playerId);
        card.setItemKey(CommanderRenamePolicy.ITEM_KEY);
        card.setCount(1);
        card.setUpdatedAt(System.currentTimeMillis());
        items.saveAndFlush(card);
    }

    private int cardCount(Long playerId) {
        return items.findByPlayerIdAndItemKey(playerId, CommanderRenamePolicy.ITEM_KEY)
                .map(PlayerItem::getCount).orElse(0);
    }
}
