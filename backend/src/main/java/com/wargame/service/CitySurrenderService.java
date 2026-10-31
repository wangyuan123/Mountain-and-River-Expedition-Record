package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Diplomatic player-city transfer. Battle conquest is deliberately not used here. */
@Service
public class CitySurrenderService {
    @org.springframework.beans.factory.annotation.Autowired private jakarta.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private CityCapacityService capacity;
    @org.springframework.beans.factory.annotation.Autowired private CityFreezeService freeze;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TickService economy;
    @org.springframework.beans.factory.annotation.Autowired private CityScope scope;
    private static final Logger log = LoggerFactory.getLogger(CitySurrenderService.class);
    public static final long RESPONSE_WINDOW_MS = 24L * 60L * 60L * 1000L;
    public static final long TRANSFER_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final String PENDING = "PENDING";
    private static final String ACCEPTED = "ACCEPTED";
    private static final String REJECTED = "REJECTED";
    private static final String EXPIRED = "EXPIRED";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";

    private final CitySurrenderOfferRepository offers;
    private final PlayerCityRepository cities;
    private final PlayerRepository players;
    private final MarchRepository marches;
    private final MarketOrderRepository marketOrders;
    private final ResourcesRepository resources;
    private final MailService mail;
    private final CityConquestService cityConquest;
    private final AccountService accounts;
    private final WebSocketPushService push;
    private final TransactionTemplate settlement;

