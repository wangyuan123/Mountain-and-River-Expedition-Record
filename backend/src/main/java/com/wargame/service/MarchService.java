package com.wargame.service;

import com.wargame.util.PlayerNames;

import com.wargame.util.DurationText;

import com.wargame.util.GameClock;
import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.BattlePrestige;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.model.constants.TechnologyRules;
import com.wargame.model.constants.ReconRules;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.constants.NewbieProtection;
import com.wargame.model.constants.StratagemRules;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.JointDefenseState;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.ConquestWallState;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.BattleScene;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 行军服务 - 对应 JS core.js processMarches / processIncoming 和 world.js launchDispatch。
 * <p>
 * 处理所有行军（部队移动）逻辑：采集、征服野地、攻击流寇/NPC城/玩家城与侦查。
 */
@Service
public class MarchService {
    @org.springframework.beans.factory.annotation.Autowired private WarPowerProtectionService warPowerProtection;
    @org.springframework.beans.factory.annotation.Autowired private TacticalOrderService tacticalOrders;
    @org.springframework.beans.factory.annotation.Autowired private TacticalBattleOutcomeService battleOutcomes;
    @org.springframework.beans.factory.annotation.Autowired private OfficerEquipmentProgressService equipmentProgress;

    @org.springframework.beans.factory.annotation.Autowired private IncomingIntelligenceService incomingIntelligence;

    private static final double WILD_GATHER_BASE_SPEED = 100.0;
    /** 世界战斗统一使用12秒游戏时间决策窗口，清军、攻墙与离线推进共用。 */
    private static final long TACTICAL_ROUND_TIMEOUT_MS = 12_000L;

    @org.springframework.beans.factory.annotation.Autowired private PrisonerService prisonerService;
    @org.springframework.beans.factory.annotation.Autowired private AccountService accounts;
    @org.springframework.beans.factory.annotation.Autowired private RankService ranks;
    @org.springframework.beans.factory.annotation.Autowired private GuildRelationService guildRelations;
    @org.springframework.beans.factory.annotation.Autowired private ArmyService armyService;
    @org.springframework.beans.factory.annotation.Autowired private BattleActionPreferences battleActionPreferences;
    @org.springframework.beans.factory.annotation.Autowired private CityConquestService cityConquest;
    @org.springframework.beans.factory.annotation.Autowired private GuildSupportService guildSupport;
    @org.springframework.beans.factory.annotation.Autowired private WorldTerrainService terrain;
    @org.springframework.beans.factory.annotation.Autowired private WildOccupationService wildOccupation;
    @org.springframework.beans.factory.annotation.Autowired private EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private MailService mailService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired
    private MarchRouteService routes;

    private final MarchTargetService targets;
    private final WoundedService woundedService;
    private final MarchRepository marchRepository;
    private final CityStateRepository cityStateRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final ResourcesRepository resourcesRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final WildTileRepository wildTileRepository;
    private final BanditRepository banditRepository;
    private final NpcCityRepository npcCityRepository;
    private final PlayerCityRepository playerCityRepository;
    private final ScoutReportRepository scoutReportRepository;
    private final TechnologyRepository technologyRepository;
    private final PlayerRepository playerRepository;
    private final OfficerRepository officerRepository;
    private final EquipmentService equipmentService;
    private final BuildingRepository buildingRepository;
    private final FortificationRepository fortificationRepository;
    private final BattleService battleService;
    private final BattleSessionRepository battleSessionRepository;
    private final WebSocketPushService pushService;
    private final PlayerItemRepository playerItemRepository;
    private final com.wargame.service.quest.QuestService questService;
    private final NpcCitySpawnService npcCitySpawnService;

    public MarchService(WoundedService woundedService, MarchTargetService targets, MarchRepository marchRepository,
                        CityStateRepository cityStateRepository,
                        IncomingMarchRepository incomingMarchRepository,
                        ResourcesRepository resourcesRepository,
                        ArmyUnitRepository armyUnitRepository,
                        WildTileRepository wildTileRepository,
                        BanditRepository banditRepository,
                        NpcCityRepository npcCityRepository,
                        PlayerCityRepository playerCityRepository,
                        ScoutReportRepository scoutReportRepository,
                        TechnologyRepository technologyRepository,
                        PlayerRepository playerRepository,
                        OfficerRepository officerRepository,
                        BuildingRepository buildingRepository,
                        FortificationRepository fortificationRepository,
                        @Lazy BattleService battleService,
                        BattleSessionRepository battleSessionRepository,
                        @Lazy WebSocketPushService pushService,
                        EquipmentService equipmentService,
                        PlayerItemRepository playerItemRepository,
                        com.wargame.service.quest.QuestService questService,
                        NpcCitySpawnService npcCitySpawnService) {
        this.targets = targets;
        this.woundedService = woundedService;
        this.marchRepository = marchRepository;
        this.cityStateRepository = cityStateRepository;
        this.incomingMarchRepository = incomingMarchRepository;
        this.resourcesRepository = resourcesRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.wildTileRepository = wildTileRepository;
        this.banditRepository = banditRepository;
        this.npcCityRepository = npcCityRepository;
        this.playerCityRepository = playerCityRepository;
        this.scoutReportRepository = scoutReportRepository;
        this.technologyRepository = technologyRepository;
        this.playerRepository = playerRepository;
        this.officerRepository = officerRepository;
        this.equipmentService = equipmentService;
        this.buildingRepository = buildingRepository;
        this.fortificationRepository = fortificationRepository;
        this.battleService = battleService;
        this.battleSessionRepository = battleSessionRepository;
        this.pushService = pushService;
        this.playerItemRepository = playerItemRepository;
        this.questService = questService;
        this.npcCitySpawnService = npcCitySpawnService;
    }

    // ========================================================================
    // processMarches - 对应 JS core.js processMarches(now)
    // ========================================================================

    /** 在任何账号/行军写锁前锁定整个拦截关系，避免攻守双方 Tick 以相反顺序互相等待。 */
    @Transactional
    public void lockInterceptionParticipants(Long playerId) {
        lockInterceptionParticipants(playerId, null);
    }

    private void lockInterceptionParticipants(Long playerId, Long additionalPlayer) {
        Set<Long> participants = new TreeSet<>();
        participants.add(playerId);
        if (additionalPlayer != null) participants.add(additionalPlayer);
        int previousSize;
        do {
            previousSize = participants.size();
            for (Long id : new ArrayList<>(participants)) {
                for (March support : marchRepository.findByPlayerId(id)) if (support.getSupportOwnerId() != null) participants.add(support.getSupportOwnerId());
                for (PlayerCity c : playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(id)) {
                    guildSupport.stationed(c).forEach(m -> participants.add(m.getPlayerId()));
                    marchRepository.findByTargetKindAndTargetId("player", c.getId().toString()).forEach(m -> participants.add(m.getPlayerId()));
                }
            }
            for (March interceptor : marchRepository.findRelatedInterceptions(new ArrayList<>(participants))) {
                participants.add(interceptor.getPlayerId());
                marchRepository.findById(Long.valueOf(interceptor.getTargetId())).ifPresent(enemy -> participants.add(enemy.getPlayerId()));
            }
        } while (participants.size() != previousSize);
        participants.forEach(accounts::lockPlayer);
    }

    @org.springframework.beans.factory.annotation.Autowired private GarrisonFoodService garrisonFood;

