package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.CitySurrenderOfferRepository;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 独立事务竞争，验证扩城名额、槽位和归降兑现不会被并发请求重复使用。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CityCapacityConcurrencyTest extends BaseServiceTest {
    private static final long START = 1_780_000_000_000L;
    private static final long DEADLINE = START + CitySurrenderService.TRANSFER_WINDOW_MS;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CitySurrenderService surrender;
    @Autowired CitySurrenderOfferRepository offers;
    @Autowired EntityManager em;
    @MockBean WebSocketPushService pushes;

    record Fixture(Long recipient, List<Long> donors, List<Long> cities, List<Long> invitations) {}

    private PlayerCity city(Player owner, Long world, int slot, int x) {
        PlayerCity c = new PlayerCity(); c.setOwnerId(owner.getId()); c.setCitySlot(slot); c.setWorldId(world);
        c.setName("并发名额测试城"); c.setX(x); c.setY(10); c.setReadyAt(0L); c.setLastTick(START);
        c = playerCityRepository.saveAndFlush(c);
        if (slot > 0) {
            Resources stock = new Resources(); stock.setPlayerId(owner.getId()); stock.setCitySlot(slot);
            stock.setFood(1000); stock.setGold(1000); resourcesRepository.save(stock);
        }
        return c;
    }

    /** 两份独立邀请同时争夺同一账号名额；准备阶段不预先占用任何名额。 */
    private Fixture fixture(int rank) {
        return new TransactionTemplate(transactions).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Long world = createTestWorld().getId();
            Player recipient = createTestPlayer("cap-r-" + suffix, 0);
            recipient.setMilitaryRank(rank); recipient.setLastTick(START);
            var home = city(recipient, world, 0, 10); recipient.setActiveCityId(home.getId());
            List<Long> donors = new ArrayList<>(), cities = new ArrayList<>(), invitations = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Player donor = createTestPlayer("cap-d-" + i + suffix, 0); donor.setLastTick(START);
                city(donor, world, 0, 20 + i * 20);
                PlayerCity target = city(donor, world, 1, 30 + i * 20);
                CitySurrenderOffer offer = new CitySurrenderOffer();
                offer.setWorldId(world); offer.setInitiatorId(recipient.getId()); offer.setDefenderId(donor.getId());
                offer.setCityId(target.getId()); offer.setCityName(target.getName()); offer.setCityX(target.getX()); offer.setCityY(target.getY());
                offer.setStatus("PENDING"); offer.setSentAt(START); offer.setResponseExpiresAt(DEADLINE);
                offers.saveAndFlush(offer);
                donors.add(donor.getId()); cities.add(target.getId()); invitations.add(offer.getId());
            }
            return new Fixture(recipient.getId(), donors, cities, invitations);
        });
    }

    /** 先建立普通读取上下文，再同时执行请求，覆盖鉴权或页面预读后的锁等待。 */
    private List<String> race(Fixture f, long now, boolean accept) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        // GameClock 是进程共享时钟；由父线程统一安装、撤销，避免并发注册后残留旧时钟。
        try (var clock = GameClock.use(() -> now)) {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int i = index;
                results.add(workers.submit(() -> {
                    try {
                        return new TransactionTemplate(transactions).execute(status -> {
                            playerRepository.findById(f.recipient()).orElseThrow();
                            playerCityRepository.findByOwnerId(f.recipient());
                            offers.findById(f.invitations().get(i)).orElseThrow();
                            await(ready);
                            if (accept) return surrender.respond(f.donors().get(i), f.invitations().get(i), "accept").get("status").toString();
                            surrender.complete(f.invitations().get(i), now);
                            return "COMPLETED";
                        });
                    } catch (IllegalArgumentException error) {
                        assertTrue(error.getMessage().contains("名额不足"), error.getMessage());
                        return "FULL";
                    }
                }));
            }
            return List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
    }

    private void await(CyclicBarrier ready) {
        try { ready.await(10, TimeUnit.SECONDS); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @Test void twoAcceptancesCanReserveTheLastSlotOnlyOnce() throws Exception {
        Fixture f = fixture(2);
        List<String> results = race(f, START, true);
        assertEquals(1, Collections.frequency(results, "ACCEPTED"));
        assertEquals(1, Collections.frequency(results, "FULL"));
        assertEquals(1, offers.countReservedCitySlots(f.recipient()));
        assertEquals(1, f.cities().stream().map(id -> playerCityRepository.findById(id).orElseThrow())
                .filter(c -> c.isSurrenderFrozen(START)).count());
    }

    @Test void twoTransfersUseDistinctSlotsAndRedeemBothReservations() throws Exception {
        Fixture f = fixture(3);
        assertEquals(List.of("ACCEPTED", "ACCEPTED"), race(f, START, true));
        assertEquals(List.of("COMPLETED", "COMPLETED"), race(f, DEADLINE, false));
        var cities = playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(f.recipient());
        assertEquals(List.of(0, 1, 2), cities.stream().map(PlayerCity::getCitySlot).toList());
        assertEquals(0, offers.countReservedCitySlots(f.recipient()));
        for (Long id : f.invitations()) assertEquals("COMPLETED", offers.findById(id).orElseThrow().getStatus());
    }

    @Test void retryRefreshesAnInvitationCachedBeforeAnotherTransactionAcceptedIt() throws Exception {
        Fixture f = fixture(2);
        try (var clock = GameClock.use(() -> START)) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                CitySurrenderOffer cached = offers.findById(f.invitations().get(0)).orElseThrow();
                TransactionTemplate other = new TransactionTemplate(transactions);
                other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                other.executeWithoutResult(inner -> surrender.respond(f.donors().get(0), cached.getId(), "accept"));
                assertEquals("PENDING", cached.getStatus());
                assertEquals("ACCEPTED", surrender.respond(f.donors().get(0), cached.getId(), "accept").get("status"));
                em.flush();
            });
        }
        assertEquals(1, offers.countReservedCitySlots(f.recipient()));
    }
}