    public CitySurrenderService(CitySurrenderOfferRepository offers, PlayerCityRepository cities,
                                PlayerRepository players,
                                MarchRepository marches, MarketOrderRepository marketOrders,
                                ResourcesRepository resources, MailService mail,
                                CityConquestService cityConquest, AccountService accounts,
                                WebSocketPushService push, PlatformTransactionManager transactionManager) {
        this.offers = offers; this.cities = cities; this.players = players;
        this.marches = marches; this.marketOrders = marketOrders; this.resources = resources;
        this.mail = mail; this.cityConquest = cityConquest; this.accounts = accounts; this.push = push;
        this.settlement = new TransactionTemplate(transactionManager);
        this.settlement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Map<String, Object> offer(Long initiatorId, Long cityId) {
        long now = GameClock.now();
        PlayerCity city = cities.findById(cityId).orElseThrow(() -> new IllegalArgumentException("目标城市不存在"));
        if (city.getOwnerId() == null || city.getCitySlot() == null) throw new IllegalArgumentException("只能向真实玩家城市提出劝降");
        if (initiatorId.equals(city.getOwnerId())) throw new IllegalArgumentException("不能向自己的城市提出劝降");
        Long defenderId = city.getOwnerId();
        Map<Long, Player> lockedPlayers = lockPair(initiatorId, defenderId);
        city = lockCity(cityId);
        if (!defenderId.equals(city.getOwnerId())) throw new IllegalArgumentException("目标城市归属已变化，请重新发起");
        if (city.getReadyAt() != null && city.getReadyAt() > now) throw new IllegalArgumentException("目标城市尚未建成");
        if (city.isSurrenderFrozen(now)) throw new IllegalArgumentException("目标城市正在移交中");
        Player initiator = lockedPlayers.get(initiatorId);
        Player defender = lockedPlayers.get(city.getOwnerId());
        if (!defender.accountActive()) throw new IllegalArgumentException("守方账号不可用");
        CitySurrenderOffer existing = offers.findFirstByCityIdAndStatusIn(cityId, List.of(PENDING, ACCEPTED)).orElse(null);
        if (existing != null) {
            // 重复点击或切换城市后重试同一邀请必须返回原邀请，不能重新计算期限。
            if (PENDING.equals(existing.getStatus()) && initiatorId.equals(existing.getInitiatorId())) return dto(existing, initiatorId);
            throw new IllegalArgumentException("该城市已有待处理的劝降流程");
        }
        // 玩家没有账号级世界字段，使用发起方当前城市所属世界；旧存档缺少当前城市时，回退到首座城市，不能固定假设世界1。
        Long initiatorWorld = Optional.ofNullable(initiator.getActiveCityId()).flatMap(cities::findById)
                .map(PlayerCity::getWorldId)
                .orElseGet(() -> cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(initiatorId).stream()
                        .map(PlayerCity::getWorldId).filter(Objects::nonNull).findFirst().orElse(null));
        if (!Objects.equals(initiatorWorld, city.getWorldId())) throw new IllegalArgumentException("双方不在同一世界");
        if (!capacity.hasRoom(initiatorId, null)) throw new IllegalArgumentException("接收方城市名额不足");

        CitySurrenderOffer offer = new CitySurrenderOffer();
        offer.setWorldId(city.getWorldId()); offer.setInitiatorId(initiatorId); offer.setDefenderId(defender.getId());
        offer.setCityId(city.getId()); offer.setCityName(city.getName()); offer.setCityX(city.getX()); offer.setCityY(city.getY());
        offer.setStatus(PENDING); offer.setSentAt(now); offer.setResponseExpiresAt(now + RESPONSE_WINDOW_MS);
        offer = offers.saveAndFlush(offer);
        String initiatorName = displayName(initiator);
        String defenderName = displayName(defender);
        Mail incoming = mail.sendSystem(defender.getId(), initiatorName, "surrender",
                "【劝降提议】" + city.getName() + " · 24小时内答复",
                invitationBody(initiatorName, defenderName, city, offer), List.of(), offer.getId());
        offer.setOfferMailId(incoming == null ? null : incoming.getId());
        mail.sendSystem(initiatorId, "系统", "surrender",
                "【劝降已送达】" + city.getName() + " · 等待答复",
                "你向 " + defenderName + " 发出的 " + city.getName() + "（" + city.getX() + "," + city.getY() + "）劝降提议已送达。\n\n"
                        + "对方须在 " + format(offer.getResponseExpiresAt()) + " 前接受或拒绝。对方接受后，城市立即冻结，并开始 24 小时移交倒计时；在对方答复前，你不能操作该城市，也不能提前获得城市资产。",
                List.of(), offer.getId());
        offers.save(offer);
        push.pushToPlayer(defender.getId(), "surrender_offer", Map.of("offerId", offer.getId(), "cityId", city.getId()));
        return dto(offer, initiatorId);
    }

    @Transactional
    public Map<String, Object> respond(Long defenderId, Long offerId, String decision) {
        CitySurrenderOffer offer = offers.findById(offerId).orElseThrow(() -> new IllegalArgumentException("劝降邀请不存在"));
        if (!defenderId.equals(offer.getDefenderId())) throw new IllegalArgumentException("无权处理该劝降邀请");
        // 所有接受及兑现入口先锁账号，防止另一邀请持有预留锁等待同一账号而死锁。
        Map<Long, Player> locked = lockPair(defenderId, offer.getInitiatorId());
        offer = lockOffer(offer);
        long now = GameClock.now();
        if (!PENDING.equals(offer.getStatus())) return dto(offer, defenderId);
        if (now >= offer.getResponseExpiresAt()) {
            expireLocked(offer, now);
            return dto(offer, defenderId);
        }
        if (!"accept".equalsIgnoreCase(decision) && !"reject".equalsIgnoreCase(decision)) throw new IllegalArgumentException("答复必须是 accept 或 reject");
        Player defender = locked.get(defenderId);
        Player initiator = locked.get(offer.getInitiatorId());
        PlayerCity city = lockCity(offer.getCityId());
        if (!defenderId.equals(city.getOwnerId())) { cancelLocked(offer, city, now, "目标城市归属已变化"); return dto(offer, defenderId); }
        if ("reject".equalsIgnoreCase(decision)) {
            offer.setStatus(REJECTED); offers.save(offer);
            sendRejected(offer, initiator, defender);
            finishMailRetention(offer);
            return dto(offer, defenderId);
        }
        ensureAcceptable(offer, city, initiator, defender, now);
        // 冻结从接受时刻开始，必须先兑现此前已产生的经济及到期队列。
        try (var ignored = scope.enter(city)) { economy.settleEconomyBeforeChange(defenderId); }
        closeMarketOrders(defenderId, city.getCitySlot(), now);
        offer.setStatus(ACCEPTED); offer.setAcceptedAt(now); offer.setTransferDeadlineAt(now + TRANSFER_WINDOW_MS);
        city.setSurrenderOfferId(offer.getId()); city.setSurrenderRecipientId(initiator.getId()); city.setSurrenderFrozenAt(now);
        city.setSurrenderTransferDeadlineAt(offer.getTransferDeadlineAt());
        cities.save(city); offers.save(offer);
        mail.sendSystem(initiator.getId(), "系统", "surrender", "【劝降已接受】" + city.getName() + " · 24小时后完成移交",
                "" + displayName(defender) + " 已接受你对 " + city.getName() + "（" + city.getX() + "," + city.getY() + "）的劝降提议。\n\n"
                        + "城市已冻结，移交倒计时为 24 小时，预计于 " + format(offer.getTransferDeadlineAt()) + " 完成。移交完成前，你不能进入或操作该城市的经营和军事资产；完成后系统将向双方发送结果电报。", List.of(), offer.getId());
        mail.sendSystem(defender.getId(), "系统", "surrender", "【城市移交进行中】" + city.getName() + " · 只读",
                "你已接受 " + displayName(initiator) + " 对 " + city.getName() + "（" + city.getX() + "," + city.getY() + "）的劝降提议。\n\n"
                        + "城市已进入“归降移交中 · 只读”状态，移交倒计时为 24 小时，预计于 " + format(offer.getTransferDeadlineAt()) + " 完成。倒计时期间不能出征、运输、派遣、驻防、建设、生产、科研、市场交易或改变城内资产；你仍可查看本城资料并切换到其他城市。", List.of(), offer.getId());
        push.pushToPlayer(defenderId, "city_ownership", Map.of("cityId", city.getId(), "surrender", true, "deadline", offer.getTransferDeadlineAt()));
        push.pushToPlayer(initiator.getId(), "surrender_update", Map.of("offerId", offer.getId(), "status", ACCEPTED, "deadline", offer.getTransferDeadlineAt()));
        return dto(offer, defenderId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long playerId, Long offerId) {
        CitySurrenderOffer offer = offers.findById(offerId).orElseThrow(() -> new IllegalArgumentException("劝降邀请不存在"));
        if (!playerId.equals(offer.getInitiatorId()) && !playerId.equals(offer.getDefenderId())) throw new IllegalArgumentException("无权查看该劝降邀请");
        return dto(offer, playerId);
    }

    @Scheduled(fixedDelayString = "${game.surrender-interval:1000}")
    public void settleDue() {
        long now = GameClock.now();
        for (CitySurrenderOffer offer : offers.findPendingDue(now, PageRequest.of(0, 100))) {
            // 同实例调用不会经过事务代理，每份邀请单独提交，失败不影响其余邀请。
            try { settlement.executeWithoutResult(status -> expire(offer.getId(), now)); }
            catch (Exception error) { log.error("劝降过期结算失败 offerId={}", offer.getId(), error); }
        }
        for (CitySurrenderOffer offer : offers.findAcceptedDue(now, PageRequest.of(0, 100))) {
            try { settlement.executeWithoutResult(status -> complete(offer.getId(), now)); }
            catch (Exception error) { log.error("归降移交结算失败 offerId={}", offer.getId(), error); }
        }
    }

    @Transactional
    public void expire(Long offerId, long now) {
        CitySurrenderOffer offer = offers.findById(offerId).orElse(null);
        if (offer == null || !PENDING.equals(offer.getStatus()) || now < offer.getResponseExpiresAt()) return;
        // 过期同样先锁账号，避免邀请状态变更与名额当前读持有的索引区间锁交叉等待。
        new TreeSet<>(List.of(offer.getInitiatorId(), offer.getDefenderId()))
                .forEach(id -> players.findById(id).ifPresent(p -> accounts.lockPlayer(id)));
        offer = lockOffer(offer);
        if (PENDING.equals(offer.getStatus()) && now >= offer.getResponseExpiresAt()) expireLocked(offer, now);
    }

    @Transactional
    public void complete(Long offerId, long now) {
        CitySurrenderOffer offer = offers.findById(offerId).orElse(null);
        if (offer == null || !ACCEPTED.equals(offer.getStatus()) || now < offer.getTransferDeadlineAt()) return;
        PlayerCity city = cities.findById(offer.getCityId()).orElse(null);
        // 与接受和库存巡检保持同一锁顺序；异常归属也要锁现城主后再恢复该城时钟。
        Set<Long> participants = new TreeSet<>(List.of(offer.getInitiatorId(), offer.getDefenderId()));
        if (city != null && city.getOwnerId() != null) participants.add(city.getOwnerId());
        Map<Long, Player> locked = new HashMap<>();
        participants.forEach(id -> players.findById(id).ifPresent(p -> locked.put(id, accounts.lockPlayer(id))));
        offer = lockOffer(offer);
        if (!ACCEPTED.equals(offer.getStatus()) || now < offer.getTransferDeadlineAt()) return;
        if (city != null) city = lockCity(city.getId());
        if (city == null || !offer.getDefenderId().equals(city.getOwnerId()) || !offer.getId().equals(city.getSurrenderOfferId())) {
            if (city != null) cancelLocked(offer, city, now, "目标城市归属已变化"); else cancelLocked(offer, null, now, "目标城市不存在");
            return;
        }
        Player initiator = locked.get(offer.getInitiatorId());
        Player defender = locked.get(offer.getDefenderId());
        if (initiator == null || defender == null || !initiator.accountActive() || !defender.accountActive()) {
            cancelLocked(offer, city, now, "参与账号已失效");
            return;
        }
        // 历史异常预留也在此复核；失败恢复原城，不能使城市无限期冻结。
        if (!capacity.hasRoom(initiator.getId(), offer.getId())) {
            cancelLocked(offer, city, now, "接收方城市名额不足，城市已恢复原归属");
            return;
        }
        cityConquest.peacefulTransfer(initiator.getId(), city, now);
        offer.setStatus(COMPLETED); offer.setCompletedAt(now); offers.save(offer);
        mail.sendSystem(defender.getId(), "系统", "surrender", "【城市移交完成】" + offer.getCityName(),
                offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）已按你接受的劝降提议完成移交。该城市及电报列明的城内资产已转交给 " + displayName(initiator) + "；该城所属野地随城转交；你的账号资产、科技、军衔、声望、任务、军团关系、其他城市领地、钻石和账号道具保留。", List.of(), offer.getId());
        mail.sendSystem(initiator.getId(), "系统", "surrender", "【城市移交完成】" + offer.getCityName(),
                offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）已完成和平移交，现归你管理。城市及电报列明的城内资产已完整转入新的城市槽位；本次移交不产生战斗、掠夺、声望、经验或俘虏奖励。", List.of(), offer.getId());
        push.pushToPlayer(defender.getId(), "city_ownership", Map.of("cityId", offer.getCityId(), "gained", false));
        push.pushToPlayer(initiator.getId(), "city_ownership", Map.of("cityId", offer.getCityId(), "gained", true));
        finishMailRetention(offer);
    }

    /**
     * 接受前重新检查名额、账号和全部城市相关行军，防止邀请发出后
     * 新增运输、驻防或攻击在冻结期间抵达，造成移交资产不确定。
     */
    private void ensureAcceptable(CitySurrenderOffer offer, PlayerCity city, Player initiator, Player defender, long now) {
        if (!capacity.hasRoom(initiator.getId(), null)) throw new IllegalArgumentException("接收方城市名额不足");
        if (city.isSurrenderFrozen(now)) throw new IllegalArgumentException("城市正在移交中");
        if (!marches.findUnfinishedMarchesTargetingPlayerCity(city.getId().toString()).isEmpty()) {
            throw new IllegalArgumentException("目标城市仍有在途行军");
        }
        if (!marches.findByPlayerIdAndCitySlot(defender.getId(), city.getCitySlot()).isEmpty()) {
            throw new IllegalArgumentException("目标城市仍有未完成行军");
        }
        if (!defender.accountActive() || !initiator.accountActive()) throw new IllegalArgumentException("参与账号不可用");
    }

    private void closeMarketOrders(Long playerId, Integer slot, long now) {
        Resources stock = resources.findByPlayerIdAndCitySlot(playerId, slot).orElseThrow(() -> new IllegalArgumentException("城市资源不存在"));
        for (MarketOrder order : marketOrders.findBySellerIdOrderByCreatedAtDesc(playerId)) {
            if (!Objects.equals(slot, order.getSellerCitySlot()) || !"ACTIVE".equals(order.getStatus())) continue;
            switch (order.getResourceType()) {
                case "food", "steel", "oil", "rare" -> stock.addResource(order.getResourceType(), order.getAmount());
                default -> throw new IllegalStateException("未知市场资源");
            }
            order.setStatus("CANCELLED"); order.setCompletedAt(java.time.LocalDateTime.now());
        }
        resources.save(stock);
    }

    private void expireLocked(CitySurrenderOffer offer, long now) {
        offer.setStatus(EXPIRED); offers.save(offer);
        Player initiator = players.findById(offer.getInitiatorId()).orElse(null);
        Player defender = players.findById(offer.getDefenderId()).orElse(null);
        if (defender != null) mail.sendSystem(defender.getId(), "系统", "surrender", "【劝降已过期】" + offer.getCityName(), "你收到的 " + displayName(initiator) + " 对 " + offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）的劝降提议已超过 24 小时答复期限，现已自动失效。城市未被冻结，归属、资产和队列均未改变。", List.of(), offer.getId());
        if (initiator != null) mail.sendSystem(initiator.getId(), "系统", "surrender", "【劝降已过期】" + offer.getCityName(), "你对 " + offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）发出的劝降提议在 24 小时内未获答复，现已自动失效。城市未被冻结，若需继续协商，请重新发起提议。", List.of(), offer.getId());
        finishMailRetention(offer);
    }

    private void cancelLocked(CitySurrenderOffer offer, PlayerCity city, long now, String reason) {
        offer.setStatus(CANCELLED); offer.setCancelledAt(now); offer.setCancelReason(reason); offers.save(offer);
        if (city != null && Objects.equals(city.getSurrenderOfferId(), offer.getId())) {
            freeze.resume(city, now);
            city.setSurrenderOfferId(null); city.setSurrenderRecipientId(null); city.setSurrenderFrozenAt(null);
            city.setSurrenderTransferDeadlineAt(null); cities.save(city);
        }
        for (Long playerId : List.of(offer.getInitiatorId(), offer.getDefenderId())) {
            if (players.existsById(playerId)) mail.sendSystem(playerId, "系统", "surrender", "【劝降已取消】" + offer.getCityName(), offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）的劝降流程已取消，原因：" + reason + "。城市冻结状态已解除，未完成的移交不会执行。", List.of(), offer.getId());
        }
        finishMailRetention(offer);
    }

    /** 在终结事务内启动已处理邮件的倒计时，不依赖维护任务是否已开启。 */
    private void finishMailRetention(CitySurrenderOffer offer) {
        offer.setEndedRealAt(System.currentTimeMillis());
        offers.saveAndFlush(offer);
        mail.businessEnded(offer.getId(), offer.getEndedRealAt());
    }

    private void sendRejected(CitySurrenderOffer offer, Player initiator, Player defender) {
        mail.sendSystem(initiator.getId(), "系统", "surrender", "【劝降已拒绝】" + offer.getCityName(), displayName(defender) + " 已拒绝你对 " + offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）的劝降提议。城市未被冻结，归属、资产和所有进行中的队列均未改变。", List.of(), offer.getId());
        mail.sendSystem(defender.getId(), "系统", "surrender", "【已拒绝劝降】" + offer.getCityName(), "你已拒绝 " + displayName(initiator) + " 对 " + offer.getCityName() + "（" + offer.getCityX() + "," + offer.getCityY() + "）的劝降提议。城市未被冻结，当前归属、资产和队列继续正常运行。", List.of(), offer.getId());
    }

    private Map<String, Object> dto(CitySurrenderOffer offer, Long viewerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offerId", offer.getId()); out.put("cityId", offer.getCityId()); out.put("cityName", offer.getCityName());
        out.put("cityX", offer.getCityX()); out.put("cityY", offer.getCityY()); out.put("status", offer.getStatus());
        out.put("initiatorId", offer.getInitiatorId()); out.put("defenderId", offer.getDefenderId()); out.put("viewerCanRespond", viewerId.equals(offer.getDefenderId()) && PENDING.equals(offer.getStatus()));
        out.put("sentAt", offer.getSentAt()); out.put("responseExpiresAt", offer.getResponseExpiresAt()); out.put("acceptedAt", offer.getAcceptedAt()); out.put("transferDeadlineAt", offer.getTransferDeadlineAt());
        return out;
    }

    private String invitationBody(String initiator, String defender, PlayerCity city, CitySurrenderOffer offer) {
        return "致 " + defender + "：\n\n我方 " + initiator + " 向贵方提出和平移交提议：若你接受，" + city.getName() + "（" + city.getX() + "," + city.getY() + "）将移交给我方管理，城市中的建筑、普通资源、部队、城防、军官及军官装备完整保留。\n\n你有 24 小时作出决定。接受后，该城市立即进入“归降移交中”状态并冻结所有经营和军事写操作；冻结持续 24 小时，倒计时结束后完成城市移交。\n\n移交完成后，城市归属、城市槽位和城内资产一次性变更。该城所属野地随城市转移；账号科技、军衔、声望、任务、军团关系、其他城市领地、钻石和账号道具不随城市转移。接受后双方均不能撤销。若 24 小时内未答复，本提议自动失效；城市不会被冻结，现有归属和资产不变。\n\n答复截止时间：" + format(offer.getResponseExpiresAt());
    }

    private static String displayName(Player player) { return player == null ? "对方玩家" : com.wargame.util.PlayerNames.commanderName(player); }
    /** 邮件中的绝对时间仅为发送时预计值，后续停服顺延，实际期限以游戏倒计时为准。 */
    private static String format(Long time) {
        return time == null ? "-" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new Date(System.currentTimeMillis() + time - GameClock.now())) + "（停服顺延）";
    }

    private Map<Long, Player> lockPair(Long first, Long second) {
        Map<Long, Player> result = new HashMap<>();
        new TreeSet<>(List.of(first, second)).forEach(id -> result.put(id, accounts.lockPlayer(id)));
        return result;
    }

    /** 账号锁等待后刷新邀请，重复答复及到期任务只能兑现一次。 */
    private CitySurrenderOffer lockOffer(CitySurrenderOffer offer) {
        em.flush();
        em.refresh(offer, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return offer;
    }

    /** 账号锁之后再刷新城市，锁等待前读到的冻结状态不能覆盖已经提交的移交。 */
    private PlayerCity lockCity(Long id) {
        PlayerCity city = cities.findById(id).orElseThrow(() -> new IllegalArgumentException("目标城市不存在"));
        em.flush();
        em.refresh(city, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return city;
    }
}