    @Transactional
    public void processMarches(Long playerId, long now) {
        lockInterceptionParticipants(playerId);
        Player account = playerRepository.lockById(playerId).orElse(null);
        if (account == null || account.deletionDue(System.currentTimeMillis())) return;
        List<March> marches = marchRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        if (marches == null || marches.isEmpty()) return;
        marches.forEach(em::refresh);
        processInterceptionQueue(marches, now);
        marches = marchRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));

        for (int i = marches.size() - 1; i >= 0; i--) {
            March m = marches.get(i);
            if (m.isDisplaced()) { recoverDisplacedSupport(m, now); continue; }
            boolean gathering = Boolean.TRUE.equals(m.getGathering());
            boolean returning = Boolean.TRUE.equals(m.getReturning());
            long arriveAt = m.getArriveAt() != null ? m.getArriveAt() : 0L;
            long gatherEndAt = m.getGatherEndAt() != null ? m.getGatherEndAt() : 0L;

            if (Boolean.TRUE.equals(m.getStationed())) {
                PlayerCity destination = playerCityRepository.findById(Long.valueOf(m.getTargetId())).orElse(null);
                // 与开战共用城主锁，确保编队被冻结后不会被自动返程移走。
                if (destination != null && destination.getOwnerId() != null) accounts.lockPlayer(destination.getOwnerId());
                March garrison = guildSupport.refreshGarrison(m.getId()).orElse(null);
                if (garrison == null || !Boolean.TRUE.equals(garrison.getStationed())) continue;
                if (!guildSupport.canReceive(playerId, destination, garrison.getSupportOwnerId(), now)
                        && !garrisonFood.locked(garrison, battleSessionRepository.findActivePlayerCityBattles(garrison.getTargetId()))) {
                    startReturnMarch(garrison, now);
                    marchRepository.save(garrison);
                    pushService.pushMarchUpdate(playerId, marchEvent("supportReturning", garrison, Collections.emptyMap()));
                    if (garrison.getSupportOwnerId() != null) pushService.pushToPlayer(garrison.getSupportOwnerId(), "support_update", Map.of("marchId", garrison.getId()));
                }
                continue;
            }

            // 采满只停止数量增长，必须由玩家先收获、再下令回城；历史 auto 任务也不能自动推进。
            // 2. 仍在采集中
            if (!returning && (gathering || Boolean.TRUE.equals(m.getGatherStopped()))) continue;

            // 3. 尚未到达
            if (now < arriveAt) continue;

            String targetKind = m.getTargetKind();
            // 战斗会话由 TacticalBattleScheduler 独立推进。这里不能重复结算，
            // 否则玩家自动指挥请求与经济 Tick 会同时删除同一会话。
            if (!returning && m.getBattleId() != null) {
                continue;
            }
            if (!returning && !("player".equals(targetKind) && isOffensiveAction(m.getAction()))) {
                pushService.pushMarchUpdate(playerId, marchEvent("arrived", m, Collections.emptyMap()));
            }

            // 3a. 采集返程到达 -> 收取资源并归还部队
            if ("wild_gather".equals(targetKind) && returning && gatherEndAt > 0) {
                marchRepository.delete(m);
                String gatherRes = m.getGatherRes();
                int gatherAmount = m.getGatherAmount() != null ? m.getGatherAmount() : 0;
                if (gatherRes != null && gatherAmount > 0) {
                    addResources(playerId, Map.of(gatherRes, gatherAmount));
                }
                addResources(playerId, JsonUtil.parseIntMap(m.getCarryRes()));

                if (gatherAmount > 0 && gatherRes != null) {
                    equipmentProgress.event(playerId, "gather", "march-" + m.getId(), 1);
                    questService.onEvent(playerId, "GATHER_COMPLETE", gatherRes, 1);
                }
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));

                Map<String, Object> extra = new LinkedHashMap<>(resourceEvent(m.getGatherRes(), gatherAmount));
                pushService.pushMarchUpdate(playerId, marchEvent("returned", m, extra));
                continue;
            }

            // 3b. 采集部队首次到达 -> 开始采集
            if ("wild_gather".equals(targetKind) && !returning && !gathering) {
                WildTile aTile = lockWildTile(m.getTargetId());
                if (aTile == null || !Boolean.TRUE.equals(aTile.getOccupied()) || !playerId.equals(aTile.getOccupiedBy())) {
                    returnFailedGather(m, now);
                    continue;
                }
                if (m.getGatherOccupationVersion() == null) {
                    m.setGatherOccupationVersion(aTile.getOccupationVersion());
                    m.setGatherOwnerCitySlot(aTile.getOccupiedCitySlot());
                }
                if (!sameGatherOccupation(m, aTile)) {
                    returnFailedGather(m, now);
                    continue;
                }
                int totalRes = aTile.getTotalRes() != null ? aTile.getTotalRes() : 0;
                int mined = aTile.getMined() != null ? aTile.getMined() : 0;
                int remaining = totalRes - mined;
                if (remaining <= 0) {
                    returnFailedGather(m, now);
                    continue;
                }
                Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
                int load = calcArmyLoad(armyMap, JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot()));
                int gatherAmount = Math.min(remaining, load);
                m.setGatherAmount(gatherAmount);
                WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(aTile.getType());
                String gatherRes = wtDef != null ? wtDef.res() : null;
                if (gatherRes == null || gatherAmount <= 0) {
                    // 无资源野地不应出现采集任务，直接回城
                    returnFailedGather(m, now);
                    continue;
                }
                m.setGatherRes(gatherRes);
                m.setGatherResourceSettled(false);
                m.setGathering(true);
                m.setGatherStartAt(now);
                // 派遣和驻军采集共用100资源/秒基础速度；仅派遣队叠加随队军官后勤，倍率上限×3。
                // 派遣采集时长按本次负重计算，最少60秒；已开始任务沿用保存的结束时间。
                double gatherSpeedMul = 1.0;
                Officer gatherCmd = targets.getOfficerById(playerId, m.getCommanderId());
                if (gatherCmd != null) {
                    gatherSpeedMul += equipmentService.attributes(gatherCmd).logistics() / 100.0;
                }
                if (gatherSpeedMul > 3.0) gatherSpeedMul = 3.0;
                long gatherDuration = (long) Math.max(60L, Math.ceil(gatherAmount / (WILD_GATHER_BASE_SPEED * gatherSpeedMul))) * 1000;
                m.setGatherEndAt(now + gatherDuration);
                marchRepository.save(m);
                continue;
            }

            // 3b-2. 派遣部队进驻已占领野地
            if ("wild".equals(targetKind) && !returning && "station".equals(m.getAction())) {
                WildTile sTile = lockWildTile(m.getTargetId());
                if (sTile == null || !Boolean.TRUE.equals(sTile.getOccupied()) || !playerId.equals(sTile.getOccupiedBy())) {
                    startReturnMarch(m, now);
                    marchRepository.save(m);
                    continue;
                }
                Map<String, Integer> curGarrison = new LinkedHashMap<>(JsonUtil.parseIntMap(sTile.getGarrison()));
                Map<String, Integer> incomingArmy = JsonUtil.parseIntMap(m.getArmy());
                incomingArmy.forEach((u, count) -> curGarrison.merge(u, count, Integer::sum));
                List<GarrisonRoute> stationedRoutes = readGarrisonRoutes(sTile);
                stationedRoutes.add(new GarrisonRoute(playerId, m.getCitySlot(), m.getFromX(), m.getFromY(),
                        m.getDistance(), baseMarchDuration(m),
                        m.getRouteMode(), m.getRouteData(), incomingArmy, JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot())));
                sTile.setGarrisonRoutes(JsonUtil.toJson(stationedRoutes));
                sTile.setGarrison(JsonUtil.toJson(curGarrison));
                wildTileRepository.save(sTile);

                if (m.getCommanderId() != null) {
                    Officer officer = targets.getOfficerById(playerId, m.getCommanderId());
                    if (officer != null) {
                        officer.setRole("idle");
                        officerRepository.save(officer);
                    }
                }
                marchRepository.delete(m);
                pushService.pushMarchUpdate(playerId, marchEvent("stationed", m, Collections.emptyMap()));
                continue;
            }

            // 3c. 征服/掠夺野地 (排除侦查与进驻任务)
            if ("wild".equals(targetKind) && !returning && !"scout".equals(m.getAction()) && !"station".equals(m.getAction())) {
                WildTile cTile = targets.findWildTileById(m.getTargetId());
                if (cTile == null || Boolean.TRUE.equals(cTile.getOccupied())) {
                    marchRepository.delete(m);
                    returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                    continue;
                }
                if ("manual".equals(m.getBattleMode())) {
                    startTacticalBattle(playerId, m, cTile, now);
                    continue;
                }
                Map<String, Integer> garrison = JsonUtil.parseIntMap(cTile.getGarrison());
                Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
                Officer commander = m.getCommanderId() != null
                        ? targets.getOfficerById(playerId, m.getCommanderId())
                        : targets.getCommander(playerId);
                EquipmentService.Attributes attrs = equipmentService.attributes(commander);
                CommandBonus chief = commandBonus(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0));
                Map<String, Integer> combatTech = targets.getCombatTechMap(playerId);
                BattleResult result = battleService.startWorldDispatch(armyMap, garrison, Map.of(),
                        combatTech, Map.of(), targets.getCommanderSkills(commander), Map.of(),
                        attrs.military(), attrs.defense(), 0, 0,
                        m.getAction() != null ? m.getAction() : "conquer", Map.of(), 0, true,
                        battleActionPreferences.orders(playerId, false, armyMap), Map.of(), chief, CommandBonus.NONE, null, BattleScene.WILD);
                recordCommandBonuses(result, chief, CommandBonus.NONE);
                recordUnitEquipment(result, combatTech, Map.of());
                settleWildBattle(playerId, m, cTile, now, result, commander);
                continue;
            }

            // 3cc. 出城拦截抵达拦截点 -> 发生空域防空交火结算并掉头返程
            if ("intercept_scout".equals(m.getAction()) && !returning) {
                // 队列已按交火时间/ID统一结算；尚未交火的设伏部队继续停驻。
                continue;
            }

            // 3d. 返城到达 -> 归还部队和携带资源
            if (returning) {
                marchRepository.delete(m);
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                Map<String, Integer> carryRes = JsonUtil.parseIntMap(m.getCarryRes());
                if (!carryRes.isEmpty()) addResources(playerId, carryRes);
                if ("wild".equals(targetKind) && "gather".equals(m.getAction())
                        && m.getGatherRes() != null && carryRes.getOrDefault(m.getGatherRes(), 0) > 0) {
                    equipmentProgress.event(playerId, "gather", "march-" + m.getId(), 1);
                    questService.onEvent(playerId, "GATHER_COMPLETE", m.getGatherRes(), 1);
                }
                pushService.pushMarchUpdate(playerId, marchEvent("returned", m, carryRes));
                continue;
            }

            if ("transport".equals(m.getAction()) || "rebase".equals(m.getAction()) || "reinforce".equals(m.getAction())) {
                PlayerCity destination = playerCityRepository.findById(Long.valueOf(m.getTargetId())).orElse(null);
                // 先按目标城时间轴处理全部到期支援，再结清至当前时刻；派兵人 Tick 不决定收费先后。
                if (destination != null) garrisonFood.settle(destination, now);
                processSupportArrival(m.getId(), Math.min(now, arriveAt));
                continue;
            }

            // 3e. 攻击/侦查流寇、NPC城、玩家城 - 查找目标
            Object target = targets.findTargetById(targetKind, m.getTargetId());
            if (target == null && "player".equals(targetKind)) {
                // 注销目标失效后正常返程，不瞬移返兵，也不额外发放战利品。
                startReturnMarch(m, now);
                marchRepository.save(m);
                continue;
            }
            if (target == null || targets.isDefeated(target)) {
                marchRepository.delete(m);
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                Map<String, Integer> carryRes = JsonUtil.parseIntMap(m.getCarryRes());
                if (!carryRes.isEmpty()) {
                    addResources(playerId, carryRes);
                }
                continue;
            }

            // 出发后野地或城市可能换主；未经退出保护的旧行军只能返程，不能自动参战。
            if (isPlayerWarfare(playerId, target, m.getAction())
                    && NewbieProtection.until(playerRepository.findById(playerId).orElse(null), now) > now) {
                startReturnMarch(m, now);
                marchRepository.save(m);
                notifyIncomingChange(m, "cancelled");
                continue;
            }

            // 归降冻结是目标城市级别的硬边界；旧行军即使在冻结后抵达也必须返程，
            // 包括侦察等没有战斗结算的 action，不能通过抵达时机绕过只读状态。
            if (target instanceof PlayerCity frozenCity && frozenCity.isSurrenderFrozen(now)) {
                startReturnMarch(m, now);
                marchRepository.save(m);
                notifyIncomingChange(m, "cancelled");
                continue;
            }

            if (target instanceof PlayerCity defenderCity && defenderCity.getOwnerId() != null) {
                Player defender = accounts.lockPlayer(defenderCity.getOwnerId());
                if (defender == null || defender.deletionDue(System.currentTimeMillis())) {
                    startReturnMarch(m, now);
                    marchRepository.save(m);
                    continue;
                }
            }

            // 仅拦截尚未开战的来袭部队；已有战斗会话在前面继续由战斗调度器推进。
            if (returnIfShielded(m, target, now)) continue;

            // 发兵后军团关系可能变更，抵达时必须再次校验，避免旧行军绕过新的外交状态。
            if ("player".equals(targetKind) && target instanceof PlayerCity pc && targets.hasRealOwner(pc)
                    && isOffensiveAction(m.getAction()) && !canAttackPlayerCity(playerId, pc, now)) {
                cancelMarchBeforeBattle(m, playerId);
                continue;
            }

            String action = m.getAction() != null ? m.getAction() : "conquer";
            if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
                try (var ignored = cityScope.enter(pc)) { resolveTarget(playerId, m, target, now, action); }
            } else resolveTarget(playerId, m, target, now, action);
            if ("scout".equals(action) || m.getBattleId() != null) notifyIncomingChange(m, "resolved");
        }
        for (Officer officer : officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), "march")) {
            if (!marchRepository.existsByPlayerIdAndCommanderId(playerId, officer.getId())) { officer.setRole("idle"); officerRepository.save(officer); }
        }
    }

    /** 两种野地战斗方式共用占领、掠夺和战报结算。 */
    private void settleWildBattle(Long playerId, March m, WildTile cTile, long now,
                                  BattleResult result, Officer commander) {
        recordBattleWounded(playerId, m, null, null, result.getInitialAttacker(),
                result.getSurvivorAttacker(), commander, now, result, "攻方");
        settleBattleAwards(playerId, m, true, result, m.getAction());
        if (commander != null && result.getExpGained() > 0) {
            long curExp = commander.getExp() != null ? commander.getExp() : 0L;
            commander.setExp(curExp + result.getExpGained());
            officerRepository.save(commander);
        }
        m.setArmy(JsonUtil.toJson(result.getSurvivorAttacker()));
        Map<String, Integer> survivorGarrison = result.getSurvivorDefender();
        if (survivorGarrison == null) survivorGarrison = Collections.emptyMap();
        boolean wildConquered = false;
        if (result.isWin()) {
            equipmentProgress.event(playerId, "wild", "march-" + m.getId(), 1);
            String wildAction = m.getAction() != null ? m.getAction() : "conquer";
            if (BattleService.isHarassmentAction(wildAction)) {
                // 短回合袭击仅更新真实战损，不消耗矿藏、不占领，也不派生采集奖励。
                cTile.setGarrison(JsonUtil.toJson(survivorGarrison));
                wildTileRepository.save(cTile);
            } else if ("plunder".equals(wildAction)) {
                // 掠夺: 不占领野地, 更新剩余驻军, 根据幸存部队负重掠夺资源
                cTile.setGarrison(JsonUtil.toJson(survivorGarrison));
                WildTypeDef wtDefPl = WildTypeDef.WILD_TYPES.get(cTile.getType());
                String resKey = wtDefPl != null ? wtDefPl.res() : null;
                if (resKey != null) {
                    int totalRes = cTile.getTotalRes() != null ? cTile.getTotalRes() : 0;
                    int mined = cTile.getMined() != null ? cTile.getMined() : 0;
                    int remaining = totalRes - mined;
                    if (remaining > 0) {
                        int load = calcArmyLoad(result.getSurvivorAttacker(), JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot()));
                        int plunderAmount = Math.min(remaining, load);
                        if (plunderAmount > 0) {
                            Map<String, Integer> carryRes = new LinkedHashMap<>(JsonUtil.parseIntMap(m.getCarryRes()));
                            carryRes.merge(resKey, plunderAmount, Integer::sum);
                            m.setCarryRes(JsonUtil.toJson(carryRes));
                            int newTotal = Math.max(0, remaining - plunderAmount);
                            cTile.setTotalRes(newTotal);
                            cTile.setMined(0);
                            if (newTotal <= 0 && cTile.getDepletedAt() == null)
                                cTile.setDepletedAt(now);
                            result.setPlunderedResources(Map.of(resKey, plunderAmount));
                            try { questService.onEvent(playerId, "WILD_PLUNDER", null, 1); } catch (Exception ignored) {}
                        }
                    }
                }
                wildTileRepository.save(cTile);
            } else {
                // 名额按行军出发城市结算，玩家战斗期间切城不会改变归属或借用分城名额。
                WildOccupationService.Claim claim = wildOccupation.claim(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0), cTile);
                if (claim.claimed()) {
                    cTile.setGarrison("{}");
                    clearStationedGather(cTile);
                    cTile.setGarrisonRoutes(null);
                    wildTileRepository.save(cTile);
                    wildConquered = true;
                    try {
                        questService.onEvent(playerId, "WILD_CLAIM", null, 1);
                        questService.onEvent(playerId, "WILD_CONQUER", null, 1);
                    } catch (Exception ignored) {}
                } else {
                    result.setWildOccupationReason(claim.reason());
                    result.setReport(Objects.requireNonNullElse(result.getReport(), "") + "\n" + claim.reason() + "，幸存部队返城。\n");
                    if (!Boolean.TRUE.equals(cTile.getOccupied())) {
                        cTile.setGarrison(JsonUtil.toJson(survivorGarrison));
                        wildTileRepository.save(cTile);
                    }
                }
            }
        } else {
            // 未全歼守军: 更新野地剩余驻军
            cTile.setGarrison(JsonUtil.toJson(survivorGarrison));
            wildTileRepository.save(cTile);
        }
        pushBattleReport(playerId, result, m, commander, null, wildConquered,
                m.getAction() != null ? m.getAction() : "conquer");
        m.setBattleWon(result.isWin());
        m.setBattleId(null);
        // 征服成功且选择占领时，将幸存部队转成野地驻军；默认策略仍按原逻辑返城。
        if (wildConquered && "occupy".equals(m.getConquerMode())
                && !JsonUtil.parseIntMap(m.getArmy()).isEmpty()) {
            stationConqueredArmy(playerId, m, cTile);
            return;
        }
        startReturnMarch(m, now);
        marchRepository.save(m);
    }

    /** 将征服胜利后的幸存部队写入野地驻军，并保存原始路线供后续手动撤军。 */
    private void stationConqueredArmy(Long playerId, March march, WildTile tile) {
        Map<String, Integer> army = new LinkedHashMap<>(JsonUtil.parseIntMap(march.getArmy()));
        tile.setGarrison(JsonUtil.toJson(army));
        tile.setGarrisonRoutes(JsonUtil.toJson(List.of(new GarrisonRoute(playerId, march.getCitySlot(),
                march.getFromX(), march.getFromY(), march.getDistance(),
                baseMarchDuration(march), march.getRouteMode(),
                march.getRouteData(), army, JsonUtil.parseIntMap(march.getUnitEquipmentSnapshot())))));
        wildTileRepository.save(tile);
        if (march.getCommanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, march.getCommanderId());
            if (officer != null) {
                officer.setRole("idle");
                officerRepository.save(officer);
            }
        }
        marchRepository.delete(march);
        pushService.pushMarchUpdate(playerId, marchEvent("stationed", march, Collections.emptyMap()));
    }

    /** 玩家城固定进入战术会话；非玩家攻击按出征时保存的方式分流。 */
    private void resolveTarget(Long playerId, March march, Object target, long now, String action) {
        if ("scout".equals(action)) {
            resolveScout(playerId, march, target, now);
        } else if (!"player".equals(march.getTargetKind()) && "auto".equals(march.getBattleMode())) {
            resolveAttack(playerId, march, target, now);
        } else {
            startTacticalBattle(playerId, march, target, now);
        }
    }

    private void resolveAttack(Long playerId, March m, Object target, long now) {
        Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());

        // 攻方信息
        Map<String, Integer> attackerTech = UnitEquipmentEffects.forArmy(targets.getCombatTechMap(playerId), armyMap);
        Officer commander = m.getCommanderId() != null
                ? targets.getOfficerById(playerId, m.getCommanderId())
                : targets.getCommander(playerId);
        int attackerCommanderMil = 0;
        int attackerCommanderDef = 0;
        if (commander != null) {
            EquipmentService.Attributes aAttrs = equipmentService.attributes(commander);
            attackerCommanderMil = aAttrs.military();
            attackerCommanderDef = aAttrs.defense();
        }
        Map<String, Integer> attackerSkills = targets.getCommanderSkills(commander);
        Officer defenderCommander = target instanceof PlayerCity pc && pc.getOwnerId() != null
                ? targets.getCityCommander(pc) : null;
        if (defenderCommander == null) {
            if (target instanceof Bandit bandit) {
                defenderCommander = JapaneseOfficers.buildOfficer(bandit.getCommanderName(), bandit.getLevel() != null ? bandit.getLevel() : 1);
            } else if (target instanceof NpcCity city) {
                defenderCommander = JapaneseOfficers.buildOfficer(city.getCommanderName(), city.getLevel() != null ? city.getLevel() : 1);
            }
        }

        // 守方信息
        GuildSupportService.Defense defense = defenseForce(target);
        Map<String, Integer> defenderArmy = defense.army();
        Map<String, Integer> defenderForts = targets.getTargetForts(target);
        Map<String, Integer> defenderResources = targets.getTargetResources(target);
        String action = m.getAction() != null ? m.getAction() : "conquer";
        if (action.startsWith("tactical_")) {
            action = action.substring("tactical_".length());
        }

        // 陆地流寇与海洋 NPC 使用相同的资源奖励档位。
        if (target instanceof Bandit bandit) {
            int level = bandit.getLevel() != null ? bandit.getLevel() : 1;
            if (level >= 1 && level <= WorldConfig.BANDIT_LEVELS.size()) {
                defenderResources = banditBattleResources(bandit);
            }
        } else if (target instanceof NpcCity) {
            // 兼容旧存档：30 级 NPC 城市曾预存固定钻石，不能带入新概率掉落结算。
            defenderResources = new LinkedHashMap<>(defenderResources);
            defenderResources.remove("diamond");
        }

        boolean isPlayerBattle = (target instanceof PlayerCity);
        Long defenderPlayerId = (target instanceof PlayerCity pc) ? pc.getOwnerId() : null;
        // 装备只取已冻结的来源编队，不能再混入换装后读取的新物品或属性。
        Map<String, Integer> defenderTech = new LinkedHashMap<>(defenderPlayerId != null ? targets.getTechMap(defenderPlayerId) : Map.of());
        defenderTech.putAll(defense.equipment());
        defenderTech = UnitEquipmentEffects.forArmy(defenderTech, defenderArmy);
        Map<String, Integer> defenderSkills = targets.getCommanderSkills(defenderCommander);
        int defenderCommanderMil = 0;
        int defenderCommanderDef = 0;
        if (defenderCommander != null) {
            EquipmentService.Attributes dAttrs = equipmentService.attributes(defenderCommander);
            defenderCommanderMil = dAttrs.military();
            defenderCommanderDef = dAttrs.defense();
        }
        long defenderWarehouseLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "depot") : 0;

        CommandBonus attackerChief = commandBonus(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0));
        CommandBonus defenderChief = target instanceof PlayerCity pc && targets.hasRealOwner(pc)
                ? commandBonus(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0)) : CommandBonus.NONE;
        BattleResult result = battleService.startWorldDispatch(
                armyMap, defenderArmy, defenderForts,
                attackerTech, defenderTech,
                attackerSkills, defenderSkills,
                attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef,
                action, defenderResources, defenderWarehouseLevel,
                isPlayerBattle,
                battleActionPreferences.orders(playerId, false, armyMap), Collections.emptyMap(), attackerChief, defenderChief,
                conquestWall(target, action, defenderArmy, defenderForts), battleScene(target));
        recordCommandBonuses(result, attackerChief, defenderChief);
        recordUnitEquipment(result, attackerTech, defenderTech);
        recordEquipmentSources(result, defense.sources());
        settleTacticalBattle(playerId, m, target, now, result, commander, defenderCommander, action, JsonUtil.toJson(defense.support()));
    }

    /** 模拟玩家城仍属于NPC；自动与逐回合入口必须采用相同场景。 */
    private BattleScene battleScene(Object target) {
        if (target instanceof WildTile) return BattleScene.WILD;
        return target instanceof PlayerCity city && targets.hasRealOwner(city) ? BattleScene.PLAYER : BattleScene.NPC;
    }

    /**
     * 将到达目标的攻击行军冻结为战斗会话。同一玩家城按到达时间逐场开战，
     * 不同城市可同时交战；城主账号行锁使各出征方的建会话操作串行化。
     */
    private void startTacticalBattle(Long playerId, March march, Object target, long now) {
        try (var attackerScope = cityScope.enter(playerId, Objects.requireNonNullElse(march.getCitySlot(), 0))) {
            if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
                try (var defenderScope = cityScope.enter(city)) { createTacticalBattle(playerId, march, target, now); }
            } else createTacticalBattle(playerId, march, target, now);
        }
    }

    /** 攻守城市作用域已固定后，读取本次参战军官、城防和资源并冻结到会话。 */
    private void createTacticalBattle(Long playerId, March march, Object target, long now) {
        if (march.getBattleId() != null) return;
        // 手动打开战场同样不得绕过退出确认；正常 PvP 出征已在发起事务中永久退出。
        if (isPlayerWarfare(playerId, target, march.getAction())
                && NewbieProtection.until(playerRepository.findById(playerId).orElse(null), now) > now) return;
        if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
            accounts.lockPlayer(city.getOwnerId());
            if (returnIfShielded(march, target, now)) return;
            // 手动打开战场也会建会话，必须重验城权与战争资格，防止排队部队攻击刚接管的己方城市。
            if (isOffensiveAction(march.getAction())
                    && !canAttackPlayerCity(playerId, city, now)) {
                cancelMarchBeforeBattle(march, playerId);
                return;
            }
            if (!battleSessionRepository.findActivePlayerCityBattles(march.getTargetId()).isEmpty()) return;
            List<Long> waiting = marchRepository.findWaitingPlayerCityAttackIds(march.getTargetId(), now,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            if (!waiting.isEmpty() && !march.getId().equals(waiting.get(0))) return;
        }
        if (target instanceof PlayerCity city) { garrisonFood.settle(city, now); garrisonFood.enforce(city, now); }
        Map<String, Integer> attackerArmy = new LinkedHashMap<>(JsonUtil.parseIntMap(march.getArmy()));
        GuildSupportService.Defense defense = defenseForce(target);
        Map<String, Integer> defenderArmy = new LinkedHashMap<>(defense.army());
        targets.getTargetForts(target).forEach((unit, count) -> {
            if (count != null && count > 0) defenderArmy.merge(unit, count, Integer::sum);
        });
        Officer attackerCommander = march.getCommanderId() != null
                ? targets.getOfficerById(playerId, march.getCommanderId()) : targets.getCommander(playerId);
        Long defenderPlayerId = target instanceof PlayerCity city ? city.getOwnerId() : null;
        Officer defenderCommander = target instanceof PlayerCity city && defenderPlayerId != null
                ? targets.getCityCommander(city) : null;
        if (defenderCommander == null) {
            if (target instanceof Bandit bandit) {
                defenderCommander = JapaneseOfficers.buildOfficer(bandit.getCommanderName(), bandit.getLevel() != null ? bandit.getLevel() : 1);
            } else if (target instanceof NpcCity city) {
                defenderCommander = JapaneseOfficers.buildOfficer(city.getCommanderName(), city.getLevel() != null ? city.getLevel() : 1);
            }
        }
        EquipmentService.Attributes attackerAttrs = attackerCommander != null
                ? equipmentService.attributes(attackerCommander) : new EquipmentService.Attributes(0, 0, 0, 0, List.of(), List.of());
        EquipmentService.Attributes defenderAttrs = defenderCommander != null
                ? equipmentService.attributes(defenderCommander) : new EquipmentService.Attributes(0, 0, 0, 0, List.of(), List.of());
        int defenderMil = defenderAttrs.military();
        int defenderDef = defenderAttrs.defense();
        Map<String, Integer> attackerTech = UnitEquipmentEffects.forArmy(targets.getCombatTechMap(playerId), attackerArmy);
        Map<String, Integer> defenderTech = new LinkedHashMap<>(defenderPlayerId != null ? targets.getTechMap(defenderPlayerId) : Map.of());
        defenderTech.putAll(defense.equipment());
        defenderTech = UnitEquipmentEffects.forArmy(defenderTech, defenderArmy);
        Map<String, Integer> attackerSkills = targets.getCommanderSkills(attackerCommander);
        Map<String, Integer> defenderSkills = targets.getCommanderSkills(defenderCommander);
        long warehouseLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "depot") : 0;
        Map<String, Integer> defenderResources = target instanceof WildTile
                ? new LinkedHashMap<>() : new LinkedHashMap<>(targets.getTargetResources(target));
        if (target instanceof Bandit bandit) {
            int level = bandit.getLevel() != null ? bandit.getLevel() : 1;
            if (level >= 1 && level <= WorldConfig.BANDIT_LEVELS.size()) {
                defenderResources = banditBattleResources(bandit);
            }
        } else if (target instanceof NpcCity) {
            defenderResources.remove("diamond");
        }

        int attackerMil = attackerAttrs.military();
        int attackerDef = attackerAttrs.defense();
        CommandBonus attackerChief = commandBonus(playerId, Objects.requireNonNullElse(march.getCitySlot(), 0));
        CommandBonus defenderChief = target instanceof PlayerCity pc && targets.hasRealOwner(pc)
                ? commandBonus(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0)) : CommandBonus.NONE;
        ConquestWallState wall = conquestWall(target, march.getAction(), defenderArmy, Map.of());
        int initialDistance = battleService.calcInitialDistance(attackerArmy, defenderArmy,
                new BattleService.TechCtx(attackerTech, attackerSkills, attackerMil, attackerDef, attackerChief),
                new BattleService.TechCtx(defenderTech, defenderSkills, defenderMil, defenderDef, defenderChief), wall, battleScene(target));
        BattleSession session = new BattleSession();
        saveWall(session, wall);
        session.setBattleRulesVersion(BattleRules.VERSION);
        session.setDefenderCommanderId(target instanceof PlayerCity ? (defenderCommander != null ? defenderCommander.getId() : 0L) : null);
        session.setPlayerId(playerId);
        session.setMarchId(march.getId());
        session.setTargetKind(march.getTargetKind());
        session.setTargetId(march.getTargetId());
        session.setTargetName(march.getTargetName());
        session.setAction(march.getAction() != null ? march.getAction() : "conquer");
        session.setRoundNo(0);
        session.setFirstCombatRound(0);
        session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
        session.setInitialDistance(initialDistance);
        session.setAttackerArmy(JsonUtil.toJson(attackerArmy));
        session.setDefenderArmy(JsonUtil.toJson(defenderArmy));
        session.setInitialAttacker(JsonUtil.toJson(attackerArmy));
        session.setInitialDefender(JsonUtil.toJson(defenderArmy));
        if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
            session.setJointDefense(JsonUtil.toJson(guildSupport.freeze(defense, city, defenderCommander, march, attackerCommander,
                    attackerArmy, UnitEquipmentEffects.only(attackerTech), defenderArmy)));
            session.setBattleRulesVersion(JointDefenseState.VERSION);
        }
        session.setDefenderSupport(JsonUtil.toJson(defense.support()));
        session.setDefenderEquipmentSources(JsonUtil.toJson(defense.sources()));
        session.setAttackerPositions(JsonUtil.toJson(initialPositions(attackerArmy, 0)));
        session.setDefenderPositions(JsonUtil.toJson(initialPositions(defenderArmy, initialDistance)));
        session.setAttackerTech(JsonUtil.toJson(attackerTech));
        session.setDefenderTech(JsonUtil.toJson(defenderTech));
        session.setAttackerSkills(JsonUtil.toJson(attackerSkills));
        session.setDefenderSkills(JsonUtil.toJson(defenderSkills));
        session.setDefenderResources(JsonUtil.toJson(defenderResources));
        session.setBattleLog("规则版本：" + session.getBattleRulesVersion() + "\n战场初始距离: " + initialDistance + "\n");
        session.setAttackerCommandBonus(JsonUtil.toJson(attackerChief.toMap()));
        session.setDefenderCommandBonus(JsonUtil.toJson(defenderChief.toMap()));
        session.setAttackerCommanderMil(attackerMil);
        session.setAttackerCommanderDef(attackerDef);
        session.setDefenderCommanderMil(defenderMil);
        session.setDefenderCommanderDef(defenderDef);
        session.setDefenderWarehouseLevel(warehouseLevel);
        session = battleSessionRepository.save(session);
        march.setBattleId(session.getId());
        marchRepository.save(march);
        pushService.pushMarchUpdate(playerId, marchEvent("battleReady", march, Map.of("battleId", session.getId())));
    }

    /**
     * 返回战术战场快照；进攻方和目标城市的真实所有者都可进入同一会话指挥各自部队。
     *
     * @param playerId 请求战场快照的玩家 ID
     * @param marchId 战斗关联的进攻行军 ID
     * @return 以请求方视角排列“我军/敌军”的战场快照
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> getTacticalBattle(Long playerId, Long marchId) {
        Map<String, Object> finished = battleOutcomes.find(playerId, marchId);
        if (finished != null) return finished;
        lockInterceptionParticipants(playerId);
        TacticalBattleAccess access;
        try { access = tacticalBattleAccess(playerId, marchId); }
        catch (IllegalArgumentException missing) {
            Map<String, Object> outcome = battleOutcomes.find(playerId, marchId);
            if (outcome != null) return outcome;
            throw missing;
        }
        March march = access.march();
        processInterceptionQueue(List.of(march), GameClock.now());
        if (!marchRepository.existsById(marchId) || Boolean.TRUE.equals(march.getReturning())) {
            Map<String, Object> outcome = battleOutcomes.find(playerId, marchId);
            if (outcome != null) return outcome;
            return Map.of("marchId", marchId, "cancelled", true, "reason", "intercepted", "message", "部队已在城外交火，请查看拦截战报");
        }
        if (march.getBattleId() == null) {
            if (!"player".equals(march.getTargetKind())
                    && ("auto".equals(march.getBattleMode())
                    || (march.getBattleMode() == null && "wild".equals(march.getTargetKind())))) {
                throw new IllegalArgumentException("该行军选择了自动结算，请查看战报");
            }
            long now = GameClock.now();
            if (Boolean.TRUE.equals(march.getReturning()) || now < Objects.requireNonNullElse(march.getArriveAt(), 0L)
                    || "scout".equals(march.getAction())) {
                throw new IllegalArgumentException("部队尚未到达战场");
            }
            Object target = targets.findTargetById(march.getTargetKind(), march.getTargetId());
        if (target == null || targets.isDefeated(target)
                || (target instanceof WildTile wt && Boolean.TRUE.equals(wt.getOccupied()))) throw new IllegalArgumentException("战斗目标已失效");
            // 兼容旧行军：军情卡在到达后首次点击时创建会话，无需等待下一次 Tick。
            startTacticalBattle(march.getPlayerId(), march, target, now);
            if (!marchRepository.existsById(marchId)) {
                return Map.of("marchId", marchId, "cancelled", true, "reason", "war_eligibility",
                        "message", "当前已不符合城市进攻条件，部队与随军物资已归还");
            }
            // 返回正常响应才能提交返程和邮件；抛异常会回滚这次护盾拦截。
            if (Boolean.TRUE.equals(march.getReturning())) {
                return Map.of("marchId", march.getId(), "cancelled", true, "reason", "shield",
                        "message", "护盾期间不可发生交火，来袭部队已原路返程");
            }
            if (march.getBattleId() == null) {
                throw new IllegalArgumentException("目标正在交战，部队已到达并等待前一场战斗结束");
            }
        }
        BattleSession session = battleSessionRepository.findById(march.getBattleId()).orElse(null);
        if (session == null) {
            Map<String, Object> outcome = battleOutcomes.find(playerId, marchId);
            if (outcome != null) return outcome;
            throw new IllegalArgumentException("战斗会话不存在或已结束");
        }
        if (session.getRoundDeadlineAt() == null || session.getRoundDeadlineAt() <= 0) {
            // 兼容已在战场中的历史会话，打开指挥界面后也必须展示完整倒计时。
            session.setRoundDeadlineAt(GameClock.now() + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
        }
        return battleView(session, false, access.defending());
    }

    /**
     * 验证并保存当前指挥方指令，结算恰好一回合；未填写的单位沿用本场已有指令或账号预设。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> executeTacticalRound(Long playerId, Long marchId,
                                                     com.wargame.model.dto.GameDtos.BattleCommandRequest request) {
        Map<String, Object> finished = battleOutcomes.find(playerId, marchId);
        if (finished != null) return finished;
        TacticalBattleAccess access;
        try { access = tacticalBattleAccess(playerId, marchId); }
        catch (IllegalArgumentException missing) {
            Map<String, Object> outcome = battleOutcomes.find(playerId, marchId);
            if (outcome != null) return outcome;
            throw missing;
        }
        March march = access.march();
        lockInterceptionParticipants(march.getPlayerId(), playerId);
        // 野地占领结算与行军Tick统一先锁账号、再锁会话，避免两条路径相互等待。
        boolean wildBattle = "wild".equals(march.getTargetKind());
        if (wildBattle) {
            accounts.lockPlayer(march.getPlayerId());
            em.refresh(march);
        }
        if (march.getBattleId() == null) {
            Map<String, Object> outcome = battleOutcomes.find(playerId, marchId);
            if (outcome != null) return outcome;
            throw new IllegalArgumentException("当前行军没有可指挥的战斗");
        }
        BattleSession session = lockTacticalSession(march.getBattleId(), wildBattle).orElse(null);
        if (session == null) {
            Map<String, Object> outcome = battleOutcomes.find(playerId, marchId);
            if (outcome != null) return outcome;
            throw new IllegalArgumentException("战斗会话不存在或已结束");
        }
        Integer expectedRound = request != null ? request.round() : null;
        // 当后台自动结算已先完成时，过期请求只能读取最新状态，不能再推进下一回合。
        if (expectedRound != null && !expectedRound.equals(session.getRoundNo())) {
            return battleView(session, false, access.defending());
        }
        long now = GameClock.now();
        if (session.getRoundDeadlineAt() == null || session.getRoundDeadlineAt() <= 0) {
            session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
            return battleView(session, false, access.defending());
        }
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(session.getAttackerArmy());
        Map<String, Integer> defenderArmy = JsonUtil.parseIntMap(session.getDefenderArmy());
        // 客户端倒计时到期才提交本回合指令；只要调度器尚未结算当前回合，就接受这次提交。
        // 已结算回合由上面的 expectedRound 检查拦截，超时回合沿用本场指令，未下令时使用账号预设。
        // 防守玩家只能修改守城部队指令，进攻方沿用自身指令；反之亦然。
        Map<String, BattleService.UnitOrder> orders = access.defending()
                ? parseBattleOrders(request, defenderArmy, attackerArmy)
                : parseBattleOrders(request, attackerArmy, battleTargets(session, defenderArmy, false));
        try {
            questService.onEvent(playerId, "BATTLE_COMMAND", null, 1);
        } catch (Exception ignored) {}
        try (var ignored = cityScope.enter(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0))) {
            return resolveTacticalRound(march.getPlayerId(), march, session,
                    access.defending() ? Collections.emptyMap() : orders,
                    access.defending() ? orders : Collections.emptyMap(), now, access.defending());
        }
    }

    /**
     * 在玩家经济 Tick 暂停或延迟时，独立推进已到期的战术回合。
     * 手动指挥和行军 Tick 同样锁定战斗会话，因此同一回合只会结算一次。
     *
     * @param battleSessionId 到期战斗会话 ID
     * @param now 当前毫秒时间戳
     */
    @Transactional
    public void processTimedOutTacticalBattle(Long battleSessionId, long now) {
        BattleSession pending = battleSessionRepository.findById(battleSessionId).orElse(null);
        if (pending == null) return;
        lockInterceptionParticipants(pending.getPlayerId());
        boolean wildBattle = "wild".equals(pending.getTargetKind());
        if (wildBattle) accounts.lockPlayer(pending.getPlayerId());
        BattleSession session = lockTacticalSession(battleSessionId, wildBattle).orElse(null);
        if (session == null) return;
        March march = marchRepository.findById(session.getMarchId()).orElse(null);
        if (march == null || !battleSessionId.equals(march.getBattleId()) || Boolean.TRUE.equals(march.getReturning())) return;

        Long deadline = session.getRoundDeadlineAt();
        if (deadline == null || deadline <= 0) {
            session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
            return;
        }
        if (deadline > now) return;

        try (var ignored = cityScope.enter(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0))) {
            resolveTacticalRound(march.getPlayerId(), march, session,
                    Collections.emptyMap(), Collections.emptyMap(), deadline, false);
        }
    }

    /** 野地路径已取得账号锁并flush，移除等待锁前的旧会话缓存，再锁定读取最新回合。 */
    private Optional<BattleSession> lockTacticalSession(Long battleSessionId, boolean wildBattle) {
        if (wildBattle) {
            BattleSession cached = em.find(BattleSession.class, battleSessionId);
            // 直接对旧版本升级悲观锁会先触发版本冲突，必须先移除缓存，不能等查完再refresh。
            if (cached != null) em.detach(cached);
        }
        return battleSessionRepository.findForRoundResolution(battleSessionId);
    }

    /**
     * 结算一个战术回合；新指令覆盖本场已有指令并持续生效，未下令的兵种使用账号攻守预设。
     */
    private Map<String, Object> resolveTacticalRound(Long attackerPlayerId, March march, BattleSession session,
                                                      Map<String, BattleService.UnitOrder> attackerOrders,
                                                      Map<String, BattleService.UnitOrder> defenderOrders,
                                                      long now, boolean defendingViewer) {
        // 已到期手动请求与超时任务共用截止时刻；不能用请求到达时间抹平离线回合。
        long at = session.getJointDefense() != null && session.getRoundDeadlineAt() != null ? Math.min(now, session.getRoundDeadlineAt()) : now;
        return resolveTacticalRoundAt(attackerPlayerId, march, session, attackerOrders, defenderOrders, at, defendingViewer);
    }

    private Map<String, Object> resolveTacticalRoundAt(Long attackerPlayerId, March march, BattleSession session,
                                                      Map<String, BattleService.UnitOrder> attackerOrders,
                                                      Map<String, BattleService.UnitOrder> defenderOrders,
                                                      long now, boolean defendingViewer) {
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(session.getAttackerArmy());
        Map<String, Integer> defenderArmy = JsonUtil.parseIntMap(session.getDefenderArmy());
        Object target = targets.findTargetById(session.getTargetKind(), session.getTargetId());
        Long defenderPlayerId = target instanceof PlayerCity city && targets.hasRealOwner(city)
                ? city.getOwnerId() : null;
        Map<String, BattleService.UnitOrder> savedAttackerOrders = persistentBattleOrders(
                session.getAttackerOrders(), attackerArmy, battleTargets(session, defenderArmy, false));
        Map<String, BattleService.UnitOrder> savedDefenderOrders = persistentBattleOrders(
                session.getDefenderOrders(), defenderArmy, attackerArmy);
        savedAttackerOrders.putAll(attackerOrders);
        savedDefenderOrders.putAll(defenderOrders);
        Map<String, BattleService.UnitOrder> resolvedAttackerOrders = new LinkedHashMap<>(
                battleActionPreferences.orders(attackerPlayerId, false, attackerArmy));
        Map<String, BattleService.UnitOrder> resolvedDefenderOrders = new LinkedHashMap<>(
                battleActionPreferences.orders(defenderPlayerId, true, defenderArmy));
        resolvedAttackerOrders.putAll(savedAttackerOrders);
        resolvedDefenderOrders.putAll(savedDefenderOrders);
        int nextRound = session.getRoundNo() + 1;
        // 老会话从完整战报恢复起点，新会话直接沿用持久化值，确保刷新和离线结算共用同一周期。
        int firstCombatRound = session.getFirstCombatRound() != null ? session.getFirstCombatRound()
                : battleService.firstCombatRoundFromLog(session.getBattleLog());
        if (target instanceof PlayerCity city) garrisonFood.beforeBattle(city, now, session.getId());
        JointDefenseState joint = JointDefenseState.read(session.getJointDefense());
        BattleRoundState round = joint != null ? battleService.resolveJointRound(attackerArmy, defenderArmy,
                JsonUtil.parseIntMap(session.getAttackerPositions()), JsonUtil.parseIntMap(session.getDefenderPositions()), session.getInitialDistance(),
                new BattleService.TechCtx(JsonUtil.parseIntMap(session.getAttackerTech()), JsonUtil.parseIntMap(session.getAttackerSkills()),
                        session.getAttackerCommanderMil(), session.getAttackerCommanderDef(), CommandBonus.fromJson(session.getAttackerCommandBonus())),
                new BattleService.TechCtx(JsonUtil.parseIntMap(session.getDefenderTech()), JsonUtil.parseIntMap(session.getDefenderSkills()),
                        session.getDefenderCommanderMil(), session.getDefenderCommanderDef(), CommandBonus.fromJson(session.getDefenderCommandBonus())),
                nextRound, resolvedAttackerOrders, resolvedDefenderOrders, session.getAction(), restoreWall(session), joint)
                : battleService.resolveWorldRound(attackerArmy, defenderArmy,
                JsonUtil.parseIntMap(session.getAttackerPositions()), JsonUtil.parseIntMap(session.getDefenderPositions()),
                session.getInitialDistance(), JsonUtil.parseIntMap(session.getAttackerTech()), JsonUtil.parseIntMap(session.getDefenderTech()),
                JsonUtil.parseIntMap(session.getAttackerSkills()), JsonUtil.parseIntMap(session.getDefenderSkills()),
                session.getAttackerCommanderMil(), session.getAttackerCommanderDef(),
                session.getDefenderCommanderMil(), session.getDefenderCommanderDef(),
                nextRound, firstCombatRound, resolvedAttackerOrders, resolvedDefenderOrders,
                CommandBonus.fromJson(session.getAttackerCommandBonus()), CommandBonus.fromJson(session.getDefenderCommandBonus()),
                session.getAction(), restoreWall(session));
        if (joint != null) session.setJointDefense(JsonUtil.toJson(joint));
        String battleLog = session.getBattleLog() + round.log();

        // 所有推进路径持久化同一耐久与阶段；终局响应同样使用本回合状态，不能展示上一回合满血。
        saveWall(session, round.wall());
        session.setRoundNo(round.round());
        session.setFirstCombatRound(round.firstCombatRound());

        session.setAttackerArmy(JsonUtil.toJson(round.attackerArmy()));
        session.setDefenderArmy(JsonUtil.toJson(round.defenderArmy()));
        if (!round.finished()) {
            session.setAttackerArmy(JsonUtil.toJson(round.attackerArmy()));
            session.setDefenderArmy(JsonUtil.toJson(round.defenderArmy()));
            session.setAttackerPositions(JsonUtil.toJson(round.attackerPositions()));
            session.setDefenderPositions(JsonUtil.toJson(round.defenderPositions()));
            session.setAttackerOrders(JsonUtil.toJson(savedAttackerOrders));
            session.setDefenderOrders(JsonUtil.toJson(savedDefenderOrders));
            session.setBattleLog(battleLog);
            session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
            return battleView(session, false, defendingViewer);
        }

        if (target == null) throw new IllegalStateException("战斗目标已不存在，无法结算");
        Map<String, Integer> defenderResources = new LinkedHashMap<>(JsonUtil.parseIntMap(session.getDefenderResources()));
        // 旧战斗会话可能缓存固定 20 钻石；统一改由战斗胜利后的概率抽取发放。
        if (target instanceof Bandit || target instanceof NpcCity) defenderResources.remove("diamond");
        BattleResult result = battleService.finishWorldBattle(round.attackerWin(), round.attackerArmy(), round.defenderArmy(),
                JsonUtil.parseIntMap(session.getInitialAttacker()), JsonUtil.parseIntMap(session.getInitialDefender()),
                JsonUtil.parseIntMap(session.getAttackerTech()), JsonUtil.parseIntMap(session.getDefenderTech()), session.getAction(), defenderResources,
                session.getDefenderWarehouseLevel(), battleLog);
        if (joint != null) {
            result.setFormationContributions(joint.contributions(round.attackerArmy(), round.defenderArmy()));
            result.setFormationEvents(joint.getEvents());
            result.setCommanders(joint.commanders());
        }
        result.setWall(round.wall());
        result.setTerminalReason(round.terminalReason());
        recordCommandBonuses(result, CommandBonus.fromJson(session.getAttackerCommandBonus()),
                CommandBonus.fromJson(session.getDefenderCommandBonus()));
        recordUnitEquipment(result, JsonUtil.parseIntMap(session.getAttackerTech()), JsonUtil.parseIntMap(session.getDefenderTech()));
        recordEquipmentSources(result, JsonUtil.parseObjMap(session.getDefenderEquipmentSources()));
        Officer attackerCommander = march.getCommanderId() != null
                ? targets.getOfficerById(attackerPlayerId, march.getCommanderId()) : targets.getCommander(attackerPlayerId);
        Officer defenderCommander = target instanceof PlayerCity city && city.getOwnerId() != null
                ? (session.getDefenderCommanderId() != null
                    ? targets.getOfficerById(city.getOwnerId(), session.getDefenderCommanderId()) : targets.getCityCommander(city)) : null;
        if (defenderCommander == null) {
            if (target instanceof Bandit bandit) {
                defenderCommander = JapaneseOfficers.buildOfficer(bandit.getCommanderName(), bandit.getLevel() != null ? bandit.getLevel() : 1);
            } else if (target instanceof NpcCity city) {
                defenderCommander = JapaneseOfficers.buildOfficer(city.getCommanderName(), city.getLevel() != null ? city.getLevel() : 1);
            }
        }
        Long originalDefenderId = target instanceof PlayerCity city && targets.hasRealOwner(city) ? city.getOwnerId() : null;
        if (target instanceof WildTile tile) {
            settleWildBattle(attackerPlayerId, march, tile, now, result, attackerCommander);
        } else if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
            // 防守玩家的兵力、资源和城防按目标城市槽位持久化，结算时必须进入该城市作用域。
            try (var ignored = cityScope.enter(city)) {
                settleTacticalBattle(attackerPlayerId, march, target, now, result, attackerCommander, defenderCommander, session.getAction());
            }
        } else {
            settleTacticalBattle(attackerPlayerId, march, target, now, result, attackerCommander, defenderCommander, session.getAction());
        }
        if (joint != null) {
            Set<Long> supportPlayers = new LinkedHashSet<>();
            joint.getFormations().values().stream().filter(f -> "support".equals(f.getKind())).forEach(f -> supportPlayers.add(f.getPlayerId()));
            for (Long id : supportPlayers) if (!id.equals(originalDefenderId) && !id.equals(attackerPlayerId))
                pushBattleReport(id, result, march, attackerCommander, defenderCommander);
        }
        battleSessionRepository.deleteResolvedById(session.getId());
        // 只移除已终结会话的一级缓存；全局 clear 会丢掉同事务粮账和城市归属的后续更新。
        if (em.contains(session)) em.detach(session);
        if (joint != null) joint.getFormations().values().stream().filter(f -> f.getMarchId() != null && "support".equals(f.getKind()))
                .forEach(f -> marchRepository.findById(f.getMarchId()).filter(m -> "origin_lost".equals(m.getReturnReason()))
                        .ifPresent(m -> { displaceSupportAfterCityLoss(m, now); recoverDisplacedSupport(m, now); }));
        if (target instanceof PlayerCity city) {
            for (March support : guildSupport.stationed(city)) if (!guildSupport.canReceive(support.getPlayerId(), city, support.getSupportOwnerId(), now)) {
                try (var ignored = cityScope.enter(support.getPlayerId(), support.getCitySlot())) { startReturnMarch(support, now); }
                marchRepository.save(support);
            }
            garrisonFood.settle(city, now); garrisonFood.enforce(city, now);
        }
        Map<String, Object> attack = terminalBattleView(session, round, result, battleLog, false);
        Map<String, Object> defense = originalDefenderId == null ? null : terminalBattleView(session, round, result, battleLog, true);
        battleOutcomes.save(march.getId(), attackerPlayerId, originalDefenderId, attack, defense);
        return defendingViewer ? defense : attack;
    }

    /** 从最终回合构建双方视角；保留最终兵力、破墙结果和胜负，不读取当前驻军。 */
    private Map<String, Object> terminalBattleView(BattleSession session, BattleRoundState round,
            BattleResult result, String battleLog, boolean defendingViewer) {
        Map<String, Object> response = battleView(session, true, defendingViewer);
        response.put("round", round.round());
        response.put("attackerArmy", defendingViewer ? round.defenderArmy() : round.attackerArmy());
        response.put("defenderArmy", defendingViewer ? round.attackerArmy() : round.defenderArmy());
        response.put("attackerPositions", viewerPositions(defendingViewer ? round.defenderPositions() : round.attackerPositions(), session.getInitialDistance(), defendingViewer));
        response.put("defenderPositions", viewerPositions(defendingViewer ? round.attackerPositions() : round.defenderPositions(), session.getInitialDistance(), defendingViewer));
        response.put("log", battleLog);
        response.put("wall", round.wall());
        response.put("battlePhase", round.wall() != null ? round.wall().phase() : null);
        response.put("finished", true);
        response.put("terminalReason", round.terminalReason());
        if (result.getFormationContributions() != null) response.put("formationContributions", result.getFormationContributions());
        JointDefenseState joint = JointDefenseState.read(session.getJointDefense());
        if (joint != null) response.put("formationRecovery", ownFormationRecovery(result,
                joint.getFormations().get(defendingViewer ? "0" : "attack").getPlayerId()));
        response.put("result", Map.of("win", defendingViewer ? !result.isWin() : result.isWin(), "report", result.getReport()));
        return response;
    }

    /**
     * 验证战术会话访问者。进攻方控制出征部队；防守方仅限被攻击城市的真实所有者。
     *
     * @param playerId 当前玩家 ID
     * @param marchId 进攻行军 ID
     * @return 行军与当前玩家是否为防守方的访问结果
     */
    private TacticalBattleAccess tacticalBattleAccess(Long playerId, Long marchId) {
        March march = marchRepository.findById(marchId).orElseThrow(() -> new IllegalArgumentException("行军任务不存在"));
        if (Set.of("transport", "rebase", "reinforce", "station").contains(Objects.requireNonNullElse(march.getAction(), ""))) {
            throw new IllegalArgumentException("该行军不是进攻任务");
        }
        if (playerId.equals(march.getPlayerId())) return new TacticalBattleAccess(march, false);
        Object target = targets.findTargetById(march.getTargetKind(), march.getTargetId());
        if (target instanceof PlayerCity city && targets.hasRealOwner(city) && playerId.equals(city.getOwnerId())) {
            return new TacticalBattleAccess(march, true);
        }
        throw new IllegalArgumentException("无权查看该战斗");
    }

    private Map<String, BattleService.UnitOrder> parseBattleOrders(
            com.wargame.model.dto.GameDtos.BattleCommandRequest request,
            Map<String, Integer> attackerArmy, Map<String, Integer> defenderArmy) {
        Map<String, BattleService.UnitOrder> orders = new LinkedHashMap<>();
        Map<String, com.wargame.model.dto.GameDtos.BattleUnitOrderRequest> requested =
                request != null && request.orders() != null ? request.orders() : Collections.emptyMap();
        for (Map.Entry<String, com.wargame.model.dto.GameDtos.BattleUnitOrderRequest> entry : requested.entrySet()) {
            if (entry.getValue() == null || attackerArmy.entrySet().stream().noneMatch(e -> UnitEquipmentEffects.base(e.getKey()).equals(UnitEquipmentEffects.base(entry.getKey())) && e.getValue() > 0)) continue;
            String focusTarget = entry.getValue().focusTarget();
            // 空指令不清除本场已有命令，未曾下令的兵种才使用账号预设。
            if (entry.getValue().action() == null || entry.getValue().action().isBlank()) {
                if (focusTarget == null || focusTarget.isBlank()) continue;
                throw new IllegalArgumentException("选择集火目标时，请同时选择前进、后退或待命");
            }
            BattleService.CommandAction action;
            try { action = BattleService.CommandAction.valueOf(entry.getValue().action().toUpperCase(Locale.ROOT)); }
            catch (Exception ex) { throw new IllegalArgumentException("未知战术指令：" + entry.getValue().action()); }
            if (focusTarget != null && !focusTarget.isBlank() && UnitEquipmentEffects.aggregate(defenderArmy).getOrDefault(UnitEquipmentEffects.base(focusTarget), 0) <= 0) {
                throw new IllegalArgumentException("指定集火目标不在战场上");
            }
            for (String group : attackerArmy.keySet()) {
                if (UnitEquipmentEffects.base(group).equals(UnitEquipmentEffects.base(entry.getKey()))) orders.put(group, new BattleService.UnitOrder(action, focusTarget));
            }
        }
        return orders;
    }

    /**
     * 恢复本场存活兵种的持久指令；集火目标全灭后恢复默认索敌，保留移动命令。
     * @param json 当前指挥方保存的指令，历史会话可为空
     * @param army 当前指挥方的存活部队
     * @param enemyArmy 对方的存活部队
     * @return 仅包含当前有效兵种与目标的显式指令
     */
    private Map<String, BattleService.UnitOrder> persistentBattleOrders(
            String json, Map<String, Integer> army, Map<String, Integer> enemyArmy) {
        var saved = JsonUtil.parseTree(json);
        Map<String, Integer> enemies = UnitEquipmentEffects.aggregate(enemyArmy);
        Map<String, BattleService.UnitOrder> orders = new LinkedHashMap<>();
        army.forEach((unitId, count) -> {
            if (count <= 0) return;
            var entry = saved.has(UnitEquipmentEffects.base(unitId)) ? saved.path(UnitEquipmentEffects.base(unitId)) : saved.path(unitId);
            String action = entry.path("action").asText(null);
            if (action == null) return;
            String focusTarget = entry.path("focusTarget").asText(null);
            if (focusTarget == null || focusTarget.isBlank()
                    || enemies.getOrDefault(UnitEquipmentEffects.base(focusTarget), 0) <= 0) focusTarget = null;
            orders.put(unitId, new BattleService.UnitOrder(BattleService.CommandAction.valueOf(action), focusTarget));
        });
        return orders;
    }

    private Map<String, Integer> initialPositions(Map<String, Integer> army, int position) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        army.forEach((unit, count) -> positions.put(unit, position));
        return positions;
    }

    /** 仅真实玩家城征服需要破墙，读取被攻击城市槽位，不能误用攻方或主城等级。 */
    private ConquestWallState conquestWall(Object target, String action, Map<String, Integer> army, Map<String, Integer> forts) {
        if (action == null) action = "conquer";
        if (!(target instanceof PlayerCity city) || !targets.hasRealOwner(city)
                || !Set.of("conquer", "tactical_conquer").contains(action)) return null;
        boolean defendersAlive = java.util.stream.Stream.concat(army.values().stream(), forts.values().stream())
                .anyMatch(n -> n != null && n > 0);
        try (var ignored = cityScope.enter(city)) {
            return ConquestWallState.create(targets.buildingLevel(city.getOwnerId(), "wall"),
                    targets.getTechLevel(city.getOwnerId(), "wall_reinforcement"), defendersAlive, BattleRules.VERSION);
        }
    }

    /** 旧会话缺等级快照则沿用旧规则；禁止刷新或超时重新读取建筑、重新补满耐久。 */
    private ConquestWallState restoreWall(BattleSession session) {
        if (session.getWallLevelSnapshot() == null) return null;
        return new ConquestWallState(session.getWallLevelSnapshot(), session.getWallMaxHp(), session.getWallHpRemaining(),
                session.getWallDefense(), session.getBattlePhase(), session.getBattleRulesVersion());
    }

    private void saveWall(BattleSession session, ConquestWallState wall) {
        if (wall == null) return;
        session.setWallLevelSnapshot(wall.level());
        session.setWallMaxHp(wall.maxHp());
        session.setWallHpRemaining(wall.hpRemaining());
        session.setWallDefense(wall.defense());
        session.setBattlePhase(wall.phase());
        session.setBattleRulesVersion(session.getJointDefense() == null ? wall.rulesVersion() : JointDefenseState.VERSION);
    }

    /** 仅为集火验证增加墙体标识；不会改变真实军队、损失、伤兵或援军快照。 */
    private Map<String, Integer> battleTargets(BattleSession session, Map<String, Integer> enemyArmy, boolean defendingViewer) {
        Map<String, Integer> targets = new LinkedHashMap<>(enemyArmy);
        ConquestWallState wall = restoreWall(session);
        if (!defendingViewer && wall != null && wall.standing() && ConquestWallState.ATTACK_WALL.equals(wall.phase())) {
            targets.put(ConquestWallState.TARGET, 1);
        }
        return targets;
    }

    /** 守方交换部队后同时镜像坐标，使双方视角均为我军在左、敌军在右；距离保持不变。 */
    private Map<String, Integer> viewerPositions(Map<String, Integer> positions, int distance, boolean defendingViewer) {
        if (!defendingViewer) return positions;
        Map<String, Integer> mirrored = new LinkedHashMap<>();
        positions.forEach((id, x) -> mirrored.put(id, distance - x));
        return mirrored;
    }

    /**
     * 以当前指挥方视角序列化战场，防守方需要看到守军作为“我军”才能提交正确兵种指令。
     */
    private Map<String, Object> battleView(BattleSession session, boolean finished, boolean defendingViewer) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("marchId", session.getMarchId());
        Player attacker = playerRepository.findById(session.getPlayerId()).orElse(null);
        view.put("targetName", defendingViewer ? (attacker != null ? PlayerNames.commanderName(attacker) : "来袭敌军") : session.getTargetName());
        view.put("targetKind", session.getTargetKind());
        view.put("action", session.getAction());
        view.put("side", defendingViewer ? "defender" : "attacker");
        view.put("round", session.getRoundNo());
        // 双方视角共用交战起点，客户端据此展示下一回合真实机动速度，刷新不重置技能周期。
        view.put("firstCombatRound", session.getFirstCombatRound() != null ? session.getFirstCombatRound()
                : battleService.firstCombatRoundFromLog(session.getBattleLog()));
        view.put("roundDeadlineAt", session.getRoundDeadlineAt());
        view.put("maxRound", BattleService.maxRoundForAction(session.getAction()));
        view.put("initialDistance", session.getInitialDistance());
        view.put("rulesVersion", session.getBattleRulesVersion());
        view.put("wall", restoreWall(session));
        view.put("battlePhase", session.getBattlePhase());
        view.put("attackerArmy", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderArmy() : session.getAttackerArmy()));
        view.put("defenderArmy", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerArmy() : session.getDefenderArmy()));
        view.put("orders", persistentBattleOrders(defendingViewer ? session.getDefenderOrders() : session.getAttackerOrders(),
                JsonUtil.parseIntMap(defendingViewer ? session.getDefenderArmy() : session.getAttackerArmy()),
                battleTargets(session, JsonUtil.parseIntMap(defendingViewer ? session.getAttackerArmy() : session.getDefenderArmy()), defendingViewer)));
        Map<String, Integer> attackerPositions = new LinkedHashMap<>(JsonUtil.parseIntMap(session.getAttackerPositions()));
        Map<String, Integer> defenderPositions = new LinkedHashMap<>(JsonUtil.parseIntMap(session.getDefenderPositions()));
        battleService.normalizeBattleFronts(JsonUtil.parseIntMap(session.getAttackerArmy()),
                JsonUtil.parseIntMap(session.getDefenderArmy()), attackerPositions, defenderPositions, session.getInitialDistance());
        view.put("attackerPositions", viewerPositions(defendingViewer ? defenderPositions : attackerPositions, session.getInitialDistance(), defendingViewer));
        view.put("defenderPositions", viewerPositions(defendingViewer ? attackerPositions : defenderPositions, session.getInitialDistance(), defendingViewer));
        view.put("attackerTech", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderTech() : session.getAttackerTech()));
        view.put("defenderTech", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerTech() : session.getDefenderTech()));
        view.put("attackerCommandBonus", CommandBonus.fromJson(defendingViewer ? session.getDefenderCommandBonus() : session.getAttackerCommandBonus()).toMap());
        view.put("defenderCommandBonus", CommandBonus.fromJson(defendingViewer ? session.getAttackerCommandBonus() : session.getDefenderCommandBonus()).toMap());
        view.put("equipmentSources", JsonUtil.parseObjMap(session.getDefenderEquipmentSources()));
        view.put("unitEquipment", Map.of("attacker", UnitEquipmentEffects.only(JsonUtil.parseIntMap(defendingViewer ? session.getDefenderTech() : session.getAttackerTech())), "defender", UnitEquipmentEffects.only(JsonUtil.parseIntMap(defendingViewer ? session.getAttackerTech() : session.getDefenderTech()))));
        view.put("attackerSkills", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderSkills() : session.getAttackerSkills()));
        view.put("defenderSkills", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerSkills() : session.getDefenderSkills()));
        Object target = defendingViewer ? targets.findTargetById(session.getTargetKind(), session.getTargetId()) : null;
        Long viewerId = defendingViewer && target instanceof PlayerCity city && targets.hasRealOwner(city)
                ? city.getOwnerId() : defendingViewer ? null : session.getPlayerId();
        if (viewerId != null) {
            var preferences = battleActionPreferences.get(viewerId);
            view.put("defaultActions", defendingViewer ? preferences.defending() : preferences.outgoing());
        }
        JointDefenseState joint = JointDefenseState.read(session.getJointDefense());
        if (joint != null) {
            Map<String, Object> labels = new LinkedHashMap<>(), contexts = new LinkedHashMap<>();
            joint.getFormations().values().forEach(f -> f.getInitial().keySet().forEach(unit -> {
                boolean own = defendingViewer != "attack".equals(f.getKind());
                labels.put((own ? "mine:" : "foe:") + unit, f.title());
                if (own) contexts.put(unit, Map.of("firstCombatRound", f.getFirstCombatRound(), "skills", f.getSkills(), "title", f.title(),
                        "formationId", joint.publicId(f), "military", f.getMilitary(), "defense", f.getDefense(), "equipment", f.getEquipment()));
            }));
            view.put("formationEvents", joint.getEvents());
            view.put("formationLabels", labels); view.put("unitContexts", contexts);
            view.put("formationContributions", joint.contributions(JsonUtil.parseIntMap(session.getAttackerArmy()), JsonUtil.parseIntMap(session.getDefenderArmy())));
            // 对手只得到本场可见行动，不再透传完整军官、来源配装与私人科技。
            view.put("defenderTech", Map.of()); view.put("defenderSkills", Map.of()); view.put("defenderCommandBonus", Map.of());
            view.remove("equipmentSources"); view.remove("unitEquipment");
        }
        view.put("log", session.getBattleLog());
        view.put("finished", finished);
        return view;
    }

    private record TacticalBattleAccess(March march, boolean defending) {}

    /** 玩家手动结束派兵采集；部队留在野地，资源随手动返城到达后入库。 */
    @Transactional
    public void stopGatherMarch(Long playerId, Long marchId) {
        accounts.lockPlayer(playerId);
        March march = marchRepository.findById(marchId)
                .orElseThrow(() -> new IllegalArgumentException("采集任务不存在"));
        em.refresh(march);
        if (!playerId.equals(march.getPlayerId()) || march.getCitySlot() != cityScope.slot(playerId)) {
            throw new IllegalArgumentException("无权操作该采集任务");
        }
        if (!"wild_gather".equals(march.getTargetKind()) || Boolean.TRUE.equals(march.getReturning())) {
            throw new IllegalArgumentException("只能终止尚未返城的采集任务");
        }
        if (Boolean.TRUE.equals(march.getGatherStopped())) return;
        if (!Boolean.TRUE.equals(march.getGathering())) {
            throw new IllegalArgumentException("部队尚未开始采集，途中可使用撤回");
        }
        long now = GameClock.now();
        long start = Objects.requireNonNullElse(march.getGatherStartAt(), now);
        long end = Objects.requireNonNullElse(march.getGatherEndAt(), now);
        int maxAmount = Objects.requireNonNullElse(march.getGatherAmount(), 0);
        double progress = end <= start ? 0 : Math.min(1D, Math.max(0D, (double) (now - start) / (end - start)));
        march.setGatherAmount((int) Math.floor(maxAmount * progress));
        march.setGathering(false);
        march.setGatherStopped(true);
        // 收获不发起返城或放弃；下一步必须由玩家另行下令。
        march.setGatherAfterActionResult("cancelled");
        settleGatherResources(march);
        marchRepository.save(march);
        pushService.pushMarchUpdate(playerId, marchEvent("gatherStopped", march,
                resourceEvent(march.getGatherRes(), march.getGatherAmount())));
    }

    /**
     * 消耗一枚行军令加速当前路段，保持当前地图位置，去程与回程分别限一次。
     * @param returning 客户端打开确认弹窗时的行军阶段
     * @param version 客户端读取的版本；阶段变化或旧快照必须重新确认
     */
    @Transactional
    public Map<String, Object> speedUpMarch(Long playerId, Long marchId, Boolean returning, Long version) {
        return speedUpMarch(playerId, marchId, returning, version,
                (String) previewOrder(playerId, marchId, "marchOrd").get("confirmationToken"));
    }

    @Transactional
    public Map<String, Object> speedUpMarch(Long playerId, Long marchId, Boolean returning, Long version, String confirmationToken) {
        return speedUpMarch(playerId, marchId, returning, version, confirmationToken, "marchOrd");
    }

    /** 预览与扣除必须绑定同一档位；旧调用默认使用原来的极速档库存。 */
    @Transactional
    public Map<String, Object> speedUpMarch(Long playerId, Long marchId, Boolean returning, Long version, String confirmationToken, String itemKey) {
        if (itemKey == null) itemKey = "marchOrd";
        if (!ItemDef.MARCH_BONUS_PERCENT.containsKey(itemKey)) throw new IllegalArgumentException("无效的行军令档位");
        if (marchId == null || returning == null || version == null) {
            throw new IllegalArgumentException("请刷新情报后重新选择行军加速");
        }
        lockInterceptionParticipants(playerId);
        March march = guildSupport.refreshGarrison(marchId)
                .orElseThrow(() -> new IllegalArgumentException("行军任务不存在"));
        em.refresh(march);
        if (!playerId.equals(march.getPlayerId()) || march.getCitySlot() != cityScope.slot(playerId)) {
            throw new IllegalArgumentException("无权加速该行军任务");
        }
        boolean isReturning = Boolean.TRUE.equals(march.getReturning());
        if (returning != isReturning || version != march.getVersion()) {
            throw new IllegalArgumentException("行军状态已变化，请刷新情报后重新确认");
        }
        if (settleInterceptionBeforeOrder(march, GameClock.now()))
            return new LinkedHashMap<>(Map.of("success", false, "message", "部队已在城外交火，请查看拦截战报后重新操作"));
        if (march.getBattleId() != null || Boolean.TRUE.equals(march.getStationed())
                || (!isReturning && (Boolean.TRUE.equals(march.getGathering()) || Boolean.TRUE.equals(march.getGatherStopped())))) {
            throw new IllegalArgumentException("只能加速正在移动的部队");
        }
        if (Boolean.TRUE.equals(isReturning ? march.getReturnAccelerated() : march.getOutboundAccelerated())) {
            throw new IllegalArgumentException("本段行军已经加速");
        }
        long now = GameClock.now();
        boolean deployingInterception = !isReturning && march.getInterceptDeployAt() != null;
        Long start = march.getStartAt(), arrive = deployingInterception
                ? march.getInterceptDeployAt() : march.getArriveAt();
        if (start == null || arrive == null || start > now || arrive <= start
                || (!deployingInterception && arrive - now <= WorldConfig.MARCH_ACCELERATION_MIN_MS)) {
            throw new IllegalArgumentException("剩余行军时间不足或已到达，无法加速");
        }
        Map<String, Object> effect = previewOrder(playerId, marchId, itemKey);
        tacticalOrders.requireToken((String) effect.get("confirmationToken"), confirmationToken);
        long duration = arrive - start;
        long remaining = arrive - now;
        long minRemaining = deployingInterception ? 10_000L : StratagemRules.MIN_MARCH_REMAINING_MS;
        long acceleratedRemaining = StratagemRules.acceleratedMarchRemaining(remaining,
                ((Number) effect.get("knowledge")).intValue(), minRemaining, itemKey);
        long baseDuration = baseMarchDuration(march);
        // 按同一比例压缩完整时间轴，使 (now-start)/(arrive-start) 在加速前后保持不变。
        long acceleratedDuration = Math.round(duration * ((double) acceleratedRemaining / remaining));
        if (playerItemRepository.tryConsume(playerId, itemKey, 1, now) == 0) {
            throw new IllegalArgumentException("行军令数量不足");
        }
        march.setBaseDurationMs(baseDuration);
        if (!isReturning && march.getInterceptDeployAt() != null) {
            march.setInterceptDeployAt(now + acceleratedRemaining);
            march.setInterceptReadyAt(march.getInterceptDeployAt() + InterceptionPlanner.PREPARATION_MS);
            march.setStartAt(march.getInterceptDeployAt() - acceleratedDuration);
        } else {
            march.setArriveAt(now + acceleratedRemaining);
            march.setStartAt(march.getArriveAt() - acceleratedDuration);
        }
        if (isReturning) march.setReturnAccelerated(true);
        else march.setOutboundAccelerated(true);
        recordOrderEffect(march, effect, now, remaining, acceleratedRemaining);
        // 扣令会清空持久化上下文；合并原版本后立即 flush，冲突时整个事务回滚。
        march = marchRepository.saveAndFlush(march);
        processInterceptionQueue(List.of(march), now);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("startAt", march.getStartAt()); timing.put("arriveAt", march.getArriveAt());
        timing.put("deployAt", march.getInterceptDeployAt()); timing.put("readyAt", march.getInterceptReadyAt());
        timing.put("version", march.getVersion()); timing.put("returning", isReturning);
        pushService.pushMarchUpdate(playerId, marchEvent("accelerated", march, timing));
        notifyIncomingChange(march, "accelerated");
        Map<String, Object> result = new LinkedHashMap<>(timing);
        result.put("success", true);
        result.put("message", isReturning ? "回城已加速" : "行军已加速");
        result.put("savedMs", remaining - acceleratedRemaining);
        result.put("effect", effect);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewOrder(Long playerId, Long marchId, String itemKey) {
        March march = marchRepository.findById(marchId)
                .orElseThrow(() -> new IllegalArgumentException("行军任务不存在"));
        em.refresh(march);
        long now = GameClock.now();
        boolean returning = Boolean.TRUE.equals(march.getReturning());
        boolean accelerating = ItemDef.MARCH_BONUS_PERCENT.containsKey(itemKey);
        if (accelerating) {
            if (!playerId.equals(march.getPlayerId()) || march.getCitySlot() != cityScope.slot(playerId))
                throw new IllegalArgumentException("只能加速本城己方行军");
            if (Boolean.TRUE.equals(returning ? march.getReturnAccelerated() : march.getOutboundAccelerated()))
                throw new IllegalArgumentException("本段行军已经加速");
        } else if (!canUseEnemyOrder(playerId, march, itemKey)) {
            throw new IllegalArgumentException("干扰令仅用于本城来袭，撤退拖延令仅用于从本城撤退的敌军");
        } else if (Boolean.TRUE.equals(returning ? march.getReturnStratagemUsed() : march.getStratagemUsed())) {
            throw new IllegalArgumentException("该行军本阶段已经受过计谋影响");
        }
        boolean deployingInterception = accelerating && !returning && march.getInterceptDeployAt() != null;
        Long arrive = deployingInterception ? march.getInterceptDeployAt() : march.getArriveAt();
        if (march.getBattleId() != null || Boolean.TRUE.equals(march.getStationed())
                || (!returning && (Boolean.TRUE.equals(march.getGathering()) || Boolean.TRUE.equals(march.getGatherStopped())))
                || march.getStartAt() == null || arrive == null || march.getStartAt() > now || arrive <= now
                || arrive <= march.getStartAt() || (accelerating && !deployingInterception && arrive - now <= WorldConfig.MARCH_ACCELERATION_MIN_MS))
            throw new IllegalArgumentException("部队当前不可使用道具或剩余时间不足");
        Map<String, Object> effect = tacticalOrders.effect(playerId, itemKey);
        String token = tacticalOrders.token(effect, playerId, cityScope.slot(playerId), marchId,
                march.getVersion(), returning, march.getStartAt(), arrive);
        long remaining = arrive - now;
        int knowledge = ((Number) effect.get("knowledge")).intValue();
        long minRemaining = deployingInterception ? 10_000L : StratagemRules.MIN_MARCH_REMAINING_MS;
        long after = accelerating ? StratagemRules.acceleratedMarchRemaining(remaining, knowledge, minRemaining, itemKey)
                : StratagemRules.delayedRemaining(remaining, knowledge);
        effect.put("confirmationToken", token);
        effect.put("marchId", marchId);
        effect.put("version", march.getVersion());
        effect.put("returning", returning);
        effect.put("arriveAt", march.getArriveAt());
        effect.put("remainingMs", remaining);
        effect.put("afterMs", after);
        effect.put("stock", playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey)
                .map(PlayerItem::getCount).orElse(0));
        return effect;
    }

    private boolean canUseEnemyOrder(Long playerId, March march, String itemKey) {
        if ("signalJammingOrder".equals(itemKey)) return canIntercept(playerId, cityScope.slot(playerId), march);
        if (!"feintOrder".equals(itemKey) || !Boolean.TRUE.equals(march.getReturning())
                || playerId.equals(march.getPlayerId()) || march.getPlayerId() == null
                || !"player".equals(march.getTargetKind())
                || !("scout".equals(march.getAction()) || BattleService.isOffensiveAction(march.getAction())
                || Objects.requireNonNullElse(march.getAction(), "").startsWith("tactical"))) return false;
        try {
            return playerCityRepository.findById(Long.valueOf(march.getTargetId()))
                    .filter(city -> playerId.equals(city.getOwnerId())
                            && Objects.requireNonNullElse(city.getCitySlot(), 0) == cityScope.slot(playerId)).isPresent();
        } catch (NumberFormatException | NullPointerException ignored) {
            return false;
        }
    }

    private void recordOrderEffect(March march, Map<String, Object> effect, long now, long before, long after) {
        List<Map<String, Object>> history = new ArrayList<>(JsonUtil.parseList(march.getOrderEffects()));
        Map<String, Object> snapshot = new LinkedHashMap<>(effect);
        snapshot.remove("confirmationToken");
        snapshot.put("usedAt", now);
        snapshot.put("remainingMs", before);
        snapshot.put("afterMs", after);
        snapshot.put("actualChangeMs", Math.abs(after - before));
        history.add(snapshot);
        march.setOrderEffects(JsonUtil.toJson(history));
    }

    /**
     * 对指向己方城市的敌军在途行军使用一次拖延计谋，并按比例拉伸整段时间轴以保持当前位置。
     * @param expectedArriveAt 弹窗展示的抵达时间，用于拒绝过期情报
     */
    @Transactional
    public Map<String, Object> applyMarchStratagem(Long playerId, Long marchId, String itemKey, Long expectedArriveAt) {
        return applyMarchStratagem(playerId, marchId, itemKey, expectedArriveAt,
                (String) previewOrder(playerId, marchId, itemKey).get("confirmationToken"));
    }

    @Transactional
    public Map<String, Object> applyMarchStratagem(Long playerId, Long marchId, String itemKey, Long expectedArriveAt, String confirmationToken) {
        if (marchId == null || expectedArriveAt == null) throw new IllegalArgumentException("请刷新军情后重新选择计谋");
        String itemName;
        if ("signalJammingOrder".equals(itemKey)) {
            itemName = "通讯干扰令";
        } else if ("feintOrder".equals(itemKey)) {
            itemName = "撤退拖延令";
        } else throw new IllegalArgumentException("未知计谋道具");

        March snapshot = marchRepository.findById(marchId)
                .orElseThrow(() -> new IllegalArgumentException("敌军行军已不存在"));
        if (snapshot.getPlayerId() == null || playerId.equals(snapshot.getPlayerId())) {
            throw new IllegalArgumentException("只能对敌方行军使用计谋");
        }
        lockInterceptionParticipants(playerId, snapshot.getPlayerId());
        March march = guildSupport.refreshGarrison(marchId)
                .orElseThrow(() -> new IllegalArgumentException("敌军行军已不存在"));
        em.refresh(march);
        long now = GameClock.now();
        boolean returning = Boolean.TRUE.equals(march.getReturning());
        if (!canUseEnemyOrder(playerId, march, itemKey) || march.getBattleId() != null
                || Boolean.TRUE.equals(march.getStationed())
                || !returning && (Boolean.TRUE.equals(march.getGathering()) || Boolean.TRUE.equals(march.getGatherStopped()))) {
            throw new IllegalArgumentException("该敌军当前不能使用计谋");
        }
        if (!returning && settleInterceptionBeforeOrder(march, now))
            return new LinkedHashMap<>(Map.of("success", false, "message", "敌军已在城外交火，请刷新军情后重新操作"));
        if (Boolean.TRUE.equals(returning ? march.getReturnStratagemUsed() : march.getStratagemUsed())) throw new IllegalArgumentException("该行军本阶段已经受过计谋影响");
        Long start = march.getStartAt(), arrive = march.getArriveAt();
        if (start == null || arrive == null || start > now || arrive <= now || arrive <= start
                || !expectedArriveAt.equals(arrive)) {
            throw new IllegalArgumentException("行军状态已变化或已抵达，请刷新军情后重试");
        }
        long remaining = arrive - now;
        Map<String, Object> effect = previewOrder(playerId, marchId, itemKey);
        tacticalOrders.requireToken((String) effect.get("confirmationToken"), confirmationToken);
        long delay = StratagemRules.delayedRemaining(remaining, ((Number) effect.get("knowledge")).intValue()) - remaining;
        if (delay <= 0) throw new IllegalArgumentException("剩余行军时间过短，无法拖延");

        double progress = Math.max(0.0, Math.min(1.0, (double) (now - start) / (arrive - start)));
        long extendedArrival = arrive + delay;
        // 先求出延长后剩余路程占整段行程的比例，保持当下地图位置不跳回。
        long extendedDuration = Math.round((extendedArrival - now) / Math.max(0.000001, 1.0 - progress));
        long adjustedStart = now - Math.round(progress * extendedDuration);
        if (playerItemRepository.tryConsume(playerId, itemKey, 1, now) == 0) {
            throw new IllegalArgumentException(itemName + "数量不足");
        }
        march.setStartAt(adjustedStart);
        march.setArriveAt(extendedArrival);
        if (returning) march.setReturnStratagemUsed(true);
        else march.setStratagemUsed(true);
        recordOrderEffect(march, effect, now, remaining, remaining + delay);
        march = marchRepository.saveAndFlush(march);

        Map<String, Object> timing = Map.of("startAt", march.getStartAt(), "arriveAt", march.getArriveAt(),
                "delayMs", delay, "itemKey", itemKey, "itemName", itemName);
        processInterceptionQueue(List.of(march), now);
        notifyIncomingChange(march, "stratagemDelayed");
        pushService.pushMarchUpdate(march.getPlayerId(), marchEvent("stratagemDelayed", march, timing));
        Map<String, Object> result = new LinkedHashMap<>(timing);
        result.put("success", true);
        result.put("effect", effect);
        result.put("message", itemName + "生效，敌军行军已拖延 " + DurationText.seconds(Math.max(1, (delay + 999) / 1000)));
        return result;
    }

    /** 召回前先结算已发生的截击；返回实际结果，避免已交火或全歼时仍提示召回成功。 */
    @Transactional
    public Map<String, Object> cancelMarch(Long playerId, Long marchId) {
        lockInterceptionParticipants(playerId);
        March march = guildSupport.refreshGarrison(marchId)
                .orElseThrow(() -> new IllegalArgumentException("行军任务不存在"));
        em.refresh(march);
        if (!playerId.equals(march.getPlayerId()) || march.getCitySlot() != cityScope.slot(playerId)) {
            throw new IllegalArgumentException("无权取消该行军任务");
        }
        if (settleInterceptionBeforeOrder(march, GameClock.now()))
            return new LinkedHashMap<>(Map.of("success", false, "message", "部队已在城外交火，请查看拦截战报后重新操作"));
        if (Boolean.TRUE.equals(march.getReturning()) || Boolean.TRUE.equals(march.getGathering())) {
            throw new IllegalArgumentException("行军已进入返程或采集阶段，无法取消");
        }
        if ("wild_gather".equals(march.getTargetKind())) {
            march.setGatherAfterActionResult("cancelled");
            if (Objects.requireNonNullElse(march.getGatherEndAt(), 0L) > 0) settleGatherResources(march);
        }
        if (march.getBattleId() != null) {
            throw new IllegalArgumentException("部队已进入战场，请完成战斗后再返程");
        }
        if (Boolean.TRUE.equals(march.getStationed())) {
            PlayerCity destination = playerCityRepository.findById(Long.valueOf(march.getTargetId())).orElse(null);
            if (destination != null) accounts.lockPlayer(destination.getOwnerId());
            march = guildSupport.refreshGarrison(marchId).orElseThrow(() -> new IllegalArgumentException("驻军已在战斗中损失"));
            if (garrisonFood.locked(march, battleSessionRepository.findActivePlayerCityBattles(march.getTargetId()))) {
                throw new IllegalArgumentException("本编队正在参战，请战斗结束后撤回");
            }
        }
        // 撤回是在途行军应进入返程，而不是立即删除记录。这样地图和军情页
        // 能继续展示返城进度，部队与资源在返程到达后统一归队。
        long now = GameClock.now();
        long outboundStart = march.getStartAt() != null ? march.getStartAt() : now;
        long outboundEnd = march.getInterceptDeployAt() != null ? march.getInterceptDeployAt()
                : march.getArriveAt() != null ? march.getArriveAt() : now;
        long duration = Math.max(1000L, outboundEnd - outboundStart);
        long travelled = Boolean.TRUE.equals(march.getStationed()) ? duration : Math.max(1000L, Math.min(duration, now - outboundStart));
        long returnDuration = baseMarchDuration(march);
        long returnTravelled = Math.max(1000L, Math.round(returnDuration * ((double) travelled / duration)));
        startReturnMarch(march, now);
        // 保留完整路线并回推返程起点时间：地图标记从撤回时的位置掉头，
        // 以已走过的路线比例计算基础返程耗时，去程加速不会免费带到回程。
        march.setStartAt(now + returnTravelled - returnDuration);
        march.setArriveAt(now + returnTravelled);
        marchRepository.saveAndFlush(march);
        notifyIncomingChange(march, "cancelled");
        processInterceptionQueue(List.of(march), now);
        if (march.getSupportOwnerId() != null) {
            pushService.pushToPlayer(march.getSupportOwnerId(), "support_update", Map.of("marchId", march.getId()));
        }
        return new LinkedHashMap<>(Map.of("success", true, "message", "部队已撤回，正在返城，抵达后归还部队和携带资源"));
    }

    /** 城主只能遣返驻扎在当前城市的援军，返城资产仍归原派兵人。 */
    @Transactional
    public void dismissSupport(Long playerId, Long marchId) {
        accounts.lockPlayer(playerId);
        March march = marchRepository.findById(marchId).orElseThrow(() -> new IllegalArgumentException("驻军不存在"));
        PlayerCity city = cityScope.selected(playerId).orElseThrow(() -> new IllegalArgumentException("城市不存在"));
        if (!Boolean.TRUE.equals(march.getStationed()) || !playerId.equals(march.getSupportOwnerId())
                || !city.getId().toString().equals(march.getTargetId())) throw new IllegalArgumentException("无权遣返该驻军");
        try (var ignored = cityScope.enter(march.getPlayerId(), march.getCitySlot())) {
            cancelMarch(march.getPlayerId(), marchId);
        }
    }

    // ========================================================================
    // 野地驻军与就地采集/收获/撤回
    // ========================================================================

    @Transactional
    public Map<String, Object> startWildGather(Long playerId, Long wildTileId) {
        return startWildGather(playerId, wildTileId, "manual");
    }

    /** 驻军只支持手动采集；省略模式按 manual 处理，拒绝旧客户端的自动指令。 */
    @Transactional
    public Map<String, Object> startWildGather(Long playerId, Long wildTileId, String mode) {
        String gatherMode = mode == null ? "manual" : mode;
        if (!"manual".equals(gatherMode)) throw new IllegalArgumentException("采集仅支持手动模式，请刷新页面后重试");
        WildTile wt = lockOwnedWild(playerId, wildTileId);
        if (Boolean.TRUE.equals(wt.getGathering())) {
            throw new IllegalArgumentException("该野地已在采集中");
        }
        if (wt.getGatherHarvested() != null) throw new IllegalArgumentException("已收获的部队等待回城，请先下达回城命令");
        Map<String, Integer> garrison = JsonUtil.parseIntMap(wt.getGarrison());
        if (garrison.isEmpty()) {
            throw new IllegalArgumentException("野地暂无驻军，请先派遣部队进驻");
        }
        WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(wt.getType());
        if (wtDef == null || wtDef.res() == null) {
            throw new IllegalArgumentException("该野地无资源可采集");
        }
        int totalRes = wt.getTotalRes() != null ? wt.getTotalRes() : 0;
        int mined = wt.getMined() != null ? wt.getMined() : 0;
        int remaining = totalRes - mined;
        if (remaining <= 0) {
            throw new IllegalArgumentException("该野地资源已耗尽");
        }
        Map<String,Integer> gatherEquipment = TechnologyRules.actionSnapshot(targets.getCombatTechMap(playerId));
        int load = calcArmyLoad(garrison, gatherEquipment);
        if (load <= 0) {
            throw new IllegalArgumentException("当前驻军无运载能力，无法采集");
        }
        int gatherAmount = Math.min(remaining, load);
        long now = GameClock.now();
        long gatherDuration = (long) Math.max(30L, Math.ceil(gatherAmount / WILD_GATHER_BASE_SPEED)) * 1000L;

        wt.setGarrisonRoutes(JsonUtil.toJson(resolveGarrisonRoutes(playerId, wt, gatherEquipment).stream().map(route -> new GarrisonRoute(route.playerId(), route.citySlot(), route.fromX(), route.fromY(), route.distance(), route.duration(), route.routeMode(), route.routeData(), route.army(), gatherEquipment)).toList()));
        wt.setGatherMode(gatherMode);
        wt.setGatherCitySlot(cityScope.slot(playerId));
        wt.setGathering(true);
        wt.setGatherStartAt(now);
        wt.setGatherEndAt(now + gatherDuration);
        wt.setGatherLoad(gatherAmount);
        wt.setGatherRes(wtDef.res());
        wildTileRepository.save(wt);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "野地驻军已开始采集资源");
        res.put("gatherAmount", gatherAmount);
        res.put("gatherRes", wtDef.res());
        res.put("gatherEndAt", wt.getGatherEndAt());
        res.put("gatherMode", gatherMode);
        return res;
    }

    /** 玩家手动收获驻军采集任务，部队留守，仍需单独下达回城命令。 */
    @Transactional
    public Map<String, Object> harvestWild(Long playerId, Long wildTileId) {
        WildTile wt = lockOwnedWild(playerId, wildTileId);
        if (!Boolean.TRUE.equals(wt.getGathering()) && wt.getGatherHarvested() == null) {
            throw new IllegalArgumentException("该野地当前并未在采集中");
        }
        if (Boolean.TRUE.equals(wt.getGathering())) finishStationedGather(wt, GameClock.now());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "已终止采集并收获 " + wt.getGatherHarvested() + " 资源；部队留守，需下达回城命令后运回入库");
        res.put("harvestAmount", wt.getGatherHarvested());
        res.put("harvestRes", wt.getGatherRes());
        return res;
    }

    /** 收获只扣减野地存量；资源随驻军返城后入库，重复收获不会重复结算。 */
    private void finishStationedGather(WildTile wt, long now) {
        long startAt = wt.getGatherStartAt() != null ? wt.getGatherStartAt() : now;
        long endAt = wt.getGatherEndAt() != null ? wt.getGatherEndAt() : now;
        int maxLoad = Math.max(0, Objects.requireNonNullElse(wt.getGatherLoad(), 0));
        String resKey = wt.getGatherRes();
        double ratio = endAt > startAt ? Math.max(0, Math.min(1, (double) (now - startAt) / (endAt - startAt)))
                : (now >= startAt ? 1 : 0);
        int harvestAmount = (int) Math.floor(ratio * maxLoad);
        int totalRes = wt.getTotalRes() != null ? wt.getTotalRes() : 0;
        int mined = wt.getMined() != null ? wt.getMined() : 0;
        int remaining = Math.max(0, totalRes - mined);
        // 战损后只按存活驻军承运；旧记录缺路线时按无装备补全，不能把收获容量降成零。
        List<GarrisonRoute> aliveRoutes;
        try (var ignored = cityScope.enter(wt.getOccupiedBy(), Objects.requireNonNullElse(wt.getGatherCitySlot(), 0))) {
            aliveRoutes = resolveGarrisonRoutes(wt.getOccupiedBy(), wt);
        }
        wt.setGarrisonRoutes(JsonUtil.toJson(aliveRoutes));
        long capacity = aliveRoutes.stream().mapToLong(route -> calcArmyLoad(route.army(), route.equipment())).sum();
        harvestAmount = resKey == null ? 0 : (int) Math.min(Math.min(harvestAmount, remaining), capacity);
        int newTotal = Math.max(0, remaining - harvestAmount);
        wt.setTotalRes(newTotal);
        wt.setMined(0);
        if (newTotal <= 0 && wt.getDepletedAt() == null) {
            // 驻军采集耗尽资源后同样进入统一刷新队列。
            wt.setDepletedAt(now);
        }
        wt.setGatherHarvested(harvestAmount);
        wt.setGathering(false);
        wildTileRepository.save(wt);
    }

    @Transactional
    public Map<String, Object> recallWild(Long playerId, Long wildTileId) {
        WildTile wt = lockOwnedWild(playerId, wildTileId);
        if (Boolean.TRUE.equals(wt.getGathering())) throw new IllegalArgumentException("请先手动收获以结束采集，再下达回城命令");
        Map<String, Integer> garrison = JsonUtil.parseIntMap(wt.getGarrison());
        if (garrison.values().stream().noneMatch(count -> count > 0)) {
            throw new IllegalArgumentException("该野地暂无驻军可撤回");
        }
        returnStationedArmy(playerId, wt, GameClock.now());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "驻军已开始按原路线返城，抵达后部队与资源入库；野地仍属于你");
        return res;
    }

    private WildTile lockOwnedWild(Long playerId, Long tileId) {
        playerRepository.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        WildTile tile = wildTileRepository.lockById(tileId).orElseThrow(() -> new IllegalArgumentException("野地不存在"));
        if (!Boolean.TRUE.equals(tile.getOccupied()) || !playerId.equals(tile.getOccupiedBy())) {
            throw new IllegalArgumentException("该野地未被你占领");
        }
        return tile;
    }

    private record GarrisonRoute(Long playerId, Integer citySlot, Integer fromX, Integer fromY,
                                 Double distance, long duration, String routeMode, String routeData, Map<String, Integer> army, Map<String, Integer> equipment) {}

    private List<GarrisonRoute> readGarrisonRoutes(WildTile tile) {
        List<GarrisonRoute> result = new ArrayList<>();
        for (Map<String, Object> raw : JsonUtil.parseList(tile.getGarrisonRoutes())) {
            result.add(JsonUtil.getMapper().convertValue(raw, GarrisonRoute.class));
        }
        return result;
    }

    /** 历史无路线的驻军按无装备补全；新采集可传入当前配置，已有行程保留原时长。 */
    private List<GarrisonRoute> resolveGarrisonRoutes(Long playerId, WildTile tile) {
        return resolveGarrisonRoutes(playerId, tile, Map.of());
    }

    private List<GarrisonRoute> resolveGarrisonRoutes(Long playerId, WildTile tile, Map<String, Integer> fallbackEquipment) {
        Map<String, Integer> remaining = new LinkedHashMap<>(JsonUtil.parseIntMap(tile.getGarrison()));
        remaining.entrySet().removeIf(entry -> entry.getValue() <= 0);
        List<GarrisonRoute> result = new ArrayList<>();
        for (GarrisonRoute route : readGarrisonRoutes(tile)) {
            if (!playerId.equals(route.playerId())) continue;
            Map<String, Integer> army = new LinkedHashMap<>();
            route.army().forEach((unit, count) -> {
                int alive = Math.min(Math.max(0, count), remaining.getOrDefault(unit, 0));
                if (alive > 0) { army.put(unit, alive); remaining.put(unit, remaining.get(unit) - alive); }
            });
            if (!army.isEmpty()) result.add(new GarrisonRoute(playerId, route.citySlot(), route.fromX(), route.fromY(),
                    route.distance(), route.duration(), route.routeMode(), route.routeData(), army, route.equipment()));
        }
        remaining.entrySet().removeIf(entry -> entry.getValue() <= 0);
        if (!remaining.isEmpty()) {
            MarchRouteService.Route route = routes.plan(playerId, tile, remaining, false, null, fallbackEquipment);
            CityEconomy city = cityScope.economy(playerId);
            double slowest = remaining.keySet().stream().map(GameData.UNITS::get).filter(Objects::nonNull)
                    .mapToDouble(unit -> unit.spd() * getUnitTechSpdMul(playerId, unit.speedCategory())
                            * UnitEquipmentEffects.speed(fallbackEquipment, unit.key(), false)).min().orElse(1.0);
            long duration = Math.max(1L, (long) Math.ceil(route.distance() * WorldConfig.MARCH_SEC_PER_GRID / Math.max(0.1, slowest))) * 1000L;
            result.add(new GarrisonRoute(playerId, cityScope.slot(playerId), city.getCityPosX(), city.getCityPosY(),
                    route.distance(), duration, route.mode(), JsonUtil.toJson(route.points()), remaining, fallbackEquipment));
        }
        return result;
    }

    /** 城市易主前撤回其他来源的幸存驻军；调用方已锁定城主，失守城编队已清除。 */
    @Transactional
    public void evacuateWildGarrison(Long playerId, WildTile tile, long now) {
        if (Boolean.TRUE.equals(tile.getGathering())) finishStationedGather(tile, now);
        returnStationedArmy(playerId, tile, now);
    }

    private void returnStationedArmy(Long playerId, WildTile tile, long now) {
        List<GarrisonRoute> stationedRoutes;
        try (var ignored = cityScope.enter(playerId, tile.getGatherHarvested() == null ? cityScope.slot(playerId)
                : Objects.requireNonNullElse(tile.getGatherCitySlot(), 0))) {
            stationedRoutes = resolveGarrisonRoutes(playerId, tile);
        }
        int cargo = Math.max(0, Objects.requireNonNullElse(tile.getGatherHarvested(), 0));
        long remainingLoad = stationedRoutes.stream().mapToLong(route -> calcArmyLoad(route.army(), route.equipment())).sum();
        for (GarrisonRoute route : stationedRoutes) {
            int load = calcArmyLoad(route.army(), route.equipment());
            int amount = remainingLoad > 0 ? (int) Math.min(load, (long) cargo * load / remainingLoad) : 0;
            cargo -= amount;
            remainingLoad -= load;
            March march = new March();
            march.setPlayerId(playerId);
            march.setCitySlot(Objects.requireNonNullElse(route.citySlot(), 0));
            march.setTargetKind("wild");
            march.setTargetId(String.valueOf(tile.getId()));
            march.setTargetName(WildTypeDef.WILD_TYPES.containsKey(tile.getType()) ? WildTypeDef.WILD_TYPES.get(tile.getType()).name() : "野地");
            march.setTargetX(tile.getX());
            march.setTargetY(tile.getY());
            march.setFromX(route.fromX());
            march.setFromY(route.fromY());
            march.setDistance(route.distance());
            march.setRouteMode(route.routeMode());
            march.setRouteData(route.routeData());
            march.setStartAt(now);
            march.setArriveAt(now + Math.max(1000L, route.duration()));
            march.setArmy(JsonUtil.toJson(route.army()));
            march.setUnitEquipmentSnapshot(JsonUtil.toJson(route.equipment() == null ? Map.of() : route.equipment()));
            march.setAction(tile.getGatherHarvested() != null ? "gather" : "station");
            march.setGatherMode("manual");
            march.setGathering(false);
            march.setGatherAmount(amount);
            march.setGatherRes(tile.getGatherRes());
            march.setCarryRes(JsonUtil.toJson(amount > 0 && tile.getGatherRes() != null ? Map.of(tile.getGatherRes(), amount) : Map.of()));
            try (var ignored = cityScope.enter(playerId, march.getCitySlot())) { startReturnMarch(march, now); }
            marchRepository.save(march);
            pushService.pushMarchUpdate(playerId, marchEvent("returning",
                    march, resourceEvent(march.getGatherRes(), amount)));
        }
        tile.setGarrison("{}");
        tile.setGarrisonRoutes(null);
        clearStationedGather(tile);
        wildTileRepository.save(tile);
    }

    private void clearStationedGather(WildTile tile) {
        tile.setGathering(false);
        tile.setGatherStartAt(0L);
        tile.setGatherEndAt(0L);
        tile.setGatherLoad(0);
        tile.setGatherRes(null);
        tile.setGatherHarvested(null);
        tile.setGatherMode("manual");
        tile.setGatherCitySlot(0);
    }

    // ========================================================================
    // createDispatch - 对应 JS world.js launchDispatch
    // ========================================================================

    /** 世界行军的陆军（含步兵系）共用燃烧引擎；空军另叠加出发城市机场，战场不读取机场。 */
    public double getUnitTechSpdMul(Long playerId, String cat) {
        String catKey = switch (cat != null ? cat : "") {
            case "inf", "arm" -> "arm_engine";
            case "air" -> "air_engine";
            case "nav" -> "nav_engine";
            default -> null;
        };
        double mul = 1.0;
        if (catKey != null) {
            int lv = targets.getTechLevel(playerId, catKey);
            mul += 0.05 * lv;
        }
        if ("air".equals(cat)) {
            int apronLv = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), "apron")
                    .stream().mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0).sum();
            mul += 0.03 * apronLv;
        }
        return mul;
    }

    @Transactional
    public Map<String,Object> previewRoute(Long playerId,DispatchRequest req) {
        if ("incoming_scout".equals(req.targetKind()) || "intercept_scout".equals(req.action())) {
            return previewInterception(playerId, req);
        }
        if ("npc".equals(req.targetKind())) throw new IllegalArgumentException("NPC城市/要塞已取消");
        Object target=targets.findTargetByLongId(req.targetKind(),req.targetId());
        if(target==null)throw new IllegalArgumentException("目标不存在");
        if (target instanceof PlayerCity frozenCity && frozenCity.isSurrenderFrozen(GameClock.now())) {
            throw new com.wargame.security.GameAccessException("CITY_FROZEN", "归降移交中的城市拒绝新的行军", 409);
        }
        boolean transfer="transport".equals(req.action())||"rebase".equals(req.action())||"reinforce".equals(req.action());
        if (transfer) requireTransferTarget(playerId, target, req.action());
        if ("scout".equals(req.action())) ReconRules.requireUnit(req.army());
        Map<String,Integer> actionEquipment = TechnologyRules.actionSnapshot(targets.getCombatTechMap(playerId));
        Map<String,Integer> army=new LinkedHashMap<>();double slowest=Double.MAX_VALUE;
        for(var e:(req.army()==null?Map.<String,Integer>of():req.army()).entrySet()){
            var u=GameData.UNITS.get(e.getKey());if(u==null||e.getValue()==null||e.getValue()<=0)continue;
            int have=armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId,cityScope.slot(playerId),e.getKey()).stream().mapToInt(v->Objects.requireNonNullElse(v.getCount(),0)).sum();
            int count=Math.min(e.getValue(),have);if(count<=0)continue;
            army.put(e.getKey(),count);
            double effectiveSpd = u.spd() * getUnitTechSpdMul(playerId, u.speedCategory()) * UnitEquipmentEffects.speed(actionEquipment, u.key(), false);
            slowest=Math.min(slowest,effectiveSpd);
        }
        if(army.isEmpty())throw new IllegalArgumentException("请选择出征部队以计算路线");
        var route=routes.plan(playerId,target,army,transfer,req.action(),actionEquipment);
        var cs=cityStateRepository.findByPlayerIdAndCitySlot(playerId,cityScope.slot(playerId)).orElse(null);
        double boost=cs!=null?cs.marchSpeedMultiplier(GameClock.now()):1;
        int seconds=Math.max(1,(int)Math.ceil((double)route.distance()*WorldConfig.MARCH_SEC_PER_GRID/(Math.max(0.1,slowest)*boost)));
        return Map.of("mode",route.mode(),"points",route.points(),"distance",route.distance(),"seconds",seconds,"reservedLoad",route.reservedLoad(),"cargoLimit",Math.min(calcArmyLoad(army, actionEquipment),route.cargoLimit()),"oil",calcMarchFuel(army,route.distance(),"station".equals(req.action())||"rebase".equals(req.action())?1:2,actionEquipment),"unitEquipment",actionEquipment,"unitEquipmentVersion",UnitEquipmentEffects.actionVersion(actionEquipment));
    }

    /** 兼容快捷侦查：优先使用侦察机，无飞机时派出一辆侦查车，仍由正式出征复核陆路和补给。 */
    @Transactional
    public March createDefaultReconDispatch(Long playerId, String targetKind, Long targetId) {
        return createDefaultReconDispatch(playerId, targetKind, targetId, false);
    }

    /** 兼容接口同样要求显式退出新手保护，不能通过快捷侦查绕过正式出征校验。 */
    @Transactional
    public March createDefaultReconDispatch(Long playerId, String targetKind, Long targetId, Boolean abandonNewbieProtection) {
        for (String unit : List.of("scout", "recon_car")) {
            boolean available = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), unit)
                    .stream().anyMatch(army -> Objects.requireNonNullElse(army.getCount(), 0) > 0);
            if (available) return createDispatch(playerId, new DispatchRequest(targetKind, targetId, "scout",
                    Map.of(unit, 1), null, null, null, null, null, null, abandonNewbieProtection));
        }
        throw new IllegalArgumentException("城内没有可用侦查部队，请制造侦察机或侦查装甲车");
    }

    public int getHeadquartersLevel(Long playerId, int citySlot) {
        List<Building> list = buildingRepository.findByPlayerIdAndCitySlotAndType(playerId, citySlot, "headquarters");
        return list.stream().mapToInt(b -> Objects.requireNonNullElse(b.getLevel(), 0)).max().orElse(0);
    }

    public int getMaxMarches(Long playerId, int citySlot) {
        int hqLv = getHeadquartersLevel(playerId, citySlot);
        // 基础出征队伍5支 + 司令部等级: 0级5支，1级6支，此后每级+1 (Lv.10满级15支)
        return 5 + hqLv;
    }

    /**
     * 出城拦截来袭部队：侦查仅可用空军或装甲车，普通敌袭可用所有兵种。
     */
    @Transactional
    public March interceptScout(Long playerId, DispatchRequest req) {
        Map<String, Integer> reqArmy = req.army() != null ? req.army() : Collections.emptyMap();
        if (reqArmy.isEmpty()) throw new IllegalArgumentException("请配置拦截部队");

        int citySlot = cityScope.slot(playerId);
        int maxMarches = getMaxMarches(playerId, citySlot);
        long activeMarches = marchRepository.countByPlayerIdAndCitySlot(playerId, citySlot);
        if (activeMarches >= maxMarches) {
            throw new IllegalArgumentException("当前城池出征队伍已达上限 (" + activeMarches + "/" + maxMarches + ")，请等待部队返城或升级司令部");
        }

        Long enemyOwner = req.targetId() != null && !"incoming_scout".equals(req.targetKind())
                ? marchRepository.findById(req.targetId()).map(March::getPlayerId).orElse(null) : null;
        lockInterceptionParticipants(playerId, enemyOwner);
        CityEconomy city = cityScope.economy(playerId);
        int cityX = Objects.requireNonNullElse(city.getCityPosX(), 0);
        int cityY = Objects.requireNonNullElse(city.getCityPosY(), 0);
        long now = GameClock.now();
        for (var entry : reqArmy.entrySet()) {
            int count = Objects.requireNonNullElse(entry.getValue(), 0);
            int available = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, citySlot, entry.getKey())
                    .stream().mapToInt(unit -> Objects.requireNonNullElse(unit.getCount(), 0)).sum();
            if (count <= 0 || count > available) throw new IllegalArgumentException("拦截兵力不足或数量无效");
        }
        if (reqArmy.values().stream().mapToLong(Integer::longValue).sum() > armyService.armyCap(playerId)) {
            throw new IllegalArgumentException("出征兵力超过带兵上限");
        }
        if (req.commanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, req.commanderId());
            if (officer == null || !Objects.equals(officer.getCitySlot(), citySlot)
                    || Set.of("commander_in_chief", "mayor", "commander").contains(Objects.requireNonNullElse(officer.getRole(), ""))
                    || marchRepository.existsByPlayerIdAndCommanderId(playerId, officer.getId())) {
                throw new IllegalArgumentException("该军官无法随队拦截");
            }
        }

        Long targetId = req.targetId();
        March incomingMarch = null;
        IncomingMarch incomingEntity = null;
        if (targetId != null && !"incoming_scout".equals(req.targetKind())) {
            incomingMarch = guildSupport.refreshGarrison(targetId).orElse(null);
            if (incomingMarch != null) em.refresh(incomingMarch);
        }
        if (incomingMarch == null && !"incoming_march".equals(req.targetKind())) {
            incomingEntity = targetId == null ? null : incomingMarchRepository.findById(targetId)
                    .filter(im -> playerId.equals(im.getTargetPlayerId()) && "scout".equals(im.getAction())
                            && Objects.requireNonNullElse(im.getArriveAt(), 0L) > now).orElse(null);
        }
        if (incomingMarch != null && !canIntercept(playerId, citySlot, incomingMarch)) {
            throw new IllegalArgumentException("目标已不再是当前城市的可拦截敌军");
        }
        if (incomingMarch == null && incomingEntity == null) {
            throw new IllegalArgumentException("来袭目标已结束，无法出城拦截");
        }
        // 按真实来袭动作限制兵种，不能因目标来自真实行军就绕过侦查拦截限制。
        requireInterceptionUnits(reqArmy, incomingMarch == null || "scout".equals(incomingMarch.getAction()));

        Map<String, Integer> actionEquipment = TechnologyRules.actionSnapshot(targets.getCombatTechMap(playerId));
        if (req.unitEquipmentVersion() != null && !req.unitEquipmentVersion().equals(UnitEquipmentEffects.actionVersion(actionEquipment)))
            throw new IllegalArgumentException("装备配置已更新，请重新预览拦截部署计划");
        March enemyTimeline = incomingMarch != null ? incomingMarch : legacyInterceptionTarget(incomingEntity, cityX, cityY, now - 30_000L);
        InterceptionPlanner.Plan plan = planInterception(playerId, reqArmy, enemyTimeline, actionEquipment, now);
        int targetX = (int) Math.round(plan.point().x()), targetY = (int) Math.round(plan.point().y());
        long durationMs = plan.deployAt() - now;
        double marchDist = plan.distance();

        // 预扣油耗
        int fuel = calcMarchFuel(reqArmy, marchDist, 2, actionEquipment);
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, citySlot).orElse(null);
        if (res == null || Objects.requireNonNullElse(res.getOil(), 0L) < fuel) {
            throw new IllegalArgumentException("石油不足，无法准备拦截补给");
        }
        if (fuel > 0) {
            res.setOil(res.getOil() - fuel);
            resourcesRepository.save(res);
        }

        // 扣除兵力
        for (Map.Entry<String, Integer> entry : reqArmy.entrySet()) {
            String uid = entry.getKey();
            int n = entry.getValue();
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, citySlot, uid);
            for (ArmyUnit unit : existing) {
                int removed = Math.min(n, Objects.requireNonNullElse(unit.getCount(), 0));
                unit.setCount(Objects.requireNonNullElse(unit.getCount(), 0) - removed);
                armyUnitRepository.save(unit);
                n -= removed;
                if (n == 0) break;
            }
        }

        // 创建行军并出征
        March march = new March();
        march.setPlayerId(playerId);
        march.setCitySlot(citySlot);
        // 两张表的自增 ID 可能相同，持久化来源类型，结算时只能命中选定的来袭。
        march.setTargetKind(incomingMarch != null ? "incoming_march" : "incoming_scout");
        march.setTargetId(String.valueOf(targetId != null ? targetId : (incomingEntity != null ? incomingEntity.getId() : 0L)));
        march.setTargetName("拦截敌军 [" + targetX + "," + targetY + "]");
        march.setFromX(cityX);
        march.setFromY(cityY);
        march.setTargetX(targetX);
        march.setTargetY(targetY);
        march.setDistance(marchDist);
        march.setStartAt(now);
        march.setArriveAt(plan.clashAt());
        march.setInterceptDeployAt(plan.deployAt());
        march.setInterceptReadyAt(plan.readyAt());
        march.setInterceptEnemyProgress(plan.enemyProgress());
        march.setInterceptEnemyStartAt(enemyTimeline.getStartAt());
        march.setInterceptRouteSnapshot(InterceptionPlanner.signature(enemyTimeline));
        march.setRouteMode(plan.route().mode());
        march.setRouteData(JsonUtil.toJson(List.of(List.of((double) cityX, (double) cityY), List.of(plan.point().x(), plan.point().y()))));
        march.setBaseDurationMs(durationMs);
        march.setArmy(JsonUtil.toJson(reqArmy));
        march.setUnitEquipmentSnapshot(JsonUtil.toJson(actionEquipment));
        march.setAction("intercept_scout");
        march.setCommanderId(req.commanderId());
        if (req.commanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, req.commanderId());
            officer.setRole("march");
            officerRepository.save(officer);
        }
        march = marchRepository.save(march);

        notifyIncomingChange(march, "started");
        pushService.pushMarchUpdate(playerId, marchEvent("interceptDispatched", march, Map.of(
                "interceptX", targetX,
                "interceptY", targetY,
                "durationSec", durationMs / 1000L,
                "deployAt", plan.deployAt(), "readyAt", plan.readyAt(), "arriveAt", plan.clashAt()
        )));
        return march;
    }

    /** 预览与正式出征使用同一目标、兵种限制和服务端路线，预览不扣兵、不扣油。 */
    private Map<String, Object> previewInterception(Long playerId, DispatchRequest req) {
        long now = GameClock.now();
        var city = cityScope.economy(playerId);
        March enemy = req.targetId() == null || "incoming_scout".equals(req.targetKind()) ? null
                : marchRepository.findById(req.targetId()).orElse(null);
        if (enemy != null && !canIntercept(playerId, cityScope.slot(playerId), enemy))
            throw new IllegalArgumentException("目标已不再是当前城市的可拦截敌军");
        if (enemy == null && !"incoming_march".equals(req.targetKind())) {
            IncomingMarch legacy = req.targetId() == null ? null : incomingMarchRepository.findById(req.targetId())
                    .filter(im -> playerId.equals(im.getTargetPlayerId()) && "scout".equals(im.getAction())).orElse(null);
            if (legacy != null) enemy = legacyInterceptionTarget(legacy, city.getCityPosX(), city.getCityPosY(), now - 30_000L);
        }
        if (enemy == null) throw new IllegalArgumentException("来袭目标已结束，无法出城拦截");
        Map<String, Integer> army = req.army() == null ? Map.of() : req.army();
        if (army.isEmpty()) throw new IllegalArgumentException("请选择出征部队以计算路线");
        for (var entry : army.entrySet()) {
            int available = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), entry.getKey())
                    .stream().mapToInt(unit -> Objects.requireNonNullElse(unit.getCount(), 0)).sum();
            if (entry.getValue() == null || entry.getValue() <= 0 || entry.getValue() > available)
                throw new IllegalArgumentException("拦截兵力不足或数量无效");
        }
        requireInterceptionUnits(army, "scout".equals(enemy.getAction()));
        Map<String, Integer> equipment = TechnologyRules.actionSnapshot(targets.getCombatTechMap(playerId));
        var plan = planInterception(playerId, army, enemy, equipment, now);
        Map<String, Object> result = new LinkedHashMap<>(plan.preview(now));
        if (Boolean.TRUE.equals(enemy.getAmbushUsed())) result.put("opening", "urgent");
        result.put("oil", calcMarchFuel(army, plan.distance(), 2, equipment));
        result.put("unitEquipment", equipment);
        result.put("unitEquipmentVersion", UnitEquipmentEffects.actionVersion(equipment));
        return result;
    }

    private void requireInterceptionUnits(Map<String, Integer> army, boolean scout) {
        for (String id : army.keySet()) {
            UnitDef unit = UnitDef.UNITS.get(id);
            if (unit == null) throw new IllegalArgumentException("未知兵种: " + id);
            if (scout && !"air".equals(unit.branch()) && !Set.of("armored", "recon_car").contains(id))
                throw new IllegalArgumentException("侦查拦截仅支持空军或装甲车");
        }
    }

    private March legacyInterceptionTarget(IncomingMarch incoming, int cityX, int cityY, long start) {
        March enemy = new March();
        enemy.setFromX(Objects.requireNonNullElse(incoming.getFromX(), cityX - 30));
        enemy.setFromY(Objects.requireNonNullElse(incoming.getFromY(), cityY - 30));
        enemy.setTargetX(cityX); enemy.setTargetY(cityY); enemy.setStartAt(start);
        enemy.setArriveAt(incoming.getArriveAt()); enemy.setAction(incoming.getAction());
        enemy.setAmbushUsed(incoming.getAmbushUsed());
        return enemy;
    }

    private InterceptionPlanner.Plan planInterception(Long playerId, Map<String, Integer> army, March enemy,
                                                      Map<String, Integer> equipment, long now) {
        requireInterceptionUnits(army, "scout".equals(enemy.getAction()));
        double slowest = Double.MAX_VALUE;
        for (String id : army.keySet()) {
            UnitDef unit = UnitDef.UNITS.get(id);
            slowest = Math.min(slowest, unit.spd() * getUnitTechSpdMul(playerId, unit.speedCategory())
                    * UnitEquipmentEffects.speed(equipment, id, false));
        }
        var state = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        double boost = state != null ? state.marchSpeedMultiplier(now) : 1;
        var city = cityScope.economy(playerId);
        return InterceptionPlanner.plan(enemy, city.getCityPosX(), city.getCityPosY(),
                slowest * boost / WorldConfig.MARCH_SEC_PER_GRID, now, point -> {
                    WildTile target = new WildTile();
                    target.setX((int) Math.round(point.x())); target.setY((int) Math.round(point.y()));
                    return routes.plan(playerId, target, army, false, "intercept_scout", equipment);
                });
    }

    /** 指令到达时先补算已到期的交火，不能通过晚到的召回或加速跳过既成伏击。 */
    private boolean settleInterceptionBeforeOrder(March march, long now) {
        String army = march.getArmy();
        boolean returning = Boolean.TRUE.equals(march.getReturning());
        processInterceptionQueue(List.of(march), now);
        return !marchRepository.existsById(march.getId()) || !Objects.equals(army, march.getArmy())
                || returning != Boolean.TRUE.equals(march.getReturning());
    }

    /** 双方任一经济 Tick 都先处理城外截击，晚到的 Tick 也不能先把敌军送入城市战斗。 */
    private void processInterceptionQueue(List<March> owned, long now) {
        Set<String> targetIds = new HashSet<>();
        Map<Long, March> pending = new LinkedHashMap<>();
        for (March march : owned) {
            targetIds.add(String.valueOf(march.getId()));
            if (Boolean.TRUE.equals(march.getReturning())) continue;
            if ("intercept_scout".equals(march.getAction())) {
                pending.put(march.getId(), march);
                if ("incoming_march".equals(march.getTargetKind())) targetIds.add(march.getTargetId());
            }
        }
        if (!targetIds.isEmpty()) for (March march : marchRepository.findActiveInterceptions(new ArrayList<>(targetIds)))
            pending.put(march.getId(), march);
        List<March> sorted = new ArrayList<>();
        for (March snapshot : pending.values()) {
            March march = guildSupport.refreshGarrison(snapshot.getId()).orElse(null);
            if (march == null) continue;
            em.refresh(march);
            if (Boolean.TRUE.equals(march.getReturning())) continue;
            if (refreshInterceptionTiming(march)) sorted.add(march);
            else returnInvalidInterception(march, now);
        }
        sorted.sort(Comparator.comparing((March m) -> Objects.requireNonNullElse(m.getArriveAt(), Long.MAX_VALUE)).thenComparing(March::getId));
        for (March snapshot : sorted) {
            if (Objects.requireNonNullElse(snapshot.getArriveAt(), Long.MAX_VALUE) > now) continue;
            March march = guildSupport.refreshGarrison(snapshot.getId()).orElse(null);
            if (march == null) continue;
            em.refresh(march);
            if (Boolean.TRUE.equals(march.getReturning())) continue;
            try (var scope = cityScope.enter(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0))) {
                if (!refreshInterceptionTiming(march)) returnInvalidInterception(march, now);
                else if (march.getArriveAt() <= now) resolveInterception(march.getPlayerId(), march, now);
            }
        }
    }

    /** 加减速只改变同一路线交火时间；敌军早于己方到位或改道后，原设伏不能凭空命中。 */
    private boolean refreshInterceptionTiming(March interceptor) {
        if (interceptor.getInterceptEnemyProgress() == null) return true;
        March enemy;
        if ("incoming_march".equals(interceptor.getTargetKind())) {
            enemy = guildSupport.refreshGarrison(Long.valueOf(interceptor.getTargetId())).orElse(null);
            if (enemy != null) em.refresh(enemy);
            if (enemy == null || !canIntercept(interceptor.getPlayerId(), interceptor.getCitySlot(), enemy)) return false;
        } else {
            IncomingMarch legacy = incomingMarchRepository.findById(Long.valueOf(interceptor.getTargetId())).orElse(null);
            if (legacy == null || !interceptor.getPlayerId().equals(legacy.getTargetPlayerId())) return false;
            enemy = legacyInterceptionTarget(legacy, interceptor.getFromX(), interceptor.getFromY(), interceptor.getInterceptEnemyStartAt());
        }
        if (!InterceptionPlanner.signature(enemy).equals(interceptor.getInterceptRouteSnapshot())) return false;
        long clashAt = InterceptionPlanner.crossingAt(enemy, interceptor.getInterceptEnemyProgress());
        if (clashAt < interceptor.getInterceptDeployAt() || clashAt >= enemy.getArriveAt()) return false;
        if (!Objects.equals(interceptor.getArriveAt(), clashAt)) {
            interceptor.setArriveAt(clashAt);
            marchRepository.save(interceptor);
            pushService.pushMarchUpdate(interceptor.getPlayerId(), marchEvent("interceptionRescheduled", interceptor, Map.of("arriveAt", clashAt)));
            notifyIncomingChange(interceptor, "rescheduled");
        }
        return true;
    }

    /** 失效设伏按实际已行进比例返城；在交火点等待的时间不能增加回程长度。 */
    private void returnInvalidInterception(March march, long now) {
        try (var scope = cityScope.enter(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0))) {
            long travel = Math.max(1_000L, Objects.requireNonNullElse(march.getInterceptDeployAt(), march.getArriveAt()) - march.getStartAt());
            double fraction = Math.max(0, Math.min(1, (double) (now - march.getStartAt()) / travel));
            long duration = baseMarchDuration(march);
            long back = Math.max(1_000L, Math.round(duration * fraction));
            startReturnMarch(march, now);
            march.setStartAt(now + back - duration); march.setArriveAt(now + back);
            marchRepository.save(march);
            notifyIncomingChange(march, "cancelled");
            pushService.pushMarchUpdate(march.getPlayerId(), marchEvent("interceptCancelled", march, Map.of("message", "拦截目标已离开，部队正在返城")));
        }
    }

    /** 城外截击只作用于本账号城市尚未开战的敌对行军，不能误伤返程、支援或其他城市部队。 */
    private boolean canIntercept(Long playerId, Integer citySlot, March enemy) {
        String action = enemy == null ? null : enemy.getAction();
        boolean attackAction = action == null || "scout".equals(action) || BattleService.isOffensiveAction(action)
                || action.startsWith("tactical");
        if (enemy == null || playerId.equals(enemy.getPlayerId()) || !attackAction
                || !"player".equals(enemy.getTargetKind()) || Boolean.TRUE.equals(enemy.getReturning())
                || enemy.getBattleId() != null) return false;
        try {
            return playerCityRepository.findById(Long.valueOf(enemy.getTargetId()))
                    .filter(city -> playerId.equals(city.getOwnerId())
                            && (citySlot == null || Objects.requireNonNullElse(city.getCitySlot(), 0) == citySlot)).isPresent();
        } catch (NumberFormatException | NullPointerException ignored) {
            return false;
        }
    }

    /**
     * 截击在城外按正式战斗规则结算。来袭方仍是攻方；突围获胜只更新幸存兵力，
     * 保留原行军路线、目标与到达时间，不能套用攻城战后的自动返程。
     */
    private void resolveInterception(Long playerId, March m, long now) {
        Long targetId = null;
        try { targetId = Long.valueOf(m.getTargetId()); } catch (Exception ignored) {}
        March enemyMarch = targetId != null && "incoming_march".equals(m.getTargetKind())
                ? marchRepository.findById(targetId).orElse(null) : null;
        IncomingMarch enemyIncoming = targetId != null && !"incoming_march".equals(m.getTargetKind())
                ? incomingMarchRepository.findById(targetId).orElse(null) : null;
        if (enemyMarch != null) {
            // 与敌方经济 Tick 共用账号锁，并刷新实体，避免重复消灭已返程或已完成的编队。
            accounts.lockPlayer(enemyMarch.getPlayerId());
            enemyMarch = guildSupport.refreshGarrison(enemyMarch.getId()).orElse(null);
            if (enemyMarch != null) em.refresh(enemyMarch);
            if (enemyMarch != null && !canIntercept(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0), enemyMarch)) enemyMarch = null;
        }
        if (enemyIncoming != null && (!playerId.equals(enemyIncoming.getTargetPlayerId())
                || !"scout".equals(enemyIncoming.getAction()))) enemyIncoming = null;
        // Tick 可能晚于约定交汇时间；只允许原定在目标抵城前发生的截击，不能追击已越过交汇点的旧目标。
        long interceptionAt = Objects.requireNonNullElse(m.getArriveAt(), now);
        if (enemyMarch != null && (Objects.requireNonNullElse(enemyMarch.getArriveAt(), 0L) < interceptionAt
                || m.getInterceptDeployAt() != null && Objects.requireNonNullElse(enemyMarch.getArriveAt(), 0L) <= interceptionAt)) enemyMarch = null;
        if (enemyIncoming != null && Objects.requireNonNullElse(enemyIncoming.getArriveAt(), 0L) < interceptionAt) enemyIncoming = null;
        int x = Objects.requireNonNullElse(m.getTargetX(), 0);
        int y = Objects.requireNonNullElse(m.getTargetY(), 0);
        if (enemyMarch == null && enemyIncoming == null) {
            notifyIncomingChange(m, "cancelled");
            startReturnMarch(m, now);
            marchRepository.save(m);
            pushService.pushMarchUpdate(playerId, marchEvent("interceptCancelled", m, Map.of("message", "拦截目标已离开，部队正在返城")));
            return;
        }

        Long attackerId = enemyMarch != null ? enemyMarch.getPlayerId() : null;
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(enemyMarch != null ? enemyMarch.getArmy() : enemyIncoming.getArmy());
        Map<String, Integer> defenderArmy = JsonUtil.parseIntMap(m.getArmy());
        Officer attackerCommander = enemyMarch != null && enemyMarch.getCommanderId() != null
                ? targets.getOfficerById(attackerId, enemyMarch.getCommanderId()) : null;
        Officer defenderCommander = m.getCommanderId() != null ? targets.getOfficerById(playerId, m.getCommanderId()) : null;
        Map<String, Integer> attackerTech = attackerId != null ? new LinkedHashMap<>(targets.getTechMap(attackerId)) : new LinkedHashMap<>();
        if (enemyMarch != null) attackerTech.putAll(JsonUtil.parseIntMap(enemyMarch.getUnitEquipmentSnapshot()));
        Map<String, Integer> defenderTech = new LinkedHashMap<>(targets.getTechMap(playerId));
        defenderTech.putAll(JsonUtil.parseIntMap(m.getUnitEquipmentSnapshot()));
        CommandBonus attackerChief = attackerId != null ? commandBonus(attackerId, Objects.requireNonNullElse(enemyMarch.getCitySlot(), 0)) : CommandBonus.NONE;
        CommandBonus defenderChief = commandBonus(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0));
        EquipmentService.Attributes a = equipmentService.attributes(attackerCommander);
        EquipmentService.Attributes d = equipmentService.attributes(defenderCommander);
        boolean prepared = m.getInterceptReadyAt() != null && m.getInterceptReadyAt() <= interceptionAt;
        boolean ambushAvailable = prepared && !Boolean.TRUE.equals(enemyMarch != null ? enemyMarch.getAmbushUsed() : enemyIncoming.getAmbushUsed());
        BattleResult result = battleService.startWorldDispatch(attackerArmy, defenderArmy, Map.of(),
                attackerTech, defenderTech, targets.getCommanderSkills(attackerCommander), targets.getCommanderSkills(defenderCommander),
                a.military(), a.defense(), d.military(), d.defense(), "intercept_scout", Map.of(), 0, true,
                attackerId != null ? battleActionPreferences.orders(attackerId, false, attackerArmy) : Map.of(),
                battleActionPreferences.orders(playerId, true, defenderArmy), attackerChief, defenderChief, null, BattleScene.PLAYER, ambushAvailable);
        Map<String, Object> opening = new LinkedHashMap<>(Objects.requireNonNullElse(result.getInterceptionOpening(), Map.of()));
        opening.put("prepared", prepared);
        opening.put("deployAt", Objects.requireNonNullElse(m.getInterceptDeployAt(), m.getStartAt()));
        opening.put("clashAt", interceptionAt);
        result.setInterceptionOpening(opening);
        if (Boolean.TRUE.equals(opening.get("ambushConsumed"))) {
            if (enemyMarch != null) { enemyMarch.setAmbushUsed(true); marchRepository.saveAndFlush(enemyMarch); }
            else { enemyIncoming.setAmbushUsed(true); incomingMarchRepository.saveAndFlush(enemyIncoming); }
        }
        recordCommandBonuses(result, attackerChief, defenderChief);
        recordUnitEquipment(result, attackerTech, defenderTech);
        if (attackerId != null) recordBattleWounded(attackerId, enemyMarch, null, null,
                result.getInitialAttacker(), result.getSurvivorAttacker(), attackerCommander, now, result, "攻方");
        recordBattleWounded(playerId, m, null, null, result.getInitialDefender(), result.getSurvivorDefender(),
                defenderCommander, now, result, "守方");
        if (attackerCommander != null && result.getExpGained() > 0) {
            attackerCommander.setExp(Objects.requireNonNullElse(attackerCommander.getExp(), 0L) + result.getExpGained());
            officerRepository.save(attackerCommander);
        }
        pushInterceptionReport(playerId, true, enemyMarch, enemyIncoming, m, result, attackerCommander, defenderCommander, now);
        if (attackerId != null) pushInterceptionReport(attackerId, false, enemyMarch, null, m, result, attackerCommander, defenderCommander, now);

        // 在全歼导致目标行军删除前通知攻方清除急报。
        notifyIncomingChange(m, "resolved");
        if (enemyMarch != null) {
            enemyMarch.setArmy(JsonUtil.toJson(result.getSurvivorAttacker()));
            if (result.getSurvivorAttacker().isEmpty()) {
                marchRepository.delete(enemyMarch);
                if (attackerCommander != null) { attackerCommander.setRole("idle"); officerRepository.save(attackerCommander); }
            }
            else {
                if (!result.isWin()) {
                    long outboundDuration = Math.max(1000L, enemyMarch.getArriveAt() - enemyMarch.getStartAt());
                    double progress = Math.max(0, Math.min(1, (double) (interceptionAt - enemyMarch.getStartAt()) / outboundDuration));
                    long returnDuration = baseMarchDuration(enemyMarch);
                    long returnRemaining = Math.max(1000L, Math.round(returnDuration * progress));
                    try (var ignored = cityScope.enter(attackerId, Objects.requireNonNullElse(enemyMarch.getCitySlot(), 0))) {
                        startReturnMarch(enemyMarch, now);
                    }
                    // 沿原路线从交火时的位置掉头，避免返程标记先跳到原侦查目的地。
                    enemyMarch.setStartAt(now + returnRemaining - returnDuration);
                    enemyMarch.setArriveAt(now + returnRemaining);
                }
                marchRepository.save(enemyMarch);
            }
            notifyIncomingChange(enemyMarch, result.isWin() ? "interceptionFailed" : "intercepted");
            pushService.pushMarchUpdate(attackerId, marchEvent(result.isWin() ? "interceptionPassed" : "intercepted", enemyMarch,
                    Map.of("interceptX", x, "interceptY", y)));
        } else if (result.isWin()) {
            enemyIncoming.setArmy(JsonUtil.toJson(result.getSurvivorAttacker()));
            incomingMarchRepository.save(enemyIncoming);
        } else incomingMarchRepository.delete(enemyIncoming);

        m.setArmy(JsonUtil.toJson(result.getSurvivorDefender()));
        if (result.getSurvivorDefender().isEmpty()) marchRepository.delete(m);
        else { startReturnMarch(m, now); marchRepository.save(m); }
        pushService.pushMarchUpdate(playerId, marchEvent("interceptBattle", m, Map.of("won", !result.isWin(), "interceptX", x, "interceptY", y)));
    }

    /** 双方共享同一场交火快照，各自保存胜负和部队去向；此战不代表攻城或已取得侦查情报。 */
    private void pushInterceptionReport(Long recipientId, boolean defender, March enemy, IncomingMarch incoming,
                                        March interceptor, BattleResult result, Officer attackerCommander, Officer defenderCommander, long now) {
        boolean won = defender ? !result.isWin() : result.isWin();
        String attackerDisposition = result.getSurvivorAttacker().isEmpty() ? "destroyed" : result.isWin() ? "continuing" : "returning";
        String defenderDisposition = result.getSurvivorDefender().isEmpty() ? "destroyed" : "returning";
        String attackerName = enemy != null ? playerRepository.findById(enemy.getPlayerId()).map(PlayerNames::commanderName).orElse("未知敌军") : incoming.getFromName();
        String defenderName = playerRepository.findById(interceptor.getPlayerId()).map(PlayerNames::commanderName).orElse("拦截部队");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "battle");
        report.put("action", "intercept_scout");
        report.put("targetType", "interception");
        report.put("perspective", defender ? "defender" : "attacker");
        report.put("win", won);
        report.put("attackerWin", result.isWin());
        report.put("intercepted", !result.isWin());
        report.put("subject", (defender ? (won ? "拦截成功" : "拦截失败") : (won ? "突围成功" : "突围失败")) + " · 城外截击");
        report.put("time", now);
        report.put("readAt", 0L);
        report.put("citySlot", defender ? interceptor.getCitySlot() : enemy.getCitySlot());
        report.put("marchId", defender ? interceptor.getId() : enemy.getId());
        report.put("attackerName", attackerName);
        report.put("defenderName", defenderName);
        report.put("fromName", attackerName);
        report.put("fromCoord", (enemy != null ? enemy.getFromX() : incoming.getFromX()) + "," + (enemy != null ? enemy.getFromY() : incoming.getFromY()));
        report.put("toName", enemy != null ? enemy.getTargetName() : cityScope.economy(interceptor.getPlayerId()).getCityName());
        report.put("toCoord", (enemy != null ? enemy.getTargetX() : interceptor.getFromX()) + "," + (enemy != null ? enemy.getTargetY() : interceptor.getFromY()));
        report.put("interceptCoord", interceptor.getTargetX() + "," + interceptor.getTargetY());
        report.put("originalAction", enemy != null ? enemy.getAction() : incoming.getAction());
        report.put("interceptionOpening", result.getInterceptionOpening());
        report.put("attackerMarchDisposition", attackerDisposition);
        report.put("defenderMarchDisposition", defenderDisposition);
        report.put("troopDisposition", defender ? defenderDisposition : attackerDisposition);
        report.put("initialAttacker", result.getInitialAttacker());
        report.put("initialDefender", result.getInitialDefender());
        report.put("survivorAttacker", result.getSurvivorAttacker());
        report.put("survivorDefender", result.getSurvivorDefender());
        report.put("losses", defender ? result.getDefenderLosses() : result.getAttackerLosses());
        report.put("recoveredCount", defender ? result.getDefenderRecoveredCount() : result.getAttackerRecoveredCount());
        report.put("recoveryPercent", defender ? result.getDefenderRecoveryPercent() : result.getAttackerRecoveryPercent());
        if (!defender) report.put("exp", attackerCommander != null ? result.getExpGained() : 0);
        Map<String, Object> commanders = new LinkedHashMap<>();
        commanders.put("attacker", buildCommanderReport(attackerCommander));
        commanders.put("defender", buildCommanderReport(defenderCommander));
        report.put("commanders", commanders);
        report.put("commandBonuses", result.getCommandBonuses());
        report.put("unitEquipment", result.getUnitEquipment());
        if (result.getFormationContributions() != null) {
            report.put("formationContributions", result.getFormationContributions());
            report.put("formationEvents", result.getFormationEvents());
            report.put("unitEquipment", Map.of());
        }
        report.put("roundLogs", splitReportLines(result.getReport()));
        ScoutReport saved = new ScoutReport();
        saved.setPlayerId(recipientId);
        saved.setType("battle");
        saved.setTargetName("城外截击");
        saved.setTargetX(interceptor.getTargetX());
        saved.setTargetY(interceptor.getTargetY());
        saved.setCreatedAt(now);
        saved.setReadAt(0L);
        saved.setData(JsonUtil.toJson(report));
        saved = scoutReportRepository.save(saved);
        report.put("id", saved.getId());
        pushService.pushBattleReport(recipientId, report);
    }

    /**
     * 创建一支行军，并在扣除资源和兵力前完成目标、编队与补给校验。
     *
     * @param playerId 发起行军的玩家 ID
     * @param req 出征目标、动作、编队与携带资源
     * @return 已创建的行军记录
     * @throws IllegalArgumentException 当出征条件不满足时抛出
     */
    @Transactional
    public March createDispatch(Long playerId, DispatchRequest req) {
        if (req.mobilizationOrderItem() != null) {
            throw new IllegalArgumentException("动员令已改为仓库启用的24小时账号增益，请刷新页面");
        }
        // 旧接口字段保留用于明确拒绝自动指令，不能让缓存页面或直连接口恢复自动采集/放弃。
        if (req.gatherMode() != null && !"manual".equals(req.gatherMode())) {
            throw new IllegalArgumentException("采集仅支持手动模式，请刷新页面后重试");
        }
        if (req.gatherMode() != null && !"wild_gather".equals(req.targetKind())) {
            throw new IllegalArgumentException("只有采集出征可以设置采集模式");
        }
        if (req.gatherAfterAction() != null && !"retain".equals(req.gatherAfterAction())) {
            throw new IllegalArgumentException("采集后保留野地，请手动放弃领地");
        }
        if ("incoming_scout".equals(req.targetKind()) || "intercept_scout".equals(req.action())) {
            return interceptScout(playerId, req);
        }
        if ("npc".equals(req.targetKind())) throw new IllegalArgumentException("NPC城市/要塞已取消");
        int citySlot = cityScope.slot(playerId);
        int maxMarches = getMaxMarches(playerId, citySlot);
        long activeMarches = marchRepository.countByPlayerIdAndCitySlot(playerId, citySlot);
        if (activeMarches >= maxMarches) {
            throw new IllegalArgumentException("当前城池出征队伍已达上限 (" + activeMarches + "/" + maxMarches + ")，请等待部队返城或升级司令部");
        }
        lockInterceptionParticipants(playerId);
        Player player = accounts.lockPlayer(playerId);
        garrisonFood.beforeChange(playerId);
        NewbieProtection.start(player, GameClock.now());
        Map<String,Integer> actionEquipment = TechnologyRules.actionSnapshot(targets.getCombatTechMap(playerId));
        if (req.unitEquipmentVersion() != null && !req.unitEquipmentVersion().equals(UnitEquipmentEffects.actionVersion(actionEquipment))) throw new IllegalArgumentException("装备配置已更新，请重新预览行军路线");

        String kind = req.targetKind();
        String action = req.action() != null ? req.action() : "conquer";
        boolean isGather = "wild_gather".equals(kind);
        boolean isScout = "scout".equals(action);
        boolean isStation = "station".equals(action);
        if (isStation && !"wild".equals(kind)) throw new IllegalArgumentException("野地进驻只能用于野地目标");
        boolean transfer = "transport".equals(action) || "rebase".equals(action) || "reinforce".equals(action);
        if (!isOffensiveAction(action) && !Set.of("scout", "gather", "transport", "rebase", "station", "reinforce").contains(action)) throw new IllegalArgumentException("无效行军类型");
        boolean pveAttack = Set.of("wild", "bandit", "npc", "simulated_npc").contains(kind)
                && isOffensiveAction(action);
        String battleMode = req.battleMode();
        if (battleMode != null && !Set.of("manual", "auto").contains(battleMode)) throw new IllegalArgumentException("无效战斗方式");
        if (battleMode != null && !pveAttack) throw new IllegalArgumentException("只有对 NPC 或野地的攻击可以选择战斗方式");
        if (pveAttack && battleMode == null) battleMode = "wild".equals(kind) ? "auto" : "manual";
        String conquerMode = req.conquerMode() == null ? "withdraw" : req.conquerMode();
        if (!Set.of("withdraw", "occupy").contains(conquerMode)) throw new IllegalArgumentException("无效征服后操作");
        if (!"wild".equals(kind) || !"conquer".equals(action)) conquerMode = "withdraw";

        // 1. 查找目标
        Object target = targets.findTargetByLongId(kind, req.targetId());
        if (target instanceof PlayerCity pc && pc.getOwnerId() != null) {
            Player owner = accounts.lockPlayer(pc.getOwnerId());
            if (owner.deletionDue(System.currentTimeMillis())) throw new IllegalArgumentException("目标城池已失效");
        }

        if (target == null) throw new IllegalArgumentException("目标不存在");
        if ((isGather || isStation) && target instanceof WildTile wildTarget) {
            target = lockWildTile(String.valueOf(wildTarget.getId()));
            if (target == null) throw new IllegalArgumentException("野地不存在");
        }
        // 对外军事行动包括争夺玩家野地和援军；和平运输、自有城调遣及 PvE 不退出保护。
        boolean playerWarfare = isPlayerWarfare(playerId, target, action);
        if (target instanceof PlayerCity frozenCity && frozenCity.isSurrenderFrozen(GameClock.now())) {
            throw new com.wargame.security.GameAccessException("CITY_FROZEN", "归降移交中的城市拒绝新的行军", 409);
        }
        if ("player".equals(kind) && target instanceof PlayerCity pc && !targets.hasRealOwner(pc)) {
            throw new IllegalArgumentException("该城市为模拟 NPC，请使用 simulated_npc 目标类型");
        }
        if (!isGather && !isStation && targets.isDefeated(target)) throw new IllegalArgumentException("目标已被击败");

        if (transfer) {
            requireTransferTarget(playerId, target, action);
        } else if (target instanceof PlayerCity pc) {
            if (playerId.equals(pc.getOwnerId())) throw new IllegalArgumentException("不能攻击自己的城市");
            if (pc.getReadyAt() > GameClock.now()) throw new IllegalArgumentException("目标城市仍在建设中");
            if ((isOffensiveAction(action) || isScout) && hasActiveShield(pc, GameClock.now())) {
                throw new IllegalArgumentException("目标城市护盾生效，无法侦查或攻击");
            }
            if (isOffensiveAction(action) && !canAttackPlayerCity(playerId, pc, GameClock.now())) {
                var eligibility = warPowerProtection.evaluate(playerId, pc.getOwnerId());
                if (!eligibility.allowed()) throw new IllegalArgumentException(eligibility.reason());
                if (guildRelations.areFriendly(playerId, pc.getOwnerId())) {
                    throw new IllegalArgumentException("友好军团成员之间不能征服或掠夺");
                }
                throw new IllegalArgumentException("请先向目标玩家宣战，或将双方军团设为敌对");
            }
        }
        // 护盾限制当前出发城对其他玩家的交互，不影响自有城调遣及野地、寇出征。
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)
                && !playerId.equals(pc.getOwnerId())
                && cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))
                    .map(cs -> cs.getShieldUntil() != null && cs.getShieldUntil() > GameClock.now()).orElse(false)) {
            throw new IllegalArgumentException("当前城市护盾生效，无法向其他玩家发起操作");
        }
        if (req.commanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, req.commanderId());
            if (officer == null || officer.getCitySlot() != cityScope.slot(playerId)) throw new IllegalArgumentException("军官不在当前城市");
            if ("commander_in_chief".equals(officer.getRole())) {
                throw new IllegalArgumentException("请先解除司令官任命再出征");
            }
            if ("mayor".equals(officer.getRole()) || "commander".equals(officer.getRole())) {
                throw new IllegalArgumentException("请先解除市长或指挥官任命再出征");
            }
            if (marchRepository.existsByPlayerIdAndCommanderId(playerId, req.commanderId())) throw new IllegalArgumentException("军官正在执行行军任务");
        }

        int tx = targets.getTargetX(target);
        int ty = targets.getTargetY(target);

        // 2. 验证并配置兵力
        Map<String, Integer> reqArmy = req.army() != null ? req.army() : Collections.emptyMap();
        if (isScout) ReconRules.requireUnit(reqArmy);
        Map<String, Integer> customArmy = new LinkedHashMap<>();
        double slowestSpd = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : reqArmy.entrySet()) {
            String uid = entry.getKey();
            int n = entry.getValue() != null ? entry.getValue() : 0;
            if (n <= 0) continue;
            UnitDef u = GameData.UNITS.get(uid);
            if (u == null) continue;
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), uid);
            int max = existing.isEmpty() ? 0 : (existing.get(0).getCount() != null ? existing.get(0).getCount() : 0);
            if (n > max) n = max;
            if (n <= 0) continue;
            customArmy.put(uid, n);
            double effectiveSpd = u.spd() * getUnitTechSpdMul(playerId, u.speedCategory()) * UnitEquipmentEffects.speed(actionEquipment, u.key(), false);
            if (effectiveSpd < slowestSpd) slowestSpd = effectiveSpd;
        }
        if (customArmy.isEmpty()) throw new IllegalArgumentException("请至少选择一种兵种出征");
        // 城市间运输只允许后勤载具执行往返；任意其他兵种应使用单程派遣。
        if ("transport".equals(action) && customArmy.keySet().stream().anyMatch(uid -> !"truck".equals(uid) && !"transport".equals(uid))) {
            throw new IllegalArgumentException("运输只能派遣卡车或运输机");
        }

        // 出征编队受当前城市带兵上限约束，不能仅依赖前端拦截。
        long selectedTroops = customArmy.values().stream().mapToLong(Integer::longValue).sum();
        int armyCap = armyService.armyCap(playerId);
        int allowedArmyCap = armyCap;
        if (selectedTroops > allowedArmyCap) {
            throw new IllegalArgumentException("出征兵力超过带兵上限 " + allowedArmyCap + "（当前选择 " + selectedTroops + "）");
        }

        // 2.5 校验野地资源类型
        if (target instanceof WildTile wt2) {
            WildTypeDef wtCheck = WildTypeDef.WILD_TYPES.get(wt2.getType());
            boolean noRes = wtCheck == null || wtCheck.res() == null;
            if (noRes) {
                if (isGather) {
                    throw new IllegalArgumentException("该野地不包含可采集资源");
                }
                if ("wild".equals(kind) && "plunder".equals(action)) {
                    throw new IllegalArgumentException("该野地无可掠夺资源");
                }
            }
            if (isGather && (!Boolean.TRUE.equals(wt2.getOccupied()) || !playerId.equals(wt2.getOccupiedBy()))) {
                throw new IllegalArgumentException("只能采集自己已占领的野地");
            }
            if (isStation) {
                if (!Boolean.TRUE.equals(wt2.getOccupied()) || !playerId.equals(wt2.getOccupiedBy())) {
                    throw new IllegalArgumentException("只能派遣部队进驻已占领的野地");
                }
            } else if ("conquer".equals(action) && Boolean.TRUE.equals(wt2.getOccupied()) && playerId.equals(wt2.getOccupiedBy())) {
                throw new IllegalArgumentException("该野地已被您占领，请使用【派遣】进驻");
            }
            if ("wild".equals(kind) && "conquer".equals(action)) {
                wildOccupation.requireRoom(playerId, cityScope.slot(playerId));
            }
        }

        MarchRouteService.Route route = routes.plan(playerId,target,customArmy,transfer,action,actionEquipment);

        // 3. 按实际路线和最慢兵种计算行军时间及补给。
        int px = java.util.Objects.requireNonNullElse(cityScope.economy(playerId).getCityPosX(), 0);
        int py = java.util.Objects.requireNonNullElse(cityScope.economy(playerId).getCityPosY(), 0);
        double marchDist = route.distance();
        int secPerGrid = WorldConfig.MARCH_SEC_PER_GRID;
        double spd = Math.max(0.1, slowestSpd);
        long now = GameClock.now();
        CityState cs = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        double speedMul = cs != null ? cs.marchSpeedMultiplier(now) : 1.0;
        int marchSec = Math.max(1, (int) Math.ceil((double) marchDist * secPerGrid / (spd * speedMul)));
        // 出征仅预扣油耗；进驻无返程，其他任务预扣往返油耗以保证返程不受库存变化影响。
        int marchFuel = calcMarchFuel(customArmy, marchDist, isStation || "rebase".equals(action) ? 1 : 2, actionEquipment);

        // 4. 验证携带资源与行军油耗，并在出发前一次性扣除。
        Map<String, Integer> carryRes = new LinkedHashMap<>();
        for (String rk : List.of("food", "steel", "oil", "rare", "gold")) carryRes.put(rk, 0);

        if (!isGather && !isScout) {
            if (req.carryRes() != null) {
                for (Map.Entry<String, Integer> entry : req.carryRes().entrySet()) {
                    String rk = entry.getKey();
                    if (carryRes.containsKey(rk)) {
                        carryRes.put(rk, Math.max(0, entry.getValue() != null ? entry.getValue() : 0));
                    }
                }
            }
            int load = Math.min(calcArmyLoad(customArmy, actionEquipment),route.cargoLimit());
            long totalCarry = carryRes.values().stream().mapToLong(Integer::longValue).sum();
            if ("transport".equals(action) && totalCarry == 0) throw new IllegalArgumentException("请填写要运输的物资");
            if (totalCarry > load) throw new IllegalArgumentException("携带资源超出负重上限 " + load);
        }

        Map<String, Integer> totalCost = new LinkedHashMap<>(carryRes);
        totalCost.merge("oil", marchFuel, Integer::sum);
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) throw new IllegalArgumentException("资源仓库不存在，无法准备行军补给");
        for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
            int need = totalCost.getOrDefault(rk, 0);
            long have = getResourceAmount(res, rk);
            if (need > have) {
                throw new IllegalArgumentException("资源不足: " + rk + "（行军油耗和携带物资共需 " + need + "，库存 " + have + "）");
            }
        }
        // 资源、路线和编队验证失败均不消耗保护；成功退出与出征在同一事务中提交。
        if (playerWarfare) {
            NewbieProtection.requireConfirmation(player, req.abandonNewbieProtection(), now);
            if (NewbieProtection.until(player, now) > now) player.setNewbieProtectionEnded(true);
        }
        for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
            deductResource(res, rk, totalCost.getOrDefault(rk, 0));
        }
        resourcesRepository.save(res);

        // 5. 扣除兵力
        for (Map.Entry<String, Integer> entry : customArmy.entrySet()) {
            String uid = entry.getKey();
            int n = entry.getValue();
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), uid);
            if (!existing.isEmpty()) {
                ArmyUnit unit = existing.get(0);
                int current = unit.getCount() != null ? unit.getCount() : 0;
                unit.setCount(Math.max(0, current - n));
                armyUnitRepository.save(unit);
            }
        }

        // 6. 创建行军
        March march = new March();
        march.setPlayerId(playerId);
        march.setCitySlot(cityScope.slot(playerId));
        march.setTargetKind(kind);
        march.setTargetId(String.valueOf(req.targetId()));
        march.setTargetName(targets.getTargetName(target));
        march.setTargetX(tx);
        march.setTargetY(ty);
        march.setFromX(px);
        march.setFromY(py);
        march.setDistance(marchDist);
        march.setRouteMode(route.mode());
        march.setRouteData(JsonUtil.toJson(route.points()));
        march.setAction(action);
        if (transfer) march.setSupportOwnerId(((PlayerCity) target).getOwnerId());
        march.setBattleMode(battleMode);
        march.setConquerMode(conquerMode);
        march.setArmy(JsonUtil.toJson(customArmy));
        march.setUnitEquipmentSnapshot(JsonUtil.toJson(actionEquipment));
        march.setCommanderId(req.commanderId());
        march.setCarryRes(JsonUtil.toJson(carryRes));
        march.setStartAt(now);
        march.setArriveAt(now + marchSec * 1000L);
        march.setBaseDurationMs(marchSec * 1000L);
        march.setReturning(false);

        if (isGather) {
            march.setGatherMode("manual");
            march.setGatherAfterAction("retain");
            march.setGathering(false);
            march.setGatherEndAt(0L);
            march.setGatherAmount(0);
            if (target instanceof WildTile wt) {
                march.setGatherOccupationVersion(wt.getOccupationVersion());
                march.setGatherOwnerCitySlot(wt.getOccupiedCitySlot());
                WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(wt.getType());
                march.setGatherRes(wtDef != null ? wtDef.res() : "food");
            }
        }

        if (req.commanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, req.commanderId());
            officer.setRole("march"); officerRepository.save(officer);
        }
        marchRepository.save(march);
        notifyIncomingChange(march, "started");

        return march;
    }

    private void notifyIncomingChange(March march, String event) {
        if ("intercept_scout".equals(march.getAction()) && "incoming_march".equals(march.getTargetKind())) {
            March intercepted = marchRepository.findById(Long.valueOf(march.getTargetId())).orElse(null);
            if (intercepted != null && !march.getPlayerId().equals(intercepted.getPlayerId())) {
                Player source = playerRepository.findById(march.getPlayerId()).orElse(null);
                Map<String, Object> notification = incomingIntelligence.fieldInterception(march, intercepted, source);
                notification.put("event", event);
                pushService.pushIncomingAttack(intercepted.getPlayerId(), notification);
            }
            return;
        }
        if (!"player".equals(march.getTargetKind())) return;
        Object target = targets.findTargetById("player", march.getTargetId());
        if (!(target instanceof PlayerCity city) || !targets.hasRealOwner(city)
                || march.getPlayerId().equals(city.getOwnerId())) return;
        if (Set.of("transport", "reinforce", "rebase").contains(Objects.requireNonNullElse(march.getAction(), ""))) {
            pushService.pushToPlayer(city.getOwnerId(), "support_update", Map.of("cityId", city.getId()));
            return;
        }
        String fromName = playerRepository.findById(march.getPlayerId())
                .map(PlayerNames::commanderName).orElse("未知敌军");
        Map<String, Object> notification = new LinkedHashMap<>(Map.of(
                "id", "march-" + march.getId(), "event", event, "fromName", fromName));
        if ("accelerated".equals(event) || "stratagemDelayed".equals(event)) {
            notification.put("startAt", march.getStartAt());
            notification.put("arriveAt", march.getArriveAt());
        }
        pushService.pushIncomingAttack(city.getOwnerId(), IncomingIntelligenceService.project(notification, incomingIntelligence.radar(city.getOwnerId())));
    }

    // ========================================================================
    // processIncoming - 对应 JS core.js processIncoming(now)
    // ========================================================================

    @Transactional
    public void processIncoming(Long playerId, long now) {
        lockInterceptionParticipants(playerId);
        processInterceptionQueue(marchRepository.findByPlayerId(playerId), now);
        Player account = playerRepository.lockById(playerId).orElse(null);
        if (account == null || account.deletionDue(System.currentTimeMillis())) return;
        List<IncomingMarch> incoming = incomingMarchRepository.findByTargetPlayerId(playerId);
        if (incoming == null || incoming.isEmpty()) return;

        for (int i = incoming.size() - 1; i >= 0; i--) {
            IncomingMarch im = incoming.get(i);
            long arriveAt = im.getArriveAt() != null ? im.getArriveAt() : 0L;
            if (now < arriveAt) continue;

            // 来袭军到达 -> 自动解析城防战斗
            resolveIncomingBattle(playerId, im, now);
            incomingMarchRepository.delete(im);
        }
    }

    // ========================================================================
    // resolveAttack - 攻击流寇/NPC城/玩家城 (conquer/plunder)
    // ========================================================================

    /** 战斗结束后结算民心、民怨，再判断接管与俘虏；双方战报始终属于原参战玩家。 */
    private void settleTacticalBattle(Long playerId, March m, Object target, long now,
                                      BattleResult result, Officer commander, Officer defenderCommander, String action) {
        String support = m.getBattleId() == null ? null : battleSessionRepository.findById(m.getBattleId())
                .map(BattleSession::getDefenderSupport).orElse(null);
        settleTacticalBattle(playerId, m, target, now, result, commander, defenderCommander, action, support);
    }

    /** 支援损失按冻结编队结算，伤兵回到各派兵人的原城市。 */
    private void settleTacticalBattle(Long playerId, March m, Object target, long now,
                                      BattleResult result, Officer commander, Officer defenderCommander, String action, String support) {
        Long originalDefenderId = target instanceof PlayerCity pc && targets.hasRealOwner(pc) ? pc.getOwnerId() : null;
        if (originalDefenderId != null) {
            // 名额检查与归属转移共用账号锁，防止并发建城或攻下两城超出军衔上限。
            Set<Long> participants = new TreeSet<>(List.of(playerId, originalDefenderId));
            JsonUtil.parseObjMap(support).keySet().forEach(id -> marchRepository.findById(Long.valueOf(id))
                    .ifPresent(sent -> participants.add(sent.getPlayerId())));
            for (Long id : participants) accounts.lockPlayer(id);
            result.setCityConquered(false);
        }
        // 只登记正常完成的真实玩家征服/掠夺，NPC、侦查、撤回及异常终止没有入口。
        BattleSettlement prisonerReceipt = null;
        if (originalDefenderId != null && !originalDefenderId.equals(playerId)
                && Set.of("conquer", "plunder").contains(action)) {
            Optional<BattleSettlement> receipt = prisonerService.begin(m, result.isWin() ? playerId : originalDefenderId, now);
            if (receipt.isEmpty()) return;
            prisonerReceipt = receipt.get();
        }
        // 不取资源的出征不能通过 30 级 NPC 的额外掉落重新获得钻石。
        if (!BattleService.isHarassmentAction(action)) awardNpcDiamond(target, result);
        ranks.awardNpcVictory(playerId, target, result);
        recordBattleWounded(playerId, m, null, null, result.getInitialAttacker(),
                result.getSurvivorAttacker(), commander, now, result, "攻方");
        GuildSupportService.Losses defenseLosses = guildSupport.settle(support, result.getInitialDefender(),
                result.getSurvivorDefender() != null ? result.getSurvivorDefender() : Collections.emptyMap());
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
            recordBattleWounded(pc.getOwnerId(), null, pc.getX(), pc.getY(), defenseLosses.initial(),
                    defenseLosses.survivors(), defenderCommander, now, result, "守方");
        }
        JointDefenseState jointSettlement = m.getBattleId() == null ? null : battleSessionRepository.findById(m.getBattleId())
                .map(s -> JointDefenseState.read(s.getJointDefense())).orElse(null);
        if (jointSettlement != null) {
            setFormationWounded(jointSettlement.getFormations().get("0"), result.getDefenderWounded(), result.getSurvivorDefender());
            setFormationWounded(jointSettlement.getFormations().get("attack"), result.getAttackerWounded(), result.getSurvivorAttacker());
            sampleFormationRecovery(result, jointSettlement, "0", defenderCommander, now);
            sampleFormationRecovery(result, jointSettlement, "attack", commander, now);
        }
        List<PrisonerService.LossSource> defenderSources = new ArrayList<>();
        if (originalDefenderId != null) defenderSources.add(new PrisonerService.LossSource("owner:" + originalDefenderId,
                originalDefenderId, mobileLosses(defenseLosses.initial(), defenseLosses.survivors()),
                Objects.requireNonNullElse(result.getDefenderWounded(), Map.of())));
        defenderSources.addAll(recordSupportWounded(defenseLosses, now, result));
        if (jointSettlement != null) {
            JointDefenseState savedJoint = battleSessionRepository.findById(m.getBattleId()).map(s -> JointDefenseState.read(s.getJointDefense())).orElseThrow();
            savedJoint.getFormations().forEach((id, f) -> { if ("support".equals(f.getKind())) jointSettlement.getFormations().put(id, f); });
            setFormationWounded(jointSettlement.getFormations().get("attack"), result.getAttackerWounded(), result.getSurvivorAttacker());
            result.setFormationContributions(jointSettlement.contributions(result.getSurvivorAttacker(), result.getSurvivorDefender()));
        }
        if (prisonerReceipt != null) {
            List<PrisonerService.LossSource> sources = result.isWin() ? defenderSources : List.of(
                    new PrisonerService.LossSource("attacker:" + playerId, playerId,
                            mobileLosses(result.getInitialAttacker(), result.getSurvivorAttacker()),
                            Objects.requireNonNullElse(result.getAttackerWounded(), Map.of())));
            PlayerCity destination = prisonerService.destination(prisonerReceipt.getPlayerId(), result.isWin() ? m : null,
                    result.isWin() ? null : (PlayerCity) target);
            result.setPrisoners(prisonerService.capture(prisonerReceipt, destination, sources,
                    mobileLosses(result.isWin() ? result.getInitialDefender() : result.getInitialAttacker(),
                            result.isWin() ? result.getSurvivorDefender() : result.getSurvivorAttacker()), result.isWin(), now));
            long accepted = result.getPrisoners() != null
                    ? ((Number) result.getPrisoners().getOrDefault("acceptedTotal", 0)).longValue() : 0;
            if (accepted > 0) {
                result.setReport(result.getReport() + "\n战俘结算：候选 " + result.getPrisoners().get("candidateTotal")
                        + "，收容 " + result.getPrisoners().get("acceptedTotal") + "，未收容 " + result.getPrisoners().get("overflow") + "。\n");
            }
        }
        settleBattleAwards(playerId, m, true, result, action);
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
            settleBattleAwards(pc.getOwnerId(), m, false, result, action);
        }
        if (originalDefenderId != null && result.isWin() && "conquer".equals(action)) {
            PlayerCity city = (PlayerCity) target;
            String reason = cityConquest.unavailableReason(playerId, city);
            result.setCityTakeoverReason(reason);
            result.setCityConquered(reason.isEmpty());
            if (result.isCityConquered()) {
                // 接管时库存随城转移，禁止再把同一份资源装入返城行军。
                result.setPlunderedResources(Collections.emptyMap());
                result.setReport(result.getReport().replace("★ 征服胜利! 夺取敌方全部资源。", "★ 征服胜利! 城内资源随城市接管保留。")
                        + "\n★ 城市民心为0且军衔名额充足，已接管【" + city.getName() + "】。\n");
            } else result.setReport(result.getReport() + "\n未接管城市：" + reason + "。\n");
            Officer captive = cityConquest.capture(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0), originalDefenderId, defenderCommander);
            if (captive != null) {
                result.setCapturedOfficerId(captive.getId()); result.setCapturedOfficerName(captive.getName());
                result.setReport(result.getReport() + "\n★ 俘虏敌方指挥官【" + captive.getName() + "】，已转入我方参谋部，装备与培养数据完整保留。\n");
                try { questService.onEvent(playerId, "CAPTIVE_OFFICER", null, 1); } catch (Exception ignored) {}
            }
        }
        limitBattleLoot(m, result);
        pushBattleReport(playerId, result, m, commander, defenderCommander);
        if (originalDefenderId != null) pushBattleReport(originalDefenderId, result, m, commander, defenderCommander);

        // 参战军官获得经验
        if (commander != null && result.getExpGained() > 0) {
            long curExp = commander.getExp() != null ? commander.getExp() : 0L;
            commander.setExp(curExp + result.getExpGained());
            officerRepository.save(commander);
        }

        // 更新行军部队为幸存者
        Map<String, Integer> survivors = result.getSurvivorAttacker();
        if (survivors == null) survivors = Collections.emptyMap();
        m.setArmy(JsonUtil.toJson(survivors));

        // 拆分守方幸存部队与城防设施
        Map<String, Integer> survivorDefender = result.getSurvivorDefender();
        if (survivorDefender == null) survivorDefender = Collections.emptyMap();
        Map<String, Integer> remainingArmy = new LinkedHashMap<>();
        Map<String, Integer> remainingForts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : survivorDefender.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                if (GameData.FORTS.containsKey(e.getKey())) {
                    remainingForts.put(e.getKey(), e.getValue());
                } else {
                    remainingArmy.put(e.getKey(), e.getValue());
                }
            }
        }

        if (result.isWin()) {
            // 掠夺资源加入携带
            Map<String, Integer> carryRes = new LinkedHashMap<>(JsonUtil.parseIntMap(m.getCarryRes()));
            Map<String, Integer> plunder = result.getPlunderedResources();
            if (plunder != null) {
                for (Map.Entry<String, Integer> e : plunder.entrySet()) {
                    if (e.getValue() != null && e.getValue() > 0) {
                        carryRes.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
            m.setCarryRes(JsonUtil.toJson(carryRes));

            if ("conquer".equals(action) && result.isCityConquered() && (target instanceof NpcCity || target instanceof PlayerCity)) {
                try { questService.onEvent(playerId, "CONQUER_CITY", null, 1); } catch (Exception ignored) {}
            }

            // 标记与更新目标
            if (target instanceof Bandit b) {
                b.setDefeated(true);
                b.setArmy("{}");
                banditRepository.save(b);
                // 第三阶段只认可陆地剿寇胜利；海域守军不推进该任务。
                if (!WorldTerrainService.sea(terrain.current(), b.getX(), b.getY())) {
                    try { questService.onEvent(playerId, "BANDIT_DEFEAT", null, 1); } catch (Exception ignored) {}
                }
            } else if (target instanceof NpcCity nc) {
                // 兼容升级前已经发出的旧城行军，胜利后只退役，不再补城。
                npcCitySpawnService.replace(nc);
            } else if (target instanceof PlayerCity pc) {
                if (targets.hasRealOwner(pc)) {
                    Long defenderId = pc.getOwnerId();
                    // 真实玩家主城：更新防守方部队和城防损失
                    applyDefenderLosses(pc.getOwnerId(), defenseLosses.initial(), defenseLosses.survivors());
                    // 扣除守方被掠夺的资源
                    if (plunder != null && !plunder.isEmpty()) {
                        Resources defRes = resourcesRepository.findByPlayerIdAndCitySlot(pc.getOwnerId(), cityScope.slot(pc.getOwnerId())).orElse(null);
                        if (defRes != null) {
                            for (Map.Entry<String, Integer> e : plunder.entrySet()) {
                                if (e.getValue() != null && e.getValue() > 0) {
                                    deductResource(defRes, e.getKey(), e.getValue());
                                }
                            }
                            resourcesRepository.save(defRes);
                        }
                    }
                    if ("conquer".equals(action)) {
                        pc.setWarEndAt(now + 30 * 60 * 1000L);
                        if (result.isCityConquered()) {
                            cityConquest.takeOver(playerId, pc, now);
                            pushService.pushToPlayer(playerId, "city_ownership", Map.of("cityId", pc.getId(), "gained", true));
                            Player defender = playerRepository.findById(defenderId).orElseThrow();
                            pushService.pushToPlayer(defenderId, "city_ownership", Map.of("cityId", pc.getId(), "gained", false,
                                    "activeCityId", defender.getActiveCityId()));
                            try { questService.onEvent(playerId, "PLAYER_CONQUER", null, 1); } catch (Exception ignored) {}
                        }
                    } else if ("plunder".equals(action)) {
                        try { questService.onEvent(playerId, "PLAYER_PLUNDER", null, 1); } catch (Exception ignored) {}
                    }
                    playerCityRepository.save(pc);
                    // 主线任务进度钩子: 玩家对玩家主城战斗（攻方胜 / 败均算参战）
                    try { questService.onEvent(playerId, "PLAYER_WIN", null, 1); } catch (Exception ignored) {}
                } else {
                    // 模拟玩家城（无真实Owner）
                    if (result.isCityConquered()) {
                        pc.setArmy("{}");
                        pc.setForts("{}");
                        pc.setWarEndAt(now + 30 * 60 * 1000L);
                    } else {
                        pc.setArmy(JsonUtil.toJson(remainingArmy));
                        pc.setForts(JsonUtil.toJson(remainingForts));
                    }
                    playerCityRepository.save(pc);
                }
            }
        } else {
            // 攻方未获胜：守方部队依然承受战斗造成的兵力与城防损失
            if (target instanceof Bandit b) {
                b.setArmy(JsonUtil.toJson(remainingArmy));
                banditRepository.save(b);
            } else if (target instanceof NpcCity nc) {
                nc.setArmy(JsonUtil.toJson(remainingArmy));
                nc.setForts(JsonUtil.toJson(remainingForts));
                npcCityRepository.save(nc);
            } else if (target instanceof PlayerCity pc) {
                if (targets.hasRealOwner(pc)) {
                    applyDefenderLosses(pc.getOwnerId(), defenseLosses.initial(), defenseLosses.survivors());
                } else {
                    pc.setArmy(JsonUtil.toJson(remainingArmy));
                    pc.setForts(JsonUtil.toJson(remainingForts));
                    playerCityRepository.save(pc);
                }
            }
        }

        // 有幸存者则开始返程，否则删除；清空会话标记后行军才可重新由 Tick 处理返程。
        m.setBattleWon(result.isWin());
        m.setBattleId(null);
        if (survivors.isEmpty()) {
            marchRepository.delete(m);
        } else {
            startReturnMarch(m, now);
            marchRepository.save(m);
        }
    }

    /** 陆地和海洋流寇按同等级领取同一资源档位，不受目标地形或旧存档影响。 */
    private Map<String, Integer> banditBattleResources(Bandit bandit) {
        int level = bandit.getLevel() != null ? bandit.getLevel() : 1;
        return WorldConfig.npcReward(level);
    }

    /** 只在 30 级 NPC 胜利结算中抽取一次钻石，且不复用旧存档中的固定掉落。 */
    private void awardNpcDiamond(Object target, BattleResult result) {
        if (!result.isWin()) return;
        boolean eligible = target instanceof NpcCity city && Objects.equals(city.getLevel(), WorldConfig.MAX_NPC_LEVEL)
                || target instanceof Bandit bandit && Objects.equals(bandit.getLevel(), WorldConfig.MAX_NPC_LEVEL);
        if (!eligible) return;
        int diamond = WorldConfig.npcDiamondDrop(WorldConfig.MAX_NPC_LEVEL,
                java.util.concurrent.ThreadLocalRandom.current().nextInt(10));
        Map<String, Integer> plunder = new LinkedHashMap<>(result.getPlunderedResources());
        plunder.remove("diamond");
        if (diamond > 0) {
            plunder.put("diamond", diamond);
            result.setReport(result.getReport() + "\n★ 击败 30 级 NPC，获得钻石 " + diamond + "！\n");
        }
        result.setPlunderedResources(plunder);
    }

    // ========================================================================
    // resolveScout - 侦查 (对应 JS core.js processMarches scout 分支)
    // ========================================================================

    /** 单一侦查兵种仅与同方式驻军交火，情报等级和援军归属仍共用既有规则。 */
    private void resolveScout(Long playerId, March m, Object target, long now) {
        if (target instanceof PlayerCity city) {
            if (!battleSessionRepository.findActivePlayerCityBattles(city.getId().toString()).isEmpty()) return;
            garrisonFood.settle(city, now);
        }
        Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
        String scoutUnit = ReconRules.requireUnit(armyMap);
        String reconMode = ReconRules.mode(scoutUnit);
        int myScouts = armyMap.getOrDefault(scoutUnit, 0);

        GuildSupportService.Defense defense = target instanceof PlayerCity city && targets.hasRealOwner(city)
                ? guildSupport.reconnaissance(city, targets.getTargetArmy(target))
                : defenseForce(target);
        Map<String, Integer> enemyArmy = defense.army();
        int enemyScouts = UnitEquipmentEffects.aggregate(enemyArmy).getOrDefault(scoutUnit, 0);
        Long defenderId = target instanceof PlayerCity pc && targets.hasRealOwner(pc) ? pc.getOwnerId() : null;
        CommandBonus attackerChief = commandBonus(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0));
        CommandBonus defenderChief = target instanceof PlayerCity pc && targets.hasRealOwner(pc)
                ? commandBonus(pc.getOwnerId(), Objects.requireNonNullElse(pc.getCitySlot(), 0)) : CommandBonus.NONE;
        Map<String, Integer> aEquipment = UnitEquipmentEffects.only(targets.getCombatTechMap(playerId));
        Map<String, Integer> dEquipment = new LinkedHashMap<>(defense.equipment());
        aEquipment = UnitEquipmentEffects.forArmy(aEquipment, armyMap);
        Map<String, Integer> interceptArmy = new LinkedHashMap<>();
        enemyArmy.forEach((id, count) -> { if (UnitEquipmentEffects.base(id).equals(scoutUnit)) interceptArmy.put(id, count); });
        dEquipment = UnitEquipmentEffects.forArmy(dEquipment, interceptArmy);
        Map<String, Object> interceptSources = equipmentSources(defense.sources(), interceptArmy);
        // 双方自身侦察技术计入基础先手，守方雷达仅增加第一次交火的先手值。
        int myReconLv = Math.max(0, Math.min(10, targets.getTechLevel(playerId, "recon_level")));
        int defenderReconLv = defenderId == null ? 0 : targets.getTechLevel(defenderId, "recon_level");
        int defenderRadarLv = defenderId == null ? 0 : targets.getTechLevel(defenderId, "recon_radar");
        // “不派遣将领”不借用出发城守将；守方只读取被侦查城市的守将，不能串到主城。
        Officer scoutCommander = m.getCommanderId() != null ? targets.getOfficerById(playerId, m.getCommanderId()) : null;
        Officer interceptCommander = target instanceof PlayerCity pc && targets.hasRealOwner(pc)
                ? targets.getCityCommander(pc) : null;
        if (target instanceof Bandit bandit) {
            interceptCommander = JapaneseOfficers.buildOfficer(bandit.getCommanderName(), Objects.requireNonNullElse(bandit.getLevel(), 1));
        } else if (target instanceof NpcCity city) {
            interceptCommander = JapaneseOfficers.buildOfficer(city.getCommanderName(), Objects.requireNonNullElse(city.getLevel(), 1));
        }
        BattleService.TechCtx attackerRecon = scoutContext(scoutCommander, aEquipment, attackerChief);
        BattleService.TechCtx defenderRecon = scoutContext(interceptCommander, dEquipment, defenderChief);
        UnitEquipmentRecon.Result encounter = UnitEquipmentRecon.resolve(scoutUnit, myScouts, enemyArmy,
                battleService, attackerRecon, defenderRecon, myReconLv, defenderReconLv, defenderRadarLv);
        int myLost = myScouts - encounter.ownAlive();
        int enemyRemain = encounter.enemyAlive().values().stream().mapToInt(Integer::intValue).sum();
        int enemyLost = enemyScouts - enemyRemain;
        double myPower = encounter.ownPower(), enemyPower = encounter.enemyPower();
        List<String> combatLog = encounter.log();
        int attackerInitiative = encounter.attackerInitiative(), defenderInitiative = encounter.defenderInitiative();
        boolean attackerFirst = attackerInitiative >= defenderInitiative;
        String scoutResult;
        boolean showCityInfo = encounter.ownAlive() > 0;
        if (enemyRemain == 0 && showCityInfo) scoutResult = myLost == 0 || enemyPower == 0 || myPower / enemyPower >= 3 ? "overwhelming_victory" : "close_match_win";
        else if (!showCityInfo) scoutResult = enemyLost == 0 || enemyPower / Math.max(1, myPower) >= 3 ? "overwhelming_defeat" : "close_match_loss";
        else scoutResult = "draw";

        // 援军侦查部队损失仍归派兵人，城内部队只扣除分摊到城主的数量。
        Map<String, Integer> survivingDefense = new LinkedHashMap<>(enemyArmy);
        survivingDefense.putAll(encounter.enemyAlive());
        GuildSupportService.Losses scoutLosses = guildSupport.settle(JsonUtil.toJson(defense.support()), enemyArmy, survivingDefense);
        targets.setTargetReconCount(target, scoutUnit, scoutLosses.survivors().getOrDefault(scoutUnit, 0));
        BattleResult supportScoutResult = new BattleResult();
        supportScoutResult.setReport("");
        recordSupportWounded(scoutLosses, now, supportScoutResult);

        // 生成侦查报告
        Map<String, Object> reportData = new LinkedHashMap<>();
        // 报告在抵达野地时生成；新手行动用行军 ID 确认本次侦察已实际返城。
        reportData.put("commandBonuses", Map.of("attacker", attackerChief.toMap(), "defender", defenderChief.toMap()));
        // 快照按真实攻守归属保存；无拦截兵力时不宣称守将参战。
        Map<String, Object> scoutCommanders = new LinkedHashMap<>();
        scoutCommanders.put("attacker", buildCommanderReport(scoutCommander));
        scoutCommanders.put("defender", enemyScouts > 0 ? buildCommanderReport(interceptCommander) : null);
        reportData.put("commanders", scoutCommanders);
        reportData.put("commanderAttributeRounds", List.of(1, 4, 7, 10));
        reportData.put("commanderSkillRounds", List.of(1, 4, 7, 10));
        // 保存本次先手规则与双方科技快照，后续规则变化不能改写历史报告说明。
        Map<String, Object> reconInitiative = Map.of("version", UnitEquipmentRecon.INITIATIVE_RULES_VERSION,
                "attackerReconLevel", myReconLv, "defenderReconLevel", defenderReconLv,
                "defenderRadarLevel", defenderRadarLv);
        reportData.put("reconInitiative", reconInitiative);
        reportData.put("reconIntelligenceVersion", "recon-intelligence-v2-20261019");
        reportData.put("unitEquipment", Map.of("attacker", aEquipment, "defender", dEquipment, "sources", interceptSources));
        reportData.put("marchId", m.getId());
        reportData.put("citySlot", Objects.requireNonNullElse(m.getCitySlot(), 0));
        reportData.put("time", now);
        // enemyArmy在侦查交火前采样；战损另存enemySurvivors，不能把快照误当战后实时驻军。
        reportData.put("sampledAt", now);
        reportData.put("intelligencePhase", "before_encounter");
        reportData.put("attackerName", playerRepository.findById(playerId)
                .map(PlayerNames::commanderName).orElse("我方"));
        reportData.put("targetName", targets.getTargetName(target));
        reportData.put("targetKind", m.getTargetKind());
        reportData.put("x", targets.getTargetX(target));
        reportData.put("y", targets.getTargetY(target));
        if (defenderId != null) reportData.put("targetPlayerId", defenderId);
        reportData.put("level", targets.getTargetLevel(target));
        reportData.put("result", scoutResult);
        reportData.put("showCityInfo", showCityInfo);
        reportData.put("reconMode", reconMode);
        reportData.put("scoutUnit", scoutUnit);
        reportData.put("myArmy", Map.of(scoutUnit, myScouts));
        reportData.put("mySurvivors", Map.of(scoutUnit, myScouts - myLost));
        reportData.put("enemyArmy", Map.of(scoutUnit, enemyScouts));
        reportData.put("enemySurvivors", Map.of(scoutUnit, enemyScouts - enemyLost));
        reportData.put("combatLog", combatLog);
        reportData.put("myScouts", myScouts);
        reportData.put("myLost", myLost);
        reportData.put("enemyScouts", enemyScouts);
        reportData.put("enemyLost", enemyLost);
        reportData.put("reconLevel", myReconLv);
        reportData.put("tierName", getReconTierName(myReconLv));
        reportData.put("attackerInitiative", attackerInitiative);
        reportData.put("defenderInitiative", defenderInitiative);
        reportData.put("firstStrike", attackerFirst ? "attacker" : "defender");

        if (showCityInfo) {
            // 所有级别：基础资源
            reportData.put("resources", targets.getTargetResources(target));

            if (target instanceof WildTile wt) {
                // 野地处理：Lv.0 展示模糊守军，Lv.1+ 展示精确守军
                if (myReconLv >= 1) {
                    reportData.put("army", UnitEquipmentEffects.aggregate(enemyArmy));
                } else {
                    reportData.put("armyVague", buildVagueArmyDesc(enemyArmy));
                }
                wt.setScouted(true);
                wildTileRepository.save(wt);
            } else {
                // 城市处理 (PlayerCity / NpcCity)
                Integer prestige = targets.getTargetPrestige(target);
                if (prestige != null) {
                    reportData.put("prestige", prestige);
                }

                // 情报按本次成功侦查时的等级保存，历史报告不会随科技升级重新解释。
                if (myReconLv < 3) reportData.put("armyVague", buildVagueArmyDesc(enemyArmy));
                if (myReconLv < 1) reportData.put("fortsVague", "敌方防御工事隐蔽在掩体与伪装网下，无法探明");
                if (myReconLv >= 1) {
                    // 工事仅有数量、没有独立等级；城墙等级到Lv.2才开放，Lv.1只告知是否存在。
                    int wallLevel = targets.getTargetBuildingsMap(target).getOrDefault("wall", 0);
                    reportData.put("wallPresent", wallLevel > 0);
                    if (myReconLv >= 2) reportData.put("wallLevel", wallLevel);
                    reportData.put("fortTypes", targets.getTargetForts(target).entrySet().stream()
                            .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).sorted().toList());
                }
                if (myReconLv >= 2) reportData.put("forts", targets.getTargetForts(target));
                if (myReconLv >= 3) reportData.put("army", UnitEquipmentEffects.aggregate(enemyArmy));
                if (myReconLv >= 4) {
                    // 沿用被侦查城市的守将，不能回读账号主城的统帅。
                    reportData.put("commander", interceptCommander == null ? "未任命守将" : interceptCommander.getName());
                    if (interceptCommander != null) {
                        reportData.put("commanderStar", interceptCommander.getStar());
                        reportData.put("commanderLevel", interceptCommander.getLevel());
                    }
                }
                if (myReconLv >= 5) reportData.put("buildings", targets.getTargetBuildingsMap(target));
                if (myReconLv >= 6) reportData.put("officerCount", targets.getTargetOfficerCount(target));
                if (myReconLv >= 7) reportData.put("techs", targets.getTargetTechMap(target));
                if (myReconLv >= 8) {
                    reportData.put("plunderable", targets.calculatePlunderable(target));
                    reportData.put("warehouseProtection", targets.getWarehouseProtection(target));
                }
                if (myReconLv >= 9) reportData.put("officers", targets.getTargetOfficerList(target));
                if (myReconLv >= 10) {
                    long power = calcDefensePower(enemyArmy, targets.getTargetForts(target));
                    reportData.put("defensePower", power);
                    reportData.put("threatLevel", calcThreatLevel(power));
                }
            }
        }

        reportData.put("wounded", woundedService.recordOutboundLosses(playerId, m,
                Map.of(scoutUnit, myScouts), Map.of(scoutUnit, myScouts - myLost), scoutCommander, now));
        saveScoutReport(playerId, target, reportData, now);
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc) && !playerId.equals(pc.getOwnerId())) {
            // 守方仅收到本次来袭及损失信息，不复制出征方的侦查情报。
            Map<String, Object> defenseData = new LinkedHashMap<>();
            defenseData.put("time", now);
            defenseData.put("perspective", "defender");
            defenseData.put("targetKind", "player");
            defenseData.put("targetName", targets.getTargetName(target));
            defenseData.put("x", targets.getTargetX(target));
            defenseData.put("y", targets.getTargetY(target));
            defenseData.put("attackerName", playerRepository.findById(playerId)
                    .map(PlayerNames::commanderName).orElse("未知敌军"));
            defenseData.put("fromX", m.getFromX());
            defenseData.put("fromY", m.getFromY());
            defenseData.put("intercepted", !showCityInfo);
            defenseData.put("result", scoutResult);
            defenseData.put("reconMode", reconMode);
            defenseData.put("scoutUnit", scoutUnit);
            // 守方视角交换初始与幸存兵力；不复制攻方取得的内部情报。
            defenseData.put("myArmy", Map.of(scoutUnit, enemyScouts));
            defenseData.put("mySurvivors", Map.of(scoutUnit, enemyScouts - enemyLost));
            defenseData.put("enemyArmy", Map.of(scoutUnit, myScouts));
            defenseData.put("enemySurvivors", Map.of(scoutUnit, myScouts - myLost));
            defenseData.put("myScouts", enemyScouts);
            defenseData.put("myLost", enemyLost);
            defenseData.put("enemyScouts", myScouts);
            defenseData.put("enemyLost", myLost);
            // 遭遇记录也转换为守方视角，数量与双方兵力快照一致。
            defenseData.put("combatLog", combatLog.stream().map(line -> line.replace("我方", "来袭方")
                    .replace("敌方", "我方").replace("来袭方", "敌方")).toList());
            defenseData.put("unitEquipment", Map.of("attacker", aEquipment, "defender", dEquipment, "sources", interceptSources));
            defenseData.put("commandBonuses", Map.of("attacker", attackerChief.toMap(), "defender", defenderChief.toMap()));
            defenseData.put("commanders", scoutCommanders);
            defenseData.put("commanderAttributeRounds", List.of(1, 4, 7, 10));
            defenseData.put("commanderSkillRounds", List.of(1, 4, 7, 10));
            defenseData.put("reconInitiative", reconInitiative);
            defenseData.put("attackerInitiative", attackerInitiative);
            defenseData.put("defenderInitiative", defenderInitiative);
            defenseData.put("firstStrike", attackerFirst ? "attacker" : "defender");
            defenseData.put("wounded", woundedService.recordLosses(pc.getOwnerId(), pc.getX(), pc.getY(),
                    Map.of(scoutUnit, scoutLosses.initial().getOrDefault(scoutUnit, 0)),
                    Map.of(scoutUnit, scoutLosses.survivors().getOrDefault(scoutUnit, 0)),
                    interceptCommander, now));
            saveScoutReport(pc.getOwnerId(), target, defenseData, now);
        }
        try {
            questService.onEvent(playerId, "SCOUT_COMPLETE", null, 1);
            if (target instanceof WildTile) {
                questService.onEvent(playerId, "SCOUT_WILD", null, 1);
            } else if (target instanceof PlayerCity) {
                questService.onEvent(playerId, "SCOUT_PLAYER", null, 1);
            }
        } catch (Exception ignored) {}
        pushService.pushMarchUpdate(playerId, marchEvent("scoutComplete", m, Collections.emptyMap()));

        // 更新行军部队损失
        armyMap.put(scoutUnit, myScouts - myLost);
        m.setArmy(JsonUtil.toJson(armyMap));

        if (myScouts - myLost > 0) {
            startReturnMarch(m, now);
            marchRepository.save(m);
        } else {
            marchRepository.delete(m);
        }
        if (target instanceof PlayerCity city) garrisonFood.enforce(city, now);
    }

    /** 侦查沿用将领装备和套装的有效军事、防御；上下文不携带普通作战科技。 */
    private BattleService.TechCtx scoutContext(Officer commander, Map<String, Integer> equipment, CommandBonus chief) {
        EquipmentService.Attributes attrs = commander == null ? null : equipmentService.attributes(commander);
        return new BattleService.TechCtx(equipment, targets.getCommanderSkills(commander),
                attrs == null ? 0 : attrs.military(), attrs == null ? 0 : attrs.defense(), chief);
    }

    private void saveScoutReport(Long recipientId, Object target, Map<String, Object> data, long now) {
        ScoutReport report = new ScoutReport();
        report.setPlayerId(recipientId);
        report.setType("scout");
        report.setReadAt(0L);
        report.setTargetX(targets.getTargetX(target));
        report.setTargetY(targets.getTargetY(target));
        report.setTargetName(targets.getTargetName(target));
        report.setData(JsonUtil.toJson(data));
        report.setCreatedAt(now);
        ScoutReport saved = scoutReportRepository.save(report);
        pushService.pushScoutReport(recipientId, Map.of("id", saved.getId(), "type", "scout",
                "time", now, "readAt", 0L, "data", data));
    }

    // ========================================================================
    // resolveIncomingBattle - 来袭军城防战斗
    // ========================================================================

    private void resolveIncomingBattle(Long playerId, IncomingMarch im, long now) {
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(im.getArmy());

        // 玩家防守信息
        GuildSupportService.Defense defense = cityScope.selected(playerId)
                .map(city -> guildSupport.defense(city, targets.getArmyMap(playerId)))
                .orElseGet(() -> new GuildSupportService.Defense(battleActionPreferences.selectSortieArmy(playerId, targets.getArmyMap(playerId)), Map.of(), UnitEquipmentEffects.only(targets.getCombatTechMap(playerId)), Map.of()));
        Map<String, Integer> defenderArmy = defense.army();
        Map<String, Integer> defenderForts = targets.getFortsMap(playerId);
        Map<String, Integer> defenderTech = targets.getTechMap(playerId);
        defenderTech.putAll(defense.equipment());
        defenderTech = UnitEquipmentEffects.forArmy(defenderTech, defenderArmy);
        Officer commander = targets.getCommander(playerId);
        int defenderCommanderMil = 0;
        int defenderCommanderDef = 0;
        if (commander != null) {
            EquipmentService.Attributes dAttrs = equipmentService.attributes(commander);
            defenderCommanderMil = dAttrs.military();
            defenderCommanderDef = dAttrs.defense();
        }
        Map<String, Integer> defenderSkills = targets.getCommanderSkills(commander);

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        Map<String, Integer> defenderResources = new LinkedHashMap<>();
        if (res != null) {
            defenderResources.put("food", (int) Math.min(Integer.MAX_VALUE, res.getFood() != null ? res.getFood() : 0L));
            defenderResources.put("steel", (int) Math.min(Integer.MAX_VALUE, res.getSteel() != null ? res.getSteel() : 0L));
            defenderResources.put("oil", (int) Math.min(Integer.MAX_VALUE, res.getOil() != null ? res.getOil() : 0L));
            defenderResources.put("rare", (int) Math.min(Integer.MAX_VALUE, res.getRare() != null ? res.getRare() : 0L));
            defenderResources.put("gold", (int) Math.min(Integer.MAX_VALUE, res.getGold() != null ? res.getGold() : 0L));
        }
        long defenderWarehouseLevel = targets.buildingLevel(playerId, "depot");

        String action = im.getAction() != null ? im.getAction() : "conquer";

        CommandBonus defenderChief = commandBonus(playerId, cityScope.slot(playerId));
        BattleResult result = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, defenderForts,
                Collections.emptyMap(), defenderTech,
                Collections.emptyMap(), defenderSkills,
                0, 0,
                defenderCommanderMil, defenderCommanderDef,
                action, defenderResources, defenderWarehouseLevel,
                true, Collections.emptyMap(), battleActionPreferences.orders(playerId, true, defenderArmy), CommandBonus.NONE, defenderChief, null, BattleScene.NPC);
        recordCommandBonuses(result, CommandBonus.NONE, defenderChief);
        recordUnitEquipment(result, Map.of(), defenderTech);
        recordEquipmentSources(result, defense.sources());
        GuildSupportService.Losses losses = guildSupport.settle(JsonUtil.toJson(defense.support()), result.getInitialDefender(),
                result.getSurvivorDefender() != null ? result.getSurvivorDefender() : Map.of());
        recordBattleWounded(playerId, null, null, null, losses.initial(),
                losses.survivors(), commander, now, result, "守方");
        recordSupportWounded(losses, now, result);
        settleBattleAwards(playerId, null, false, result, action);
        pushBattleReport(playerId, result, null, null, commander, false, action);

        // 应用守方损失
        applyDefenderLosses(playerId, losses.initial(), losses.survivors());

        // 玩家战败则被掠夺
        if (result.isWin() && res != null) {
            Map<String, Integer> plunder = result.getPlunderedResources();
            if (plunder != null) {
                for (Map.Entry<String, Integer> e : plunder.entrySet()) {
                    if (e.getValue() != null && e.getValue() > 0) {
                        deductResource(res, e.getKey(), e.getValue());
                    }
                }
                resourcesRepository.save(res);
            }
        }


    }

    private Map<String, Integer> recordBattleWounded(Long playerId, March outbound, Integer x, Integer y, Map<String, Integer> initial,
                                     Map<String, Integer> survivors, Officer commander, long now,
                                     BattleResult result, String side) {
        int recoveryPercent = woundedService.recoveryPercent(playerId, commander);
        Map<String, Integer> injured = outbound == null
                ? woundedService.recordLosses(playerId, x, y, initial, survivors, commander, now)
                : woundedService.recordOutboundLosses(playerId, outbound, initial, survivors, commander, now);
        int recoveredCount = injured.values().stream().mapToInt(Integer::intValue).sum();
        int losses = countCombatLosses(initial, survivors);
        if ("攻方".equals(side)) {
            result.setAttackerWounded(new LinkedHashMap<>(injured));
            result.setAttackerRecoveryPercent(recoveryPercent);
            result.setAttackerRecoveredCount(recoveredCount);
            result.setAttackerLosses(losses);
        } else if ("守方".equals(side)) {
            result.setDefenderWounded(new LinkedHashMap<>(injured));
            result.setDefenderRecoveryPercent(recoveryPercent);
            result.setDefenderRecoveredCount(recoveredCount);
            result.setDefenderLosses(losses);
        }
        if (!injured.isEmpty()) {
            StringJoiner names = new StringJoiner("，");
            injured.forEach((unit, count) -> names.add(GameData.UNITS.get(unit).name() + "×" + count));
            result.setReport(result.getReport() + "\n" + side + "伤兵入营：" + names + "（7天内可付费治疗）\n");
        }
        return injured;
    }

    /** 援军伤兵在原城入营，守方战报汇总全军损失和伤兵，并按损失加权回收率。 */
    private List<PrisonerService.LossSource> recordSupportWounded(GuildSupportService.Losses losses, long now, BattleResult result) {
        List<PrisonerService.LossSource> sources = new ArrayList<>();
        Map<String, Integer> allWounded = new LinkedHashMap<>(Objects.requireNonNullElse(result.getDefenderWounded(), Map.of()));
        long recoveryWeight = (long) result.getDefenderRecoveryPercent() * result.getDefenderLosses();
        for (var loss : losses.support()) {
            March stationed = loss.march();
            int recoverySlot = playerCityRepository.findByOwnerIdAndCitySlot(stationed.getPlayerId(), stationed.getCitySlot())
                    .map(PlayerCity::getCitySlot).orElseGet(() -> playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(stationed.getPlayerId()).stream()
                            .map(PlayerCity::getCitySlot).findFirst().orElse(stationed.getCitySlot()));
            try (var ignored = cityScope.enter(stationed.getPlayerId(), recoverySlot)) {
                Officer commander = targets.getOfficerById(stationed.getPlayerId(), stationed.getCommanderId());
                Map<String, Integer> injured = recordBattleWounded(stationed.getPlayerId(), recoverySlot == stationed.getCitySlot() ? stationed : null, null, null,
                        loss.initial(), loss.survivors(), commander, now, result, "援军");
                battleSessionRepository.findActivePlayerCityBattles(stationed.getTargetId()).forEach(session -> {
                    JointDefenseState joint = JointDefenseState.read(session.getJointDefense());
                    if (joint != null && joint.getFormations().containsKey(stationed.getId().toString())) {
                        setFormationWounded(joint.getFormations().get(stationed.getId().toString()), injured, JsonUtil.parseIntMap(session.getDefenderArmy()));
                        sampleFormationRecovery(result, joint, stationed.getId().toString(), commander, now);
                        session.setJointDefense(JsonUtil.toJson(joint));
                    }
                });
                sources.add(new PrisonerService.LossSource("support:" + stationed.getId(), stationed.getPlayerId(),
                        mobileLosses(loss.initial(), loss.survivors()), injured));
                injured.forEach((unit, count) -> allWounded.merge(unit, count, Integer::sum));
                int lost = countCombatLosses(loss.initial(), loss.survivors());
                recoveryWeight += (long) woundedService.recoveryPercent(stationed.getPlayerId(), commander) * lost;
                result.setDefenderLosses(result.getDefenderLosses() + lost);
                result.setDefenderRecoveredCount(result.getDefenderRecoveredCount() + injured.values().stream().mapToInt(Integer::intValue).sum());
                if (commander != null && JsonUtil.parseIntMap(stationed.getArmy()).isEmpty()) {
                    commander.setRole("idle"); officerRepository.save(commander);
                }
            }
            pushService.pushMarchUpdate(stationed.getPlayerId(), marchEvent("supportLosses", stationed, Collections.emptyMap()));
            if (stationed.getSupportOwnerId() != null) {
                pushService.pushToPlayer(stationed.getSupportOwnerId(), "support_update", Map.of("marchId", stationed.getId()));
            }
        }
        if (result.getDefenderLosses() > 0) result.setDefenderRecoveryPercent((int) (recoveryWeight / result.getDefenderLosses()));
        result.setDefenderWounded(allWounded);
        return sources;
    }

    /** 同来源多个配装按实际战损分配终局伤兵，整数余数守恒，不能重复计入来源汇总。 */
    private void setFormationWounded(JointDefenseState.Formation f, Map<String, Integer> wounded, Map<String, Integer> alive) {
        if (f == null || wounded == null) return;
        Map<String, Integer> losses = new LinkedHashMap<>();
        f.getInitial().forEach((unit, n) -> losses.merge(UnitEquipmentEffects.base(unit), Math.max(0, n - alive.getOrDefault(unit, 0)), Integer::sum));
        Map<String, Integer> left = new LinkedHashMap<>(wounded);
        f.getInitial().forEach((unit, n) -> {
            String base = UnitEquipmentEffects.base(unit); int lost = Math.max(0, n - alive.getOrDefault(unit, 0));
            int remaining = losses.getOrDefault(base, 0);
            int amount = remaining == 0 ? 0 : (int) ((long) left.getOrDefault(base, 0) * lost / remaining);
            f.getWounded().put(unit, amount); left.put(base, left.getOrDefault(base, 0) - amount); losses.put(base, remaining - lost);
        });
    }

    /** 在军官释放／转籍前冻结来源医疗配置；零伤兵也留下采样凭据，不回读实时科技重算。 */
    private void sampleFormationRecovery(BattleResult result, JointDefenseState state, String source, Officer officer, long now) {
        var formation = state.getFormations().get(source);
        if (formation == null || formation.getPlayerId() == null) return;
        int slot = "support".equals(formation.getKind()) ? cityScope.slot(formation.getPlayerId()) : Objects.requireNonNullElse(formation.getCitySlot(), 0);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("playerId", formation.getPlayerId()); receipt.put("formationId", state.publicId(formation)); receipt.put("formation", formation.title());
        receipt.put("sampledAt", now); receipt.put("medicalLevel", woundedService.medicalLevel(formation.getPlayerId()));
        receipt.put("medicLevel", targets.getCommanderSkills(officer).getOrDefault("medic", 0));
        receipt.put("recoveryPercent", woundedService.recoveryPercent(formation.getPlayerId(), officer));
        receipt.put("wounded", new LinkedHashMap<>(formation.getWounded())); receipt.put("citySlot", slot);
        playerCityRepository.findByOwnerIdAndCitySlot(formation.getPlayerId(), slot).ifPresent(city -> receipt.put("cityId", city.getId()));
        result.getFormationRecovery().add(receipt);
    }

    /** 医疗回收详情属于资产原主；对手、其他援军与分享凭证只见公开战损及伤兵数量。 */
    private List<Map<String, Object>> ownFormationRecovery(BattleResult result, Long viewer) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var source : result.getFormationRecovery()) if (Objects.equals(viewer, source.get("playerId"))) {
            Map<String, Object> receipt = new LinkedHashMap<>(source); receipt.remove("playerId"); rows.add(receipt);
        }
        return rows;
    }

    /** 可移动单位逐兵种真实战损，设施和未参战库存始终排除。 */
    private Map<String, Integer> mobileLosses(Map<String, Integer> initial, Map<String, Integer> survivors) {
        Map<String, Integer> losses = new TreeMap<>();
        if (initial != null) initial.forEach((unit, count) -> {
            if (GameData.UNITS.containsKey(UnitEquipmentEffects.base(unit))) losses.merge(UnitEquipmentEffects.base(unit), Math.max(0, Objects.requireNonNullElse(count, 0)
                    - (survivors == null ? 0 : survivors.getOrDefault(unit, 0))), Integer::sum);
        });
        return losses;
    }

    /**
     * 结算某一真实玩家在战斗中的声望、民心和民怨，并把结果锁定到当前战报。
     * 声望按双方作战单位的生产成本折算后的净损失计算；胜利方民心上升，守城失败方民心下降，进攻失败不影响出发城民心。
     * 只有征服、掠夺守城战败增加民怨，档位按本次结算扣减前的民心判断，接管前完成写入。
     * 城防损失不进入声望或伤兵计算，声望扣减不会使现有声望低于 0。
     * @param playerId 战报接收者的玩家 ID
     * @param march 当前行军；来袭防守战可能为空
     * @param attacker 该玩家是否为进攻方
     * @param result 已完成的战斗结果
     * @param action 实际出征动作；未知来源来袭也必须传入，旧行军为空时按征服兼容
     */
    private void settleBattleAwards(Long playerId, March march, boolean attacker, BattleResult result, String action) {
        boolean won = attacker == result.isWin();
        int requestedPrestigeChange = battlePrestigeChange(result, attacker);
        int prestigeChange = 0;
        Player player = playerRepository.findById(playerId).orElse(null);
        int prestigeAfter = 0;
        if (player != null) {
            int prestigeBefore = Math.max(0, Objects.requireNonNullElse(player.getPrestige(), 0));
            prestigeAfter = Math.max(0, prestigeBefore + requestedPrestigeChange);
            // 战报记录实际生效的变化值，声望为 0 时不会显示无法扣除的负数。
            prestigeChange = prestigeAfter - prestigeBefore;
            player.setPrestige(prestigeAfter);
            playerRepository.save(player);
        }

        int requestedMoraleChange = attacker && !won ? 0 : (won ? 1 : -2);
        CityEconomy economy = battleEconomy(playerId, march, attacker);
        if (economy != null) {
            int moraleBefore = Objects.requireNonNullElse(economy.getMorale(), 70);
            // 只有玩家城对战和未知来源的来袭战记录民怨；NPC、野地战报不出现无关的民怨零值。
            boolean trackResentment = march == null || "player".equals(march.getTargetKind());
            int resentmentBefore = trackResentment
                    ? Math.max(0, Math.min(100, Objects.requireNonNullElse(economy.getResentment(), 0))) : 0;
            // 先用战前民心定档，避免 71/41 民心在同场扣除 2 点后误落入较低档位。
            int resentmentGain = trackResentment && !attacker && !won
                    ? defeatedCityResentmentGain(Objects.requireNonNullElse(action, "conquer"), moraleBefore) : 0;
            int resentmentAfter = trackResentment ? Math.min(100, resentmentBefore + resentmentGain) : 0;
            int moraleAfter = Math.max(0, Math.min(100, moraleBefore + requestedMoraleChange));
            // 战报记录上下限截断后的实际变化，避免民心已满/归零时显示虚假的增减值。
            int moraleChange = moraleAfter - moraleBefore;
            economy.setMorale(moraleAfter);
            if (trackResentment) economy.setResentment(resentmentAfter);
            saveBattleEconomy(economy);
            if (attacker) {
                result.setAttackerMoraleChange(moraleChange);
                result.setAttackerMoraleAfter(moraleAfter);
                if (trackResentment) {
                    result.setAttackerResentmentChange(resentmentAfter - resentmentBefore);
                    result.setAttackerResentmentAfter(resentmentAfter);
                }
            } else {
                result.setDefenderMoraleChange(moraleChange);
                result.setDefenderMoraleAfter(moraleAfter);
                if (trackResentment) {
                    result.setDefenderResentmentChange(resentmentAfter - resentmentBefore);
                    result.setDefenderResentmentAfter(resentmentAfter);
                }
            }
        }
        if (attacker) {
            result.setAttackerPrestigeChange(prestigeChange);
            result.setAttackerPrestigeAfter(prestigeAfter);
        } else {
            result.setDefenderPrestigeChange(prestigeChange);
            result.setDefenderPrestigeAfter(prestigeAfter);
        }
    }

    /**
     * 按敌我作战单位的生产成本折算值计算本方声望变化。
     * 反坦克火箭车、重坦和舰艇的价值高于步兵，守方变化始终与攻方相反。
     */
    private int battlePrestigeChange(BattleResult result, boolean attacker) {
        int attackerChange = BattlePrestige.attackerNetChange(result.getInitialAttacker(), result.getSurvivorAttacker(),
                result.getInitialDefender(), result.getSurvivorDefender());
        return attacker ? attackerChange : -attackerChange;
    }

    /** 统计可治疗作战单位的损失，城防设施不进入伤兵或声望计算。 */
    private int countCombatLosses(Map<String, Integer> initial, Map<String, Integer> survivors) {
        if (initial == null || initial.isEmpty()) return 0;
        int losses = 0;
        for (Map.Entry<String, Integer> entry : initial.entrySet()) {
            if (!GameData.UNITS.containsKey(UnitEquipmentEffects.base(entry.getKey()))) continue;
            int start = Math.max(0, Objects.requireNonNullElse(entry.getValue(), 0));
            int alive = survivors == null ? 0 : Math.max(0, survivors.getOrDefault(entry.getKey(), 0));
            losses += Math.max(0, start - alive);
        }
        return losses;
    }

    /**
     * 按战前民心计算守城战败的民怨增量；掠夺只影响高民心城市，短战模式均为零。
     * @param action 实际出征动作
     * @param moraleBefore 本场结算扣减前的守城民心
     * @return 未应用民怨上限的增量
     */
    private int defeatedCityResentmentGain(String action, int moraleBefore) {
        if ("conquer".equals(action)) return moraleBefore >= 71 ? 3 : moraleBefore >= 41 ? 2 : 1;
        if ("plunder".equals(action) && moraleBefore >= 71) return 2;
        return 0;
    }

    /** 选择本次战斗实际发生的城市经济体，兼容主城、分城和来袭防守。 */
    private CityEconomy battleEconomy(Long playerId, March march, boolean attacker) {
        if (march != null) {
            Integer x = attacker ? march.getFromX() : march.getTargetX();
            Integer y = attacker ? march.getFromY() : march.getTargetY();
            for (PlayerCity city : playerCityRepository.findByOwnerId(playerId)) {
                if (Objects.equals(city.getX(), x) && Objects.equals(city.getY(), y)) {
                    // 主城民心的权威值在玩家记录中，不能写到地图城的冗余字段上。
                    return Integer.valueOf(0).equals(city.getCitySlot())
                            ? playerRepository.findById(playerId).orElseThrow() : city;
                }
            }
        }
        try {
            return cityScope.economy(playerId);
        } catch (IllegalArgumentException ignored) {
            return playerRepository.findById(playerId).orElse(null);
        }
    }

    /** 按经济体的实际实体类型保存民心、民怨变化。 */
    private void saveBattleEconomy(CityEconomy economy) {
        if (economy instanceof PlayerCity city) playerCityRepository.save(city);
        else if (economy instanceof Player player) playerRepository.save(player);
    }

    private Map<String, Object> marchEvent(String event, March march, Map<String, ?> details) {
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("event", event);
        eventData.put("marchId", march.getId());
        eventData.put("targetName", march.getTargetName());
        eventData.putAll(details);
        return eventData;
    }

    private Map<String, Object> resourceEvent(String resource, int amount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resource", resource);
        details.put("amount", amount);
        return details;
    }

    /**
     * 结算派兵采集对野地储量的消耗；资源入库仍延迟到返城抵达。
     * @param march 已开始采集的派兵采集任务
     */
    private void settleGatherResources(March march) {
        settleGatherResources(march, lockWildTile(march.getTargetId()));
    }

    private void settleGatherResources(March march, WildTile tile) {
        if (!"wild_gather".equals(march.getTargetKind()) || Boolean.TRUE.equals(march.getGatherResourceSettled())) return;
        int requested = Math.max(0, Objects.requireNonNullElse(march.getGatherAmount(), 0));
        if (tile != null && requested > 0 && sameGatherOccupation(march, tile)) {
            int mined = Math.max(0, Objects.requireNonNullElse(tile.getMined(), 0));
            int total = Math.max(mined, Objects.requireNonNullElse(tile.getTotalRes(), mined));
            int remaining = Math.max(0, total - mined);
            int actual = Math.min(requested, remaining);
            march.setGatherAmount(actual);
            int newTotal = Math.max(0, remaining - actual);
            tile.setTotalRes(newTotal);
            tile.setMined(0);
            if (newTotal <= 0 && tile.getDepletedAt() == null) {
                tile.setDepletedAt(GameClock.now());
            }
            wildTileRepository.save(tile);
        }
        // 归属已变化时也要标记已处理，避免返城 Tick 重复尝试修改新归属。
        march.setGatherResourceSettled(true);
    }

    /** 锁定野地并刷新数据库状态，避免用旧一级缓存参与归属判断。 */
    private WildTile lockWildTile(String targetId) {
        if (targetId == null) return null;
        try {
            WildTile tile = wildTileRepository.findById(Long.valueOf(targetId)).orElse(null);
            if (tile != null) em.refresh(tile, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            return tile;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean sameGatherOccupation(March march, WildTile tile) {
        return Boolean.TRUE.equals(tile.getOccupied())
                && Objects.equals(march.getPlayerId(), tile.getOccupiedBy())
                && Objects.equals(march.getGatherOwnerCitySlot(), tile.getOccupiedCitySlot())
                && Objects.equals(march.getGatherOccupationVersion(), tile.getOccupationVersion());
    }

    /** 抵达时归属失效或资源不足只能折返，不能瞬移归队或改变野地归属。 */
    private void returnFailedGather(March march, long now) {
        march.setGatherAmount(0);
        march.setGatherEndAt(0L);
        march.setGatherAfterActionResult("cancelled");
        march.setGatherAfterActionReason("未成功开始采集，部队原路返城");
        startReturnMarch(march, now);
        marchRepository.save(march);
        pushService.pushMarchUpdate(march.getPlayerId(), marchEvent("returning", march, Collections.emptyMap()));
    }

    /**
     * 按战报接收者的攻守身份保存胜负，同时保留原始攻守数据供前端正确映射敌我双方。
     */
    /** 伤兵、被俘、永久损失互斥；容量未收容的候选仍归永久损失。 */
    private Map<String, Object> prisonerBreakdown(Map<String, Integer> initial, Map<String, Integer> survivors,
                                               Map<String, Integer> wounded, BattleResult result, boolean defender) {
        Map<String, Object> breakdown = new TreeMap<>();
        Map<String, Integer> captured = result.isWin() == defender
                ? JsonUtil.parseIntMap(JsonUtil.toJson(result.getPrisoners().get("accepted"))) : Map.of();
        mobileLosses(initial, survivors).forEach((unit, losses) -> {
            int recovered = wounded == null ? 0 : wounded.getOrDefault(unit, 0);
            int captive = captured.getOrDefault(unit, 0);
            breakdown.put(unit, Map.of("losses", losses, "wounded", recovered, "captured", captive,
                    "permanent", Math.max(0, losses - recovered - captive)));
        });
        return breakdown;
    }

    private void pushBattleReport(Long playerId, BattleResult result, March m,
                                  Officer attackerCommander, Officer defenderCommander) {
        pushBattleReport(playerId, result, m, attackerCommander, defenderCommander, false,
                m != null && m.getAction() != null ? m.getAction() : "conquer");
    }

    /** 来袭记录没有对应的出发行军，须显式传入动作，确保战报与民怨结算使用同一规则。 */
    private void pushBattleReport(Long playerId, BattleResult result, March m,
                                  Officer attackerCommander, Officer defenderCommander, boolean wildConquered, String action) {
        long now = GameClock.now();
        String targetName = m != null && m.getTargetName() != null && !m.getTargetName().isBlank()
                ? m.getTargetName() : "目标";
        String targetKind = m != null && m.getTargetKind() != null ? m.getTargetKind() : "target";
        boolean isCityConquered = result.isCityConquered();
        boolean conquered = isCityConquered || wildConquered;
        boolean defenderPerspective = m == null || !playerId.equals(m.getPlayerId());
        boolean attackerWin = result.isWin();
        boolean recipientWin = defenderPerspective ? !attackerWin : attackerWin;
        String perspective = defenderPerspective ? "defender" : "attacker";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "battle");
        report.put("perspective", perspective);
        report.put("citySlot", m != null ? Objects.requireNonNullElse(m.getCitySlot(), 0) : 0);
        report.put("attackerWin", attackerWin);
        if (result.getWall() != null) report.put("wall", result.getWall());
        if (result.getTerminalReason() != null) report.put("terminalReason", result.getTerminalReason());
        report.put("win", recipientWin);
        report.put("readAt", 0L);
        report.put("time", now);
        report.put("subject", defenderPerspective
                ? buildDefenseBattleSubject(recipientWin, action, targetName)
                : buildBattleSubject(attackerWin, conquered, action, targetKind, targetName));
        report.put("targetType", targetKind);
        report.put("action", action);
        report.put("attackerName", m != null ? playerRepository.findById(m.getPlayerId())
                .map(PlayerNames::commanderName).orElse("未知敌军") : "未知敌军");
        // 出发地始终属于进攻方，防守方收到的战报不能误写成自己的城市。
        report.put("fromName", m != null ? playerCityName(m.getPlayerId()) : "未知敌军");
        report.put("fromCoord", (m != null && m.getFromX() != null ? m.getFromX() : 0) + "," + (m != null && m.getFromY() != null ? m.getFromY() : 0));
        report.put("toName", targetName);
        report.put("toCoord", (m != null && m.getTargetX() != null ? m.getTargetX() : 0) + "," + (m != null && m.getTargetY() != null ? m.getTargetY() : 0));
        report.put("survivorAttacker", result.getSurvivorAttacker());
        report.put("survivorDefender", result.getSurvivorDefender());
        report.put("initialAttacker", result.getInitialAttacker());
        report.put("initialDefender", result.getInitialDefender());
        report.put("plunder", result.getPlunderedResources());
        report.put("exp", result.getExpGained());
        report.put("prestigeChange", defenderPerspective ? result.getDefenderPrestigeChange() : result.getAttackerPrestigeChange());
        report.put("prestigeAfter", defenderPerspective ? result.getDefenderPrestigeAfter() : result.getAttackerPrestigeAfter());
        report.put("moraleChange", defenderPerspective ? result.getDefenderMoraleChange() : result.getAttackerMoraleChange());
        report.put("moraleAfter", defenderPerspective ? result.getDefenderMoraleAfter() : result.getAttackerMoraleAfter());
        // 对真实玩家城市的战报同时保留敌方城市民心，NPC/野地和来袭未知城市则为空。
        report.put("enemyMoraleChange", defenderPerspective ? result.getAttackerMoraleChange() : result.getDefenderMoraleChange());
        report.put("enemyMoraleAfter", defenderPerspective ? result.getAttackerMoraleAfter() : result.getDefenderMoraleAfter());
        // 冻结实际民怨增量与战后值；后续安抚、税率变化和城权转移不回写本场快照。
        report.put("resentmentChange", defenderPerspective ? result.getDefenderResentmentChange() : result.getAttackerResentmentChange());
        report.put("resentmentAfter", defenderPerspective ? result.getDefenderResentmentAfter() : result.getAttackerResentmentAfter());
        report.put("enemyResentmentChange", defenderPerspective ? result.getAttackerResentmentChange() : result.getDefenderResentmentChange());
        report.put("enemyResentmentAfter", defenderPerspective ? result.getAttackerResentmentAfter() : result.getDefenderResentmentAfter());
        report.put("losses", defenderPerspective ? result.getDefenderLosses() : result.getAttackerLosses());
        report.put("recoveryPercent", defenderPerspective ? result.getDefenderRecoveryPercent() : result.getAttackerRecoveryPercent());
        report.put("recoveredCount", defenderPerspective ? result.getDefenderRecoveredCount() : result.getAttackerRecoveredCount());
        if (result.getPrisoners() != null) {
            report.put("prisoners", result.getPrisoners());
            report.put("attackerBreakdown", prisonerBreakdown(result.getInitialAttacker(), result.getSurvivorAttacker(),
                    result.getAttackerWounded(), result, false));
            report.put("defenderBreakdown", prisonerBreakdown(result.getInitialDefender(), result.getSurvivorDefender(),
                    result.getDefenderWounded(), result, true));
        }
        report.put("report", result.getReport());
        report.put("cityConquered", isCityConquered);
        report.put("cityTakeoverReason", result.getCityTakeoverReason());
        report.put("capturedOfficerId", result.getCapturedOfficerId());
        report.put("capturedOfficerName", result.getCapturedOfficerName());
        report.put("wildConquered", wildConquered);
        report.put("wildOccupationReason", result.getWildOccupationReason());
        if ("wild".equals(targetKind) && m != null && isOffensiveAction(action)) {
            // 在行军删除或转为驻军前冻结实际去向；历史战报没有此字段时不推测。
            boolean hasSurvivors = result.getSurvivorAttacker() != null
                    && result.getSurvivorAttacker().values().stream().anyMatch(count -> count != null && count > 0);
            report.put("troopDisposition", !hasSurvivors ? "destroyed"
                    : wildConquered && "occupy".equals(m.getConquerMode()) ? "stationed" : "returning");
        }
        report.put("conquered", conquered);
        report.put("roundLogs", splitReportLines(result.getReport()));
        Map<String, Object> commanders = new LinkedHashMap<>();
        commanders.put("attacker", buildCommanderReport(attackerCommander));
        commanders.put("defender", buildCommanderReport(defenderCommander));
        report.put("commanders", commanders);
        report.put("commandBonuses", result.getCommandBonuses());
        report.put("unitEquipment", result.getUnitEquipment());
        if (result.getFormationContributions() != null) {
            report.put("formationContributions", result.getFormationContributions());
            report.put("formationEvents", result.getFormationEvents());
            report.put("formationRecovery", ownFormationRecovery(result, playerId));
            // 主将快照与实际计算共用开战来源；不公开敌军完整配装或私人科技。
            report.put("commanders", result.getCommanders());
            report.put("commandBonuses", Map.of());
            report.put("unitEquipment", Map.of());
        }

        // 持久化到战报表(type=battle)，刷新页面/离线后仍可通过 GET /reports 拉取
        ScoutReport sr = new ScoutReport();
        sr.setPlayerId(playerId);
        sr.setType("battle");
        sr.setTargetX(m != null ? m.getTargetX() : null);
        sr.setTargetY(m != null ? m.getTargetY() : null);
        sr.setTargetName(targetName);
        sr.setCreatedAt(now);
        sr.setReadAt(0L);
        sr.setData(JsonUtil.toJson(report));
        sr = scoutReportRepository.save(sr);
        report.put("id", sr.getId());

        pushService.pushBattleReport(playerId, report);
    }

    /** 读取指定城市的司令官有效属性；技能完全不进入此快照。 */
    private CommandBonus commandBonus(Long playerId, int citySlot) {
        Officer chief = targets.getCommanderInChief(playerId, citySlot);
        if (chief == null) return CommandBonus.NONE;
        EquipmentService.Attributes attrs = equipmentService.attributes(chief);
        return new CommandBonus(chief.getId(), chief.getName(), attrs.military(), attrs.defense(), attrs.logistics(), attrs.knowledge());
    }

    /** 侦查遭遇仅公开参与拦截的编队，不借装备来源信息泄漏其他驻軍。 */
    private Map<String, Object> equipmentSources(Map<String, Object> sources, Map<String, Integer> involved) {
        Map<String, Object> out = new LinkedHashMap<>();
        sources.forEach((id, raw) -> {
            Map<String, Object> source = new LinkedHashMap<>(JsonUtil.parseObjMap(JsonUtil.toJson(raw)));
            Map<String, Integer> army = new LinkedHashMap<>(JsonUtil.parseIntMap(JsonUtil.toJson(source.get("army"))));
            army.entrySet().removeIf(e -> !involved.containsKey(e.getKey()) || e.getValue() <= 0);
            if (!army.isEmpty()) { source.put("army", army); out.put(id, source); }
        });
        return out;
    }

    private void recordEquipmentSources(BattleResult result, Map<String, Object> sources) {
        Map<String, Object> snapshot = new LinkedHashMap<>(result.getUnitEquipment()); snapshot.put("sources", sources);
        result.setUnitEquipment(snapshot);
    }

    private void recordUnitEquipment(BattleResult result, Map<String, Integer> attacker, Map<String, Integer> defender) {
        result.setUnitEquipment(Map.of("attacker", UnitEquipmentEffects.only(UnitEquipmentEffects.forArmy(attacker, result.getInitialAttacker())), "defender", UnitEquipmentEffects.only(UnitEquipmentEffects.forArmy(defender, result.getInitialDefender()))));
    }

    private void recordCommandBonuses(BattleResult result, CommandBonus attacker, CommandBonus defender) {
        result.setCommandBonuses(Map.of("attacker", attacker.toMap(), "defender", defender.toMap()));
    }

    /** 保存结算时的有效属性和技能，历史战报不受后续换装或升级影响。 */
    private Map<String, Object> buildCommanderReport(Officer commander) {
        if (commander == null) return null;
        return CommanderReportSnapshot.from(commander, equipmentService.attributes(commander));
    }

    private String buildBattleSubject(boolean win, boolean conquered, String action, String targetKind, String targetName) {
        String act;
        if (BattleService.isHarassmentAction(action)) {
            act = BattleService.actionName(action);
        } else if ("bandit".equals(targetKind)) {
            act = "剿寇";
        } else if ("npc".equals(targetKind)) {
            act = "攻城";
        } else if ("player".equals(targetKind)) {
            act = "conquer".equals(action) ? "征服" : "掠夺";
        } else if ("wild".equals(targetKind)) {
            act = "conquer".equals(action) ? "占领野地" : "掠夺野地";
        } else {
            act = "出征";
        }
        // NPC 胜利只表示击败目标，与征服奖励标记无关，也不会取得城市归属。
        if ("npc".equals(targetKind) && win) {
            return act + "胜利·已击败 " + targetName;
        }
        if (conquered) {
            // 日寇舰队与陆上据点被击溃后不会产生城市归属，战报应使用击溃语义。
            if ("bandit".equals(targetKind)) {
                return "剿寇胜利·已击溃 " + targetName;
            }
            if ("wild".equals(targetKind)) {
                return "占领野地胜利·已占领 " + targetName;
            }
            return act + "胜利·已征服 " + targetName;
        }
        return (win ? act + "胜利 " : act + "失败 ") + targetName;
    }

    /** 生成守城方视角标题，避免把来袭方的进攻结果描述成我方出征。 */
    private String buildDefenseBattleSubject(boolean win, String action, String targetName) {
        String act = BattleService.actionName(action) + "防守";
        return act + (win ? "胜利 " : "失败 ") + targetName;
    }

    private List<String> splitReportLines(String report) {
        if (report == null || report.isBlank()) return Collections.emptyList();
        String[] lines = report.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) out.add(line.trim());
        }
        return out;
    }

    private String playerCityName(Long playerId) {
        try {
            return playerRepository.findById(playerId)
                    .map(p -> (p.getCityName() != null && !p.getCityName().isBlank()) ? p.getCityName() : PlayerNames.commanderName(p))
                    .orElse("我方");
        } catch (Exception e) {
            return "我方";
        }
    }

    /** 尚未开战的敌军遇盾原路返程，并向双方发送免战邮件。 */
    private boolean returnIfShielded(March march, Object target, long now) {
        if (march.getBattleId() != null || Boolean.TRUE.equals(march.getReturning())
                || !(target instanceof PlayerCity city) || !targets.hasRealOwner(city)
                || Objects.equals(march.getPlayerId(), city.getOwnerId())
                || !(isOffensiveAction(march.getAction()) || "scout".equals(march.getAction()))
                || !hasActiveShield(city, now)) return false;
        String location = city.getName() + "（" + city.getX() + "," + city.getY() + "）";
        try (var ignored = cityScope.enter(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0))) {
            startReturnMarch(march, now);
        }
        march.setReturnReason("shield");
        marchRepository.save(march);
        notifyIncomingChange(march, "shieldBlocked");
        pushService.pushMarchUpdate(march.getPlayerId(), marchEvent("shieldBlocked", march,
                Map.of("message", "目标城市护盾生效，部队正在返程")));
        String attackerName = playerRepository.findById(march.getPlayerId()).map(PlayerNames::commanderName).orElse("敌军");
        String reason = "护盾期间不可发生交火，本次行动已取消，未发生战斗、伤亡或资源掠夺。";
        mailService.sendSystem(march.getPlayerId(), "军事指挥部", "system", "护盾免战：部队原路返程",
                "您的部队已抵达 " + location + "，目标城市的护盾正在生效。" + reason + "部队携带原有物资原路返回出发城市。", List.of());
        mailService.sendSystem(city.getOwnerId(), "军事指挥部", "system", "护盾免战：来袭已拦截",
                attackerName + " 的部队已抵达您的城市 " + location + "，城防护盾成功拦截本次来袭。" + reason + "敌军已原路返回。", List.of());
        return true;
    }

    /** 道具护盾保护所属城市，新手保护覆盖账号所有城市；敌对军团也不能绕过。 */
    private boolean hasActiveShield(PlayerCity city, long now) {
        if (city.getOwnerId() == null || !targets.hasRealOwner(city)) return false;
        if (NewbieProtection.until(playerRepository.findById(city.getOwnerId()).orElse(null), now) > now) return true;
        return cityStateRepository.findByPlayerIdAndCitySlot(city.getOwnerId(), Objects.requireNonNullElse(city.getCitySlot(), 0))
                .map(cs -> cs.getShieldUntil() != null && cs.getShieldUntil() > now)
                .orElse(false);
    }

    /** 按当前归属识别玩家战争，目标变更后也不能让受保护账号意外参战。 */
    private boolean isPlayerWarfare(Long playerId, Object target, String action) {
        return (target instanceof PlayerCity city && targets.hasRealOwner(city)
                && !playerId.equals(city.getOwnerId()) && !"transport".equals(action))
                || (target instanceof WildTile tile && Boolean.TRUE.equals(tile.getOccupied())
                && tile.getOccupiedBy() != null && !playerId.equals(tile.getOccupiedBy()));
    }

    /**
     * 判断征服或掠夺玩家城的即时权限。
     * 实力保护优先于敌对军团；友好军团禁止作战；中立玩家必须处于有效的个人战争期。
     */
    private boolean canAttackPlayerCity(Long attackerId, PlayerCity defenderCity, long now) {
        Long defenderId = defenderCity.getOwnerId();
        if (attackerId.equals(defenderId)) return false;
        if (defenderId == null || !targets.hasRealOwner(defenderCity)) return true;
        if (!warPowerProtection.evaluate(attackerId, defenderId).allowed()) return false;
        if (guildRelations.areHostile(attackerId, defenderId)) return true;
        if (guildRelations.areFriendly(attackerId, defenderId)) return false;
        Player attacker = playerRepository.findById(attackerId).orElse(null);
        if (attacker == null || !defenderId.equals(attacker.getWarAgainstId())) return false;
        long warAt = Objects.requireNonNullElse(attacker.getWarAt(), 0L);
        long warEndAt = Objects.requireNonNullElse(attacker.getWarEndAt(), 0L);
        return warAt <= now && warEndAt > now;
    }

    /** 行军抵达前失去攻击资格时，完整归还部队与随军物资。 */
    private void cancelMarchBeforeBattle(March march, Long playerId) {
        marchRepository.delete(march);
        int originSlot = march.getCitySlot() != null ? march.getCitySlot() : 0;
        try (var ignored = cityScope.enter(playerId, originSlot)) {
            returnArmy(playerId, JsonUtil.parseIntMap(march.getArmy()));
            Map<String, Integer> carryRes = JsonUtil.parseIntMap(march.getCarryRes());
            if (!carryRes.isEmpty()) addResources(playerId, carryRes);
        }
    }

    private boolean isOffensiveAction(String action) {
        return BattleService.isOffensiveAction(action);
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 计算行动负重：冻结的科技与装备相乘；旧行军缺负重键按零级处理。
     */
    public int calcArmyLoad(Map<String, Integer> army) { return calcArmyLoad(army, Map.of()); }

    /**
     * 战利品按战后幸存部队及出发时冻结的科技、装备计算剩余负重。
     * 普通资源共用容量，超载时按可获资源比例分配；钻石为独立特殊掉落，不占物资负重。
     * 必须在战报、装载与守方扣款之前执行，三处使用同一份实际取得数量。
     */
    private void limitBattleLoot(March march, BattleResult result) {
        Map<String, Integer> loot = result.getPlunderedResources();
        if (!result.isWin() || loot == null || loot.isEmpty()) return;
        List<String> resourceKeys = List.of("food", "steel", "oil", "rare", "gold");
        Map<String, Integer> carried = JsonUtil.parseIntMap(march.getCarryRes());
        long occupied = resourceKeys.stream().mapToLong(key -> Math.max(0, carried.getOrDefault(key, 0))).sum();
        int capacity = (int) Math.max(0L, (long) calcArmyLoad(result.getSurvivorAttacker(),
                JsonUtil.parseIntMap(march.getUnitEquipmentSnapshot())) - occupied);
        long available = resourceKeys.stream().mapToLong(key -> Math.max(0, loot.getOrDefault(key, 0))).sum();
        if (available <= capacity) return;

        Map<String, Integer> limited = new LinkedHashMap<>(loot);
        int remaining = capacity;
        for (String key : resourceKeys) {
            int amount = Math.max(0, loot.getOrDefault(key, 0));
            int share = (int) ((long) amount * capacity / available);
            limited.put(key, share);
            remaining -= share;
        }
        // 余数从大到小补齐整数单位，避免逐资源截断造成容量浪费或固定优先掠夺某一种资源。
        List<String> remainderOrder = new ArrayList<>(resourceKeys);
        remainderOrder.sort(Comparator.comparingLong((String key) ->
                (long) Math.max(0, loot.getOrDefault(key, 0)) * capacity % available).reversed());
        for (String key : remainderOrder) {
            if (remaining == 0) break;
            if (limited.get(key) < Math.max(0, loot.getOrDefault(key, 0))) {
                limited.merge(key, 1, Integer::sum);
                remaining--;
            }
        }
        result.setPlunderedResources(limited);
        result.setReport(result.getReport().replace("★ 征服胜利! 夺取敌方全部资源。",
                "★ 征服胜利! 按部队剩余负重装载资源。")
                + "\n战利品负重限制：剩余运力 " + capacity + "，可获普通资源 " + available
                + "，实际装载 " + capacity + "。\n");
    }

    public int calcArmyLoad(Map<String, Integer> army, Map<String, Integer> equipment) {
        if (army == null || army.isEmpty()) return 0;
        double total = 0;
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            UnitDef u = GameData.UNITS.get(entry.getKey());
            if (u != null && u.load() != null && u.load() > 0 && entry.getValue() != null && entry.getValue() > 0) {
                total += (double) entry.getValue() * u.load() * UnitEquipmentEffects.mul(equipment, entry.getKey(), "load");
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(total * TechnologyRules.loadMultiplier(equipment)));
    }

    /**
     * 按单程距离汇总行军油耗，并按任务所需的趟数预扣，使返程不受资源库存变化影响。
     * 保留每 100 格的基础油耗口径，最终扣油为原规则向上取整后的 10 倍。
     * 出征不消耗粮食，粮食库存不会影响部队是否可以出发。
     *
     * @param army 按兵种键聚合的出征数量
     * @param distance 单程直线距离，以地图格边长为单位，保留小数
     * @param legs 需要预扣的行军趟数
     * @return 整数石油需求
     */
    private int calcMarchFuel(Map<String, Integer> army, double distance, int legs) { return calcMarchFuel(army, distance, legs, Map.of()); }

    private int calcMarchFuel(Map<String, Integer> army, double distance, int legs, Map<String, Integer> equipment) {
        double oil = 0;
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            UnitDef unit = GameData.UNITS.get(entry.getKey());
            int count = Math.max(0, entry.getValue() != null ? entry.getValue() : 0);
            if (unit == null || count == 0) continue;
            oil += (double) count * unit.marchOil() * UnitEquipmentEffects.mul(equipment, entry.getKey(), "oil") * Math.max(0, distance) / 100;
        }
        int routeLegs = Math.max(1, legs);
        // 先汇总全部趟数并向上取整，再乘 10，使不足 1 的原始油耗也按原扣油量放大。
        return (int) (Math.ceil(oil * routeLegs) * 10);
    }

    /**
     * 两点间的直线距离，以地图格边长为单位。
     */
    public double dist(int x1, int y1, int x2, int y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    /** 原始单程耗时用于独立返程；旧存档在首次加速前从完整时间轴补齐。 */
    private long baseMarchDuration(March march) {
        if (march.getBaseDurationMs() != null && march.getBaseDurationMs() > 0) return march.getBaseDurationMs();
        return Math.max(1000L, Objects.requireNonNullElse(march.getArriveAt(), 0L)
                - Objects.requireNonNullElse(march.getStartAt(), 0L));
    }

    /** 开始返程并沿用未加速的单程耗时，去程行军令不会自动加速回城。 */
    private void startReturnMarch(March m, long now) {
        if (Boolean.TRUE.equals(m.getStationed())) {
            playerCityRepository.findById(Long.valueOf(m.getTargetId())).ifPresent(city -> garrisonFood.settle(city, now));
        }
        long marchDur = baseMarchDuration(m);
        m.setBaseDurationMs(marchDur);
        // 记录原目标信息用于前端展示, 坐标交换后 originX/Y/targetX/Y 顺序变了
        m.setOriginName(m.getTargetName());
        m.setOriginX(m.getTargetX());
        m.setOriginY(m.getTargetY());
        m.setReturning(true);
        m.setInterceptDeployAt(null);
        m.setInterceptReadyAt(null);
        m.setStationed(false);
        swapCoords(m);
        m.setStartAt(now);
        m.setArriveAt(now + marchDur);
        // 返城的目标名就是玩家主城
        if (m.getPlayerId() != null) {
            m.setTargetName(cityScope.economy(m.getPlayerId()).getCityName());
        }
    }

    public void settleSupportFood(PlayerCity city, long now) { garrisonFood.settle(city, now); }

    /** 接收城时间轴与派兵人 Tick 共用一次性抵达入口，资产交付后才开始目标城驻防计粮。 */
    @Transactional
    public void processSupportArrival(Long marchId, long now) {
        March m = marchRepository.findById(marchId).orElse(null);
        if (m == null || Boolean.TRUE.equals(m.getReturning()) || Boolean.TRUE.equals(m.getStationed()) || m.isDisplaced()
                || m.getArriveAt() > now || !Set.of("transport", "rebase", "reinforce").contains(m.getAction())) return;
        Long playerId = m.getPlayerId();
        try (var sourceScope = cityScope.enter(playerId, Objects.requireNonNullElse(m.getCitySlot(), 0))) {
                PlayerCity destination = playerCityRepository.findById(Long.valueOf(m.getTargetId()))
                        .filter(c -> guildSupport.canReceive(playerId, c,
                                m.getSupportOwnerId() != null ? m.getSupportOwnerId() : playerId, now))
                        .orElse(null);
                // 接收城市可能已失守，其他城市派出的部队与货物应返程，不能阻塞整座城市的Tick。
                if (destination == null) {
                    startReturnMarch(m, now);
                    marchRepository.save(m);
                    pushService.pushMarchUpdate(playerId, marchEvent("transferCancelled", m,
                            Map.of("message", "接收城市已失效，部队与物资正在返程")));
                    return;
                }
                accounts.lockPlayer(destination.getOwnerId());
                em.refresh(destination);
                if ("reinforce".equals(m.getAction())) {
                    garrisonFood.activate(destination, now);
                    if (!guildSupport.canReceive(playerId, destination, m.getSupportOwnerId(), now)
                            || !garrisonFood.mayArrive(destination, JsonUtil.parseIntMap(m.getArmy()), JsonUtil.parseIntMap(m.getCarryRes()))) {
                        startReturnMarch(m, now); marchRepository.save(m);
                        pushService.pushMarchUpdate(playerId, marchEvent("transferCancelled", m, Map.of("message", "接收城未开放驻军或粮食不足，部队返程")));
                        pushService.pushToPlayer(destination.getOwnerId(), "support_update", Map.of("message", "驻军接收失败：未开放接收或粮食不足"));
                        return;
                    }
                }
                try (var ignored = cityScope.enter(destination)) {
                    // 城市间转移是已扣除的自有库存，必须全额入库，不能按生产容量丢弃货物。
                    addResources(destination.getOwnerId(), JsonUtil.parseIntMap(m.getCarryRes()));
                    if ("rebase".equals(m.getAction())) {
                        returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                        Officer officer = targets.getOfficerById(playerId, m.getCommanderId());
                        if (officer != null) { officer.setCitySlot(destination.getCitySlot()); officer.setRole("idle"); officerRepository.save(officer); }
                    }
                }
                m.setCarryRes("{}");
                if ("reinforce".equals(m.getAction())) {
                    m.setGarrisonFoodAt(now);
                    m.setStationed(true);
                    marchRepository.save(m);
                } else if ("rebase".equals(m.getAction())) marchRepository.delete(m);
                else { startReturnMarch(m, now); marchRepository.save(m); }
                pushService.pushMarchUpdate(playerId, marchEvent("transferred", m, Collections.emptyMap()));
                if (!playerId.equals(destination.getOwnerId())) {
                    pushService.pushToPlayer(destination.getOwnerId(), "support_update", Map.of("cityId", destination.getId()));
                }
        }
    }

    /** 原派兵城失守时，正在参战的来源仅保留引用，终局后才能进入待安置／返程。 */
    public void displaceSupportAfterCityLoss(March march, long now) {
        if (Boolean.TRUE.equals(march.getStationed()) && garrisonFood.locked(march,
                battleSessionRepository.findActivePlayerCityBattles(march.getTargetId()))) return;
        if (Boolean.TRUE.equals(march.getStationed())) playerCityRepository.findById(Long.valueOf(march.getTargetId())).ifPresent(c -> garrisonFood.settle(c, now));
        march.setStationed(false); march.setDisplaced(true); march.setReturning(false); marchRepository.save(march);
    }

    /** 缺粮遣返仍执行真实返程，不删除或瞬间加兵；双方收到同一原因通知。 */
    public void returnSupportForSupply(March march, long now) {
        try (var ignored = cityScope.enter(march.getPlayerId(), march.getCitySlot())) { startReturnMarch(march, now); }
        march.setReturnReason("garrison_supply"); marchRepository.save(march);
        pushService.pushMarchUpdate(march.getPlayerId(), marchEvent("supportReturning", march, Map.of("message", "驻防城粮食不足，整队返程")));
        if (march.getSupportOwnerId() != null) pushService.pushToPlayer(march.getSupportOwnerId(), "support_update", Map.of("message", "粮食不足，未参战驻军已按粮耗从高到低遣返"));
    }

    /** 失守后优先原主城，路线不合法时保留待安置资产；后续 Tick 自动重试。 */
    private void recoverDisplacedSupport(March march, long now) {
        List<PlayerCity> owned = playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(march.getPlayerId());
        for (PlayerCity home : owned) {
            if (home.getReadyAt() > now) continue;
            try (var ignored = cityScope.enter(home)) {
                cityScope.assertWritable(march.getPlayerId());
                returnDisplacedTo(march, home, now); return;
            } catch (IllegalArgumentException unavailable) { /* 保留原玩家资产，下一次合法安置再重试。 */ }
        }
    }

    /** 异常安置入口复用自动恢复的路线与库存规则，提交成功才解除待安置状态。 */
    @Transactional
    public void recoverSupport(Long owner, Long marchId, Long cityId) {
        lockInterceptionParticipants(owner);
        March march = marchRepository.findById(marchId).filter(m -> owner.equals(m.getPlayerId()) && m.isDisplaced()
                && "origin_lost".equals(m.getReturnReason())).orElseThrow(() -> new IllegalArgumentException("没有可安置的原城失守驻军"));
        PlayerCity home = cityScope.requireOwned(owner, cityId, true);
        try (var ignored = cityScope.enter(home)) {
            cityScope.assertWritable(owner);
            returnDisplacedTo(march, home, GameClock.now());
        }
    }

    /** 路线验证完成后才重定归属；安置使用真实返程，不瞬间将兵力加到目标库存。 */
    private void returnDisplacedTo(March march, PlayerCity home, long now) {
            Long owner = march.getPlayerId();
            Object departure = targets.findTargetById("player", march.getTargetId());
            if (!(departure instanceof PlayerCity)) throw new IllegalArgumentException("原驻防位置已失效，无法规划返程");
            var loadout = JsonUtil.parseIntMap(march.getUnitEquipmentSnapshot());
            var route = routes.plan(owner, departure, JsonUtil.parseIntMap(march.getArmy()), true, "rebase", loadout);
            march.setCitySlot(home.getCitySlot()); march.setFromX(home.getX()); march.setFromY(home.getY());
            march.setRouteData(JsonUtil.toJson(route.points())); march.setRouteMode(route.mode()); march.setDistance(route.distance());
            double slowest = JsonUtil.parseIntMap(march.getArmy()).keySet().stream().map(GameData.UNITS::get).filter(Objects::nonNull)
                    .mapToDouble(u -> u.spd() * getUnitTechSpdMul(owner, u.speedCategory()) * UnitEquipmentEffects.speed(loadout, u.key(), false)).min().orElse(1);
            march.setBaseDurationMs(Math.max(1L, (long) Math.ceil(route.distance() * WorldConfig.MARCH_SEC_PER_GRID / Math.max(.1, slowest))) * 1000);
            Officer officer = targets.getOfficerById(owner, march.getCommanderId());
            if (officer != null) { officer.setCitySlot(home.getCitySlot()); officerRepository.save(officer); }
            march.setDisplaced(false); startReturnMarch(march, now); marchRepository.save(march);
    }

    /** 交换行军起点和终点坐标 */
    private void swapCoords(March m) {
        if(m.getRouteData()!=null){
            var nodes=JsonUtil.parseTree(m.getRouteData());
            List<Object> reversed=new ArrayList<>();nodes.forEach(reversed::add);Collections.reverse(reversed);
            m.setRouteData(JsonUtil.toJson(reversed));
        }
        Integer origFromX = m.getFromX();
        Integer origFromY = m.getFromY();
        m.setFromX(m.getTargetX());
        m.setFromY(m.getTargetY());
        m.setTargetX(origFromX);
        m.setTargetY(origFromY);
    }

    // ===== 目标查找 =====

    private String getReconTierName(int level) {
        return switch (level) {
            case 0 -> "目视粗探";
            case 1 -> "工事识别";
            case 2 -> "工事详探";
            case 3 -> "守军编制";
            case 4 -> "守将识别";
            case 5 -> "工业设施";
            case 6 -> "驻城军官";
            case 7 -> "电磁与科研";
            case 8 -> "掠夺测算";
            case 9 -> "将领档案";
            default -> "全维评估";
        };
    }

    private String buildVagueArmyDesc(Map<String, Integer> enemyArmy) {
        if (enemyArmy == null || enemyArmy.isEmpty()) {
            return "未探明守军 (防线空虚)";
        }
        int total = 0;
        for (int c : enemyArmy.values()) {
            total += c;
        }
        if (total <= 0) {
            return "未探明守军 (防线空虚)";
        } else if (total < 100) {
            return "小股卫戍部队 (规模 < 100，兵种编制不明)";
        } else if (total < 500) {
            return "中等规模守军 (规模约数百，兵种编制不明)";
        } else if (total < 2000) {
            return "大规模重兵防守 (规模约数千，兵种编制不明)";
        } else {
            return "主力集团军驻守 (极大规模，兵种编制不明)";
        }
    }

    private long calcDefensePower(Map<String, Integer> army, Map<String, Integer> forts) {
        long power = 0;
        if (army != null) {
            for (Map.Entry<String, Integer> e : army.entrySet()) {
                UnitDef u = GameData.UNITS.get(UnitEquipmentEffects.base(e.getKey()));
                if (u != null) {
                    power += (long) Math.round(e.getValue() * (u.peakTroopAttack() + u.def() + u.hp()));
                }
            }
        }
        if (forts != null) {
            for (Map.Entry<String, Integer> e : forts.entrySet()) {
                FortDef f = GameData.FORTS.get(e.getKey());
                if (f != null) {
                    power += (long) e.getValue() * (f.peakTroopAttack() + f.hp());
                }
            }
        }
        return power / 10;
    }

    private String calcThreatLevel(long defensePower) {
        if (defensePower < 500) return "低危 (防守薄弱)";
        if (defensePower < 3000) return "中危 (常态设防)";
        if (defensePower < 15000) return "高危 (坚固防线)";
        return "极危 (铁壁要塞)";
    }

    // ===== 资源/部队操作 =====

    public void returnArmy(Long playerId, Map<String, Integer> army) {
        garrisonFood.beforeChange(playerId);
        if (army == null || army.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) continue;
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), type);
            if (existing.isEmpty()) {
                ArmyUnit unit = new ArmyUnit();
                unit.setPlayerId(playerId);
                unit.setCitySlot(cityScope.slot(playerId));
                unit.setType(type);
                unit.setCount(count);
                armyUnitRepository.save(unit);
            } else {
                ArmyUnit unit = existing.get(0);
                unit.setCount((unit.getCount() != null ? unit.getCount() : 0) + count);
                armyUnitRepository.save(unit);
            }
        }
    }

    /** 行军、运输和其他入账可超过巡检阈值，超限库存由周期巡检分阶段处理。 */
    public void addResources(Long playerId, Map<String, Integer> resMap) {
        if (resMap == null || resMap.isEmpty()) return;
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) {
            res = new Resources();
            res.setPlayerId(playerId);
            res.setCitySlot(cityScope.slot(playerId));
            res.setFood(0);
            res.setSteel(0);
            res.setOil(0);
            res.setRare(0);
            res.setGold(0);
        }
        for (Map.Entry<String, Integer> entry : resMap.entrySet()) {
            int amount = entry.getValue();
            if (amount <= 0) continue;
            if ("diamond".equals(entry.getKey())) {
                Resources wallet = cityScope.wallet(playerId);
                wallet.setDiamond((wallet.getDiamond() != null ? wallet.getDiamond() : 0) + amount);
                resourcesRepository.save(wallet);
            } else {
                res.addResource(entry.getKey(), amount);
            }
        }
        resourcesRepository.save(res);
    }

    private long getResourceAmount(Resources res, String key) {
        return switch (key) {
            case "food" -> Objects.requireNonNullElse(res.getFood(), 0L);
            case "steel" -> Objects.requireNonNullElse(res.getSteel(), 0L);
            case "oil" -> Objects.requireNonNullElse(res.getOil(), 0L);
            case "rare" -> Objects.requireNonNullElse(res.getRare(), 0L);
            case "gold" -> Objects.requireNonNullElse(res.getGold(), 0L);
            default -> 0L;
        };
    }

    private void deductResource(Resources res, String key, int amount) {
        if (amount <= 0) return;
        switch (key) {
            case "food" -> res.setFood(Math.max(0L, (res.getFood() != null ? res.getFood() : 0L) - amount));
            case "steel" -> res.setSteel(Math.max(0L, (res.getSteel() != null ? res.getSteel() : 0L) - amount));
            case "oil" -> res.setOil(Math.max(0L, (res.getOil() != null ? res.getOil() : 0L) - amount));
            case "rare" -> res.setRare(Math.max(0L, (res.getRare() != null ? res.getRare() : 0L) - amount));
            case "gold" -> res.setGold(Math.max(0L, (res.getGold() != null ? res.getGold() : 0L) - amount));
        }
    }

    /** 真实玩家只派出战术预设内的迎战编队，NPC 和野地继续使用原守军。 */
    private GuildSupportService.Defense defenseForce(Object target) {
        Map<String, Integer> available = targets.getTargetArmy(target);
        if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
            try (var ignored = cityScope.enter(city)) {
                return guildSupport.defense(city, available);
            }
        }
        if (target instanceof WildTile tile && tile.getOccupiedBy() != null) {
            return new GuildSupportService.Defense(available, Map.of(), UnitEquipmentEffects.only(targets.getCombatTechMap(tile.getOccupiedBy())), Map.of());
        }
        return new GuildSupportService.Defense(available, Collections.emptyMap());
    }

    /** 调遣只用于自有城市；运输与驻防可面向同军团成员，拒绝跨团友好城市。 */
    private void requireTransferTarget(Long playerId, Object target, String action) {
        if (!(target instanceof PlayerCity city) || !guildSupport.canReceive(playerId, city, null, GameClock.now())) {
            throw new IllegalArgumentException("只能向自己的城市或同军团成员城市运输和驻防");
        }
        if ("reinforce".equals(action) && !garrisonFood.mayArrive(city)) throw new IllegalArgumentException("目标城市未允许接收驻军或粮食不足");
        boolean own = playerId.equals(city.getOwnerId());
        if ("rebase".equals(action) && !own) throw new IllegalArgumentException("调遣仅支持自己的城市，请使用驻防支援军团成员");
        if ("reinforce".equals(action) && own) throw new IllegalArgumentException("请向同军团成员派兵驻防，自己的城市请使用派遣");
        if (own && city.getCitySlot() == cityScope.slot(playerId)) throw new IllegalArgumentException("请选择另一座城市");
    }

    /** 仅扣除实际参战部队的损失，保留未迎战驻军及战斗期间新增的驻军和工事。 */
    private void applyDefenderLosses(Long playerId, Map<String, Integer> initial, Map<String, Integer> survivors) {
        // 更新部队
        List<ArmyUnit> armyUnits = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, ArmyUnit> armyMap = new LinkedHashMap<>();
        for (ArmyUnit au : armyUnits) armyMap.put(au.getType(), au);

        for (String unitType : GameData.UNITS.keySet()) {
            ArmyUnit au = armyMap.get(unitType);
            if (au != null) {
                int losses = Math.max(0, initial.getOrDefault(unitType, 0) - survivors.getOrDefault(unitType, 0));
                au.setCount(Math.max(0, Objects.requireNonNullElse(au.getCount(), 0) - losses));
                armyUnitRepository.save(au);
            }
        }

        // 更新城防
        List<Fortification> forts = fortificationRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, Fortification> fortMap = new LinkedHashMap<>();
        for (Fortification f : forts) fortMap.put(f.getType(), f);

        for (String fortType : GameData.FORTS.keySet()) {
            Fortification f = fortMap.get(fortType);
            if (f != null) {
                int losses = Math.max(0, initial.getOrDefault(fortType, 0) - survivors.getOrDefault(fortType, 0));
                f.setCount(Math.max(0, Objects.requireNonNullElse(f.getCount(), 0) - losses));
                fortificationRepository.save(f);
            }
        }
    }
}
