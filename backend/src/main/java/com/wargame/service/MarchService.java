package com.wargame.service;

import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.BattlePrestige;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.BattleResult;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
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

    private static final long TACTICAL_ROUND_TIMEOUT_MS = 15_000L;

    @org.springframework.beans.factory.annotation.Autowired private AccountService accounts;
    @org.springframework.beans.factory.annotation.Autowired private GuildRelationService guildRelations;
    @org.springframework.beans.factory.annotation.Autowired private ArmyService armyService;
    @org.springframework.beans.factory.annotation.Autowired private BattleActionPreferences battleActionPreferences;

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
    private final WorldMapRepository worldMapRepository;
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
                        WorldMapRepository worldMapRepository,
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
        this.worldMapRepository = worldMapRepository;
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

    @Transactional
    public void processMarches(Long playerId, long now) {
        Player account = playerRepository.lockById(playerId).orElse(null);
        if (account == null || account.deletionDue(System.currentTimeMillis())) return;
        List<March> marches = marchRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        if (marches == null || marches.isEmpty()) return;

        for (int i = marches.size() - 1; i >= 0; i--) {
            March m = marches.get(i);
            boolean gathering = Boolean.TRUE.equals(m.getGathering());
            boolean returning = Boolean.TRUE.equals(m.getReturning());
            long arriveAt = m.getArriveAt() != null ? m.getArriveAt() : 0L;
            long startAt = m.getStartAt() != null ? m.getStartAt() : 0L;
            long gatherEndAt = m.getGatherEndAt() != null ? m.getGatherEndAt() : 0L;

            // 1. 采集完成 -> 开始返程
            if (gathering && !returning && now >= gatherEndAt) {
                m.setGathering(false);
                m.setReturning(true);
                long gatherDur = Math.max(1000L, arriveAt - startAt);
                swapCoords(m);
                m.setStartAt(now);
                m.setArriveAt(now + gatherDur);
                marchRepository.save(m);
                pushService.pushMarchUpdate(playerId, marchEvent("gatherComplete", m,
                        resourceEvent(m.getGatherRes(), m.getGatherAmount() != null ? m.getGatherAmount() : 0)));
                continue;
            }

            // 2. 仍在采集中
            if (gathering && !returning) continue;

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

            // 3a. 采集返程到达 -> 收取资源与掉落晋升珠宝
            if ("wild_gather".equals(targetKind) && returning && gatherEndAt > 0) {
                marchRepository.delete(m);
                WildTile gTile = targets.findWildTileById(m.getTargetId());
                int wildLv = 1;
                if (gTile != null) {
                    int mined = gTile.getMined() != null ? gTile.getMined() : 0;
                    int gatherAmount = m.getGatherAmount() != null ? m.getGatherAmount() : 0;
                    gTile.setMined(mined + gatherAmount);
                    wildTileRepository.save(gTile);
                    if (gTile.getLevel() != null && gTile.getLevel() > 0) {
                        wildLv = gTile.getLevel();
                    }
                }
                String gatherRes = m.getGatherRes();
                int gatherAmount = m.getGatherAmount() != null ? m.getGatherAmount() : 0;
                if (gatherRes != null && gatherAmount > 0) {
                    addResources(playerId, Map.of(gatherRes, gatherAmount));
                }

                // 掉落晋升珠宝
                Map<String, Integer> gemDrops = MilitaryRankDef.rollGatherGems(wildLv);
                for (Map.Entry<String, Integer> ge : gemDrops.entrySet()) {
                    String gKey = ge.getKey();
                    int gCnt = ge.getValue();
                    playerItemRepository.findByPlayerIdAndItemKey(playerId, gKey)
                            .ifPresentOrElse(existing -> {
                                existing.setCount(existing.getCount() + gCnt);
                                existing.setUpdatedAt(System.currentTimeMillis());
                                playerItemRepository.save(existing);
                            }, () -> {
                                PlayerItem newItem = new PlayerItem(null, playerId, gKey, gCnt, System.currentTimeMillis());
                                playerItemRepository.save(newItem);
                            });
                }

                if (gatherAmount > 0 && gatherRes != null) {
                    questService.onEvent(playerId, "GATHER_COMPLETE", gatherRes, 1);
                }
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));

                Map<String, Object> extra = new LinkedHashMap<>(resourceEvent(m.getGatherRes(), gatherAmount));
                if (!gemDrops.isEmpty()) {
                    extra.put("gems", gemDrops);
                }
                pushService.pushMarchUpdate(playerId, marchEvent("returned", m, extra));
                continue;
            }

            // 3b. 采集部队首次到达 -> 开始采集
            if ("wild_gather".equals(targetKind) && !returning && !gathering) {
                WildTile aTile = targets.findWildTileById(m.getTargetId());
                if (aTile == null || !Boolean.TRUE.equals(aTile.getOccupied())) {
                    marchRepository.delete(m);
                    returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                    continue;
                }
                int totalRes = aTile.getTotalRes() != null ? aTile.getTotalRes() : 0;
                int mined = aTile.getMined() != null ? aTile.getMined() : 0;
                int remaining = totalRes - mined;
                if (remaining <= 0) {
                    marchRepository.delete(m);
                    returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                    continue;
                }
                Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
                int load = calcArmyLoad(armyMap);
                int gatherAmount = Math.min(remaining, load);
                m.setGatherAmount(gatherAmount);
                WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(aTile.getType());
                String gatherRes = wtDef != null ? wtDef.res() : null;
                if (gatherRes == null) {
                    // 无资源野地不应出现采集任务，直接回城
                    marchRepository.delete(m);
                    returnArmy(playerId, armyMap);
                    continue;
                }
                m.setGatherRes(gatherRes);
                m.setGathering(true);
                // 采集速度: 基础 10 资源/秒, 叠加本次出征指挥官的 logistics 属性加成
                //   gatherSpeedMul = 1 + logistics / 100 (上限 ×3.0)
                // 同时根据载荷上限: gatherDuration = ceil(gatherAmount / (10 × gatherSpeedMul)) 秒, 最少 60 秒
                double gatherSpeedMul = 1.0;
                Officer gatherCmd = targets.getOfficerById(playerId, m.getCommanderId());
                if (gatherCmd != null) {
                    gatherSpeedMul += equipmentService.attributes(gatherCmd).logistics() / 100.0;
                }
                if (gatherSpeedMul > 3.0) gatherSpeedMul = 3.0;
                long gatherDuration = (long) Math.max(60L, Math.ceil(gatherAmount / (10.0 * gatherSpeedMul))) * 1000;
                m.setGatherEndAt(now + gatherDuration);
                marchRepository.save(m);
                continue;
            }

            // 3b-2. 派遣部队进驻已占领野地
            if ("wild".equals(targetKind) && !returning && "station".equals(m.getAction())) {
                WildTile sTile = targets.findWildTileById(m.getTargetId());
                if (sTile == null || !Boolean.TRUE.equals(sTile.getOccupied()) || !playerId.equals(sTile.getOccupiedBy())) {
                    startReturnMarch(m, now);
                    marchRepository.save(m);
                    continue;
                }
                Map<String, Integer> curGarrison = new LinkedHashMap<>(JsonUtil.parseIntMap(sTile.getGarrison()));
                Map<String, Integer> incomingArmy = JsonUtil.parseIntMap(m.getArmy());
                incomingArmy.forEach((u, count) -> curGarrison.merge(u, count, Integer::sum));
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
                Map<String, Integer> garrison = JsonUtil.parseIntMap(cTile.getGarrison());
                Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
                Officer commander = m.getCommanderId() != null
                        ? targets.getOfficerById(playerId, m.getCommanderId())
                        : targets.getCommander(playerId);
                BattleResult result = battleService.resolveWild(garrison, armyMap);
                recordBattleWounded(playerId, m, null, null, result.getInitialAttacker(),
                        result.getSurvivorAttacker(), commander, now, result, "攻方");
                settleBattleAwards(playerId, m, true, result);
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
                    String wildAction = m.getAction() != null ? m.getAction() : "conquer";
                    if ("plunder".equals(wildAction)) {
                        // 掠夺: 不占领野地, 更新剩余驻军, 根据幸存部队负重掠夺资源
                        cTile.setGarrison(JsonUtil.toJson(survivorGarrison));
                        WildTypeDef wtDefPl = WildTypeDef.WILD_TYPES.get(cTile.getType());
                        String resKey = wtDefPl != null ? wtDefPl.res() : null;
                        if (resKey != null) {
                            int totalRes = cTile.getTotalRes() != null ? cTile.getTotalRes() : 0;
                            int mined = cTile.getMined() != null ? cTile.getMined() : 0;
                            int remaining = totalRes - mined;
                            if (remaining > 0) {
                                int load = calcArmyLoad(result.getSurvivorAttacker());
                                int plunderAmount = Math.min(remaining, load);
                                if (plunderAmount > 0) {
                                    Map<String, Integer> carryRes = new LinkedHashMap<>(JsonUtil.parseIntMap(m.getCarryRes()));
                                    carryRes.merge(resKey, plunderAmount, Integer::sum);
                                    m.setCarryRes(JsonUtil.toJson(carryRes));
                                    cTile.setMined(mined + plunderAmount);
                                    result.setPlunderedResources(Map.of(resKey, plunderAmount));
                                }
                            }
                        }
                        wildTileRepository.save(cTile);
                    } else {
                        // 征服: 占领野地, 清空原驻军
                        cTile.setOccupied(true);
                        cTile.setScouted(true);
                        cTile.setOccupiedBy(playerId);
                        cTile.setGarrison("{}");
                        wildTileRepository.save(cTile);
                        wildConquered = true;
                        // 主线任务进度钩子: 占领野地
                        try { questService.onEvent(playerId, "WILD_CLAIM", null, 1); } catch (Exception ignored) {}
                    }
                } else {
                    // 未全歼守军: 更新野地剩余驻军
                    cTile.setGarrison(JsonUtil.toJson(survivorGarrison));
                    wildTileRepository.save(cTile);
                }
                pushBattleReport(playerId, result, m, commander, null, wildConquered);
                startReturnMarch(m, now);
                marchRepository.save(m);
                continue;
            }

            // 3d. 返城到达 -> 归还部队和携带资源
            if (returning) {
                marchRepository.delete(m);
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                Map<String, Integer> carryRes = JsonUtil.parseIntMap(m.getCarryRes());
                if (!carryRes.isEmpty()) {
                    addResources(playerId, carryRes);
                }
                pushService.pushMarchUpdate(playerId, marchEvent("returned", m, carryRes));
                continue;
            }

            if ("transport".equals(m.getAction()) || "rebase".equals(m.getAction())) {
                PlayerCity destination = cityScope.requireOwned(playerId, Long.valueOf(m.getTargetId()), true);
                try (var ignored = cityScope.enter(destination)) {
                    addResources(playerId, JsonUtil.parseIntMap(m.getCarryRes()));
                    if ("rebase".equals(m.getAction())) {
                        returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                        Officer officer = targets.getOfficerById(playerId, m.getCommanderId());
                        if (officer != null) { officer.setCitySlot(destination.getCitySlot()); officer.setRole("idle"); officerRepository.save(officer); }
                    }
                }
                m.setCarryRes("{}");
                if ("rebase".equals(m.getAction())) marchRepository.delete(m);
                else { startReturnMarch(m, now); marchRepository.save(m); }
                pushService.pushMarchUpdate(playerId, marchEvent("transferred", m, Collections.emptyMap()));
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

            if (target instanceof PlayerCity defenderCity && defenderCity.getOwnerId() != null) {
                Player defender = accounts.lockPlayer(defenderCity.getOwnerId());
                if (defender == null || defender.deletionDue(System.currentTimeMillis())) {
                    startReturnMarch(m, now);
                    marchRepository.save(m);
                    continue;
                }
            }

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

    private void resolveTarget(Long playerId, March march, Object target, long now, String action) {
        if ("scout".equals(action)) {
            resolveScout(playerId, march, target, now);
        } else {
            // 所有攻击行军统一进入战术会话，不能再由旧 conquer/plunder 分支自动打完。
            startTacticalBattle(playerId, march, target, now);
        }
    }

    private void resolveAttack(Long playerId, March m, Object target, long now) {
        Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());

        // 攻方信息
        Map<String, Integer> attackerTech = targets.getTechMap(playerId);
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
                ? targets.getCommander(pc.getOwnerId()) : null;

        // 守方信息
        Map<String, Integer> defenderArmy = targets.getTargetArmy(target);
        Map<String, Integer> defenderForts = targets.getTargetForts(target);
        Map<String, Integer> defenderResources = targets.getTargetResources(target);
        String action = m.getAction() != null ? m.getAction() : "conquer";
        if (action.startsWith("tactical_")) {
            action = action.substring("tactical_".length());
        }

        // 流寇奖励来自 WorldConfig
        if (target instanceof Bandit bandit) {
            int level = bandit.getLevel() != null ? bandit.getLevel() : 1;
            if (level >= 1 && level <= WorldConfig.BANDIT_LEVELS.size()) {
                defenderResources = new LinkedHashMap<>(WorldConfig.BANDIT_LEVELS.get(level - 1).reward());
            }
        }

        boolean isPlayerBattle = (target instanceof PlayerCity);
        Long defenderPlayerId = (target instanceof PlayerCity pc) ? pc.getOwnerId() : null;
        Map<String, Integer> defenderTech = defenderPlayerId != null ? targets.getTechMap(defenderPlayerId) : Collections.emptyMap();
        Map<String, Integer> defenderSkills = targets.getCommanderSkills(defenderCommander);
        int defenderCommanderMil = 0;
        int defenderCommanderDef = 0;
        if (defenderCommander != null) {
            EquipmentService.Attributes dAttrs = equipmentService.attributes(defenderCommander);
            defenderCommanderMil = dAttrs.military();
            defenderCommanderDef = dAttrs.defense();
        } else if (target instanceof NpcCity city) {
            int level = city.getLevel() != null ? city.getLevel() : 1;
            defenderCommanderMil = 50 + level * 8;
            defenderCommanderDef = 50 + level * 8;
        }
        int defenderWallLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "wall") : 0;
        long defenderWarehouseLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "depot") : 0;

        BattleResult result = battleService.startWorldDispatch(
                armyMap, defenderArmy, defenderForts,
                attackerTech, defenderTech,
                attackerSkills, defenderSkills,
                attackerCommanderMil, attackerCommanderDef,
                defenderCommanderMil, defenderCommanderDef,
                0, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel,
                isPlayerBattle);
        settleTacticalBattle(playerId, m, target, now, result, commander, defenderCommander, action);
    }

    /**
     * 将到达目标的攻击行军冻结为战斗会话。同一玩家城按到达时间逐场开战，
     * 不同城市可同时交战；城主账号行锁使各出征方的建会话操作串行化。
     */
    private void startTacticalBattle(Long playerId, March march, Object target, long now) {
        if (march.getBattleId() != null) return;
        if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
            accounts.lockPlayer(city.getOwnerId());
            if (!battleSessionRepository.findActivePlayerCityBattles(march.getTargetId()).isEmpty()) return;
            List<Long> waiting = marchRepository.findWaitingPlayerCityAttackIds(march.getTargetId(), now,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            if (!waiting.isEmpty() && !march.getId().equals(waiting.get(0))) return;
        }
        Map<String, Integer> attackerArmy = new LinkedHashMap<>(JsonUtil.parseIntMap(march.getArmy()));
        Map<String, Integer> defenderArmy = new LinkedHashMap<>(targets.getTargetArmy(target));
        targets.getTargetForts(target).forEach((unit, count) -> {
            if (count != null && count > 0) defenderArmy.merge(unit, count, Integer::sum);
        });
        Officer attackerCommander = march.getCommanderId() != null
                ? targets.getOfficerById(playerId, march.getCommanderId()) : targets.getCommander(playerId);
        Long defenderPlayerId = target instanceof PlayerCity city ? city.getOwnerId() : null;
        Officer defenderCommander = defenderPlayerId != null ? targets.getCommander(defenderPlayerId) : null;
        EquipmentService.Attributes attackerAttrs = attackerCommander != null
                ? equipmentService.attributes(attackerCommander) : new EquipmentService.Attributes(0, 0, 0, 0, List.of(), List.of());
        EquipmentService.Attributes defenderAttrs = defenderCommander != null
                ? equipmentService.attributes(defenderCommander) : new EquipmentService.Attributes(0, 0, 0, 0, List.of(), List.of());
        int defenderMil = defenderAttrs.military();
        int defenderDef = defenderAttrs.defense();
        if (defenderCommander == null && target instanceof NpcCity city) {
            int level = city.getLevel() != null ? city.getLevel() : 1;
            defenderMil = 50 + level * 8;
            defenderDef = 50 + level * 8;
        }
        Map<String, Integer> attackerTech = targets.getTechMap(playerId);
        Map<String, Integer> defenderTech = defenderPlayerId != null ? targets.getTechMap(defenderPlayerId) : Collections.emptyMap();
        Map<String, Integer> attackerSkills = targets.getCommanderSkills(attackerCommander);
        Map<String, Integer> defenderSkills = targets.getCommanderSkills(defenderCommander);
        int wallLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "wall") : 0;
        long warehouseLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "depot") : 0;
        Map<String, Integer> defenderResources = new LinkedHashMap<>(targets.getTargetResources(target));
        if (target instanceof Bandit bandit) {
            int level = bandit.getLevel() != null ? bandit.getLevel() : 1;
            if (level >= 1 && level <= WorldConfig.BANDIT_LEVELS.size()) {
                defenderResources = new LinkedHashMap<>(WorldConfig.BANDIT_LEVELS.get(level - 1).reward());
            }
        }

        int initialDistance = battleService.calcInitialDistance(attackerArmy, defenderArmy,
                new BattleService.TechCtx(attackerTech, attackerSkills, attackerAttrs.military(), attackerAttrs.defense()),
                new BattleService.TechCtx(defenderTech, defenderSkills, defenderMil, defenderDef));
        BattleSession session = new BattleSession();
        session.setPlayerId(playerId);
        session.setMarchId(march.getId());
        session.setTargetKind(march.getTargetKind());
        session.setTargetId(march.getTargetId());
        session.setTargetName(march.getTargetName());
        session.setAction(march.getAction() != null ? march.getAction() : "conquer");
        session.setRoundNo(0);
        session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
        session.setInitialDistance(initialDistance);
        session.setAttackerArmy(JsonUtil.toJson(attackerArmy));
        session.setDefenderArmy(JsonUtil.toJson(defenderArmy));
        session.setInitialAttacker(JsonUtil.toJson(attackerArmy));
        session.setInitialDefender(JsonUtil.toJson(defenderArmy));
        session.setAttackerPositions(JsonUtil.toJson(initialPositions(attackerArmy, 0)));
        session.setDefenderPositions(JsonUtil.toJson(initialPositions(defenderArmy, initialDistance)));
        session.setAttackerTech(JsonUtil.toJson(attackerTech));
        session.setDefenderTech(JsonUtil.toJson(defenderTech));
        session.setAttackerSkills(JsonUtil.toJson(attackerSkills));
        session.setDefenderSkills(JsonUtil.toJson(defenderSkills));
        session.setDefenderResources(JsonUtil.toJson(defenderResources));
        session.setBattleLog("");
        session.setAttackerCommanderMil(attackerAttrs.military());
        session.setAttackerCommanderDef(attackerAttrs.defense());
        session.setDefenderCommanderMil(defenderMil);
        session.setDefenderCommanderDef(defenderDef);
        session.setDefenderWallLevel(wallLevel);
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
    @Transactional
    public Map<String, Object> getTacticalBattle(Long playerId, Long marchId) {
        TacticalBattleAccess access = tacticalBattleAccess(playerId, marchId);
        March march = access.march();
        if (march.getBattleId() == null) {
            long now = System.currentTimeMillis();
            if (Boolean.TRUE.equals(march.getReturning()) || now < Objects.requireNonNullElse(march.getArriveAt(), 0L)
                    || "scout".equals(march.getAction())) {
                throw new IllegalArgumentException("部队尚未到达战场");
            }
            Object target = targets.findTargetById(march.getTargetKind(), march.getTargetId());
            if (target == null || targets.isDefeated(target)) throw new IllegalArgumentException("战斗目标已失效");
            // 兼容旧行军：军情卡在到达后首次点击时创建会话，无需等待下一次 Tick。
            startTacticalBattle(march.getPlayerId(), march, target, now);
            if (march.getBattleId() == null) {
                throw new IllegalArgumentException("目标正在交战，部队已到达并等待前一场战斗结束");
            }
        }
        BattleSession session = battleSessionRepository.findById(march.getBattleId())
                .orElseThrow(() -> new IllegalArgumentException("战斗会话不存在或已结束"));
        if (session.getRoundDeadlineAt() == null || session.getRoundDeadlineAt() <= 0) {
            // 兼容已在战场中的历史会话，打开指挥界面后也必须展示完整倒计时。
            session.setRoundDeadlineAt(System.currentTimeMillis() + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
        }
        return battleView(session, false, access.defending());
    }

    /**
     * 验证当前指挥方指令并结算恰好一回合；未填写的单位使用默认战术，不会因漏选而阻塞战斗。
     */
    @Transactional
    public Map<String, Object> executeTacticalRound(Long playerId, Long marchId,
                                                     com.wargame.model.dto.GameDtos.BattleCommandRequest request) {
        TacticalBattleAccess access = tacticalBattleAccess(playerId, marchId);
        March march = access.march();
        if (march.getBattleId() == null) throw new IllegalArgumentException("当前行军没有可指挥的战斗");
        BattleSession session = battleSessionRepository.findForRoundResolution(march.getBattleId())
                .orElseThrow(() -> new IllegalArgumentException("战斗会话不存在或已结束"));
        Integer expectedRound = request != null ? request.round() : null;
        // 当后台自动结算已先完成时，过期请求只能读取最新状态，不能再推进下一回合。
        if (expectedRound != null && !expectedRound.equals(session.getRoundNo())) {
            return battleView(session, false, access.defending());
        }
        long now = System.currentTimeMillis();
        if (session.getRoundDeadlineAt() == null || session.getRoundDeadlineAt() <= 0) {
            session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
            return battleView(session, false, access.defending());
        }
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(session.getAttackerArmy());
        Map<String, Integer> defenderArmy = JsonUtil.parseIntMap(session.getDefenderArmy());
        // 客户端倒计时到期才提交本回合指令；只要调度器尚未结算当前回合，就接受这次提交。
        // 已结算回合由上面的 expectedRound 检查拦截，未打开指挥页的超时回合仍使用账号预设。
        // 防守玩家只能为守城部队下令，进攻方部队继续使用默认战术；反之亦然。
        Map<String, BattleService.UnitOrder> orders = access.defending()
                ? parseBattleOrders(request, defenderArmy, attackerArmy)
                : parseBattleOrders(request, attackerArmy, defenderArmy);
        return resolveTacticalRound(march.getPlayerId(), march, session,
                access.defending() ? Collections.emptyMap() : orders,
                access.defending() ? orders : Collections.emptyMap(), now, access.defending());
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
        BattleSession session = battleSessionRepository.findForRoundResolution(battleSessionId).orElse(null);
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
                    Collections.emptyMap(), Collections.emptyMap(), now, false);
        }
    }

    /**
     * 结算一个战术回合；离线与在线都读取玩家账号的攻守预设，当前回合的显式指令优先。
     */
    private Map<String, Object> resolveTacticalRound(Long attackerPlayerId, March march, BattleSession session,
                                                      Map<String, BattleService.UnitOrder> attackerOrders,
                                                      Map<String, BattleService.UnitOrder> defenderOrders,
                                                      long now, boolean defendingViewer) {
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(session.getAttackerArmy());
        Map<String, Integer> defenderArmy = JsonUtil.parseIntMap(session.getDefenderArmy());
        Object target = targets.findTargetById(session.getTargetKind(), session.getTargetId());
        Long defenderPlayerId = target instanceof PlayerCity city && targets.hasRealOwner(city)
                ? city.getOwnerId() : null;
        Map<String, BattleService.UnitOrder> resolvedAttackerOrders = new LinkedHashMap<>(
                battleActionPreferences.orders(attackerPlayerId, false, attackerArmy));
        Map<String, BattleService.UnitOrder> resolvedDefenderOrders = new LinkedHashMap<>(
                battleActionPreferences.orders(defenderPlayerId, true, defenderArmy));
        resolvedAttackerOrders.putAll(attackerOrders);
        resolvedDefenderOrders.putAll(defenderOrders);
        int nextRound = session.getRoundNo() + 1;
        BattleRoundState round = battleService.resolveWorldRound(attackerArmy, defenderArmy,
                JsonUtil.parseIntMap(session.getAttackerPositions()), JsonUtil.parseIntMap(session.getDefenderPositions()),
                session.getInitialDistance(), JsonUtil.parseIntMap(session.getAttackerTech()), JsonUtil.parseIntMap(session.getDefenderTech()),
                JsonUtil.parseIntMap(session.getAttackerSkills()), JsonUtil.parseIntMap(session.getDefenderSkills()),
                session.getAttackerCommanderMil(), session.getAttackerCommanderDef(),
                session.getDefenderCommanderMil(), session.getDefenderCommanderDef(), 0, session.getDefenderWallLevel(),
                nextRound, resolvedAttackerOrders, resolvedDefenderOrders);
        String battleLog = session.getBattleLog() + round.log();

        if (!round.finished()) {
            session.setRoundNo(round.round());
            session.setAttackerArmy(JsonUtil.toJson(round.attackerArmy()));
            session.setDefenderArmy(JsonUtil.toJson(round.defenderArmy()));
            session.setAttackerPositions(JsonUtil.toJson(round.attackerPositions()));
            session.setDefenderPositions(JsonUtil.toJson(round.defenderPositions()));
            session.setBattleLog(battleLog);
            session.setRoundDeadlineAt(now + TACTICAL_ROUND_TIMEOUT_MS);
            battleSessionRepository.save(session);
            return battleView(session, false, defendingViewer);
        }

        if (target == null) throw new IllegalStateException("战斗目标已不存在，无法结算");
        BattleResult result = battleService.finishWorldBattle(round.attackerWin(), round.attackerArmy(), round.defenderArmy(),
                JsonUtil.parseIntMap(session.getInitialAttacker()), JsonUtil.parseIntMap(session.getInitialDefender()),
                JsonUtil.parseIntMap(session.getAttackerTech()), session.getAction(), JsonUtil.parseIntMap(session.getDefenderResources()),
                session.getDefenderWarehouseLevel(), battleLog);
        Officer attackerCommander = march.getCommanderId() != null
                ? targets.getOfficerById(attackerPlayerId, march.getCommanderId()) : targets.getCommander(attackerPlayerId);
        Officer defenderCommander = target instanceof PlayerCity city && city.getOwnerId() != null
                ? targets.getCommander(city.getOwnerId()) : null;
        if (target instanceof PlayerCity city && targets.hasRealOwner(city)) {
            // 防守玩家的兵力、资源和城防按目标城市槽位持久化，结算时必须进入该城市作用域。
            try (var ignored = cityScope.enter(city)) {
                settleTacticalBattle(attackerPlayerId, march, target, now, result, attackerCommander, defenderCommander, session.getAction());
            }
        } else {
            settleTacticalBattle(attackerPlayerId, march, target, now, result, attackerCommander, defenderCommander, session.getAction());
        }
        battleSessionRepository.deleteResolvedById(session.getId());
        Map<String, Object> response = battleView(session, true, defendingViewer);
        response.put("round", round.round());
        response.put("attackerArmy", defendingViewer ? round.defenderArmy() : round.attackerArmy());
        response.put("defenderArmy", defendingViewer ? round.attackerArmy() : round.defenderArmy());
        response.put("attackerPositions", defendingViewer ? round.defenderPositions() : round.attackerPositions());
        response.put("defenderPositions", defendingViewer ? round.attackerPositions() : round.defenderPositions());
        response.put("log", battleLog);
        response.put("finished", true);
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
            if (!attackerArmy.containsKey(entry.getKey()) || attackerArmy.get(entry.getKey()) <= 0 || entry.getValue() == null) continue;
            String focusTarget = entry.getValue().focusTarget();
            // 未显式选择移动命令时不写入订单，让战斗引擎按兵种默认行为推进。
            if (entry.getValue().action() == null || entry.getValue().action().isBlank()) {
                if (focusTarget == null || focusTarget.isBlank()) continue;
                throw new IllegalArgumentException("选择集火目标时，请同时选择前进、后退或待命");
            }
            BattleService.CommandAction action;
            try { action = BattleService.CommandAction.valueOf(entry.getValue().action().toUpperCase(Locale.ROOT)); }
            catch (Exception ex) { throw new IllegalArgumentException("未知战术指令：" + entry.getValue().action()); }
            if (focusTarget != null && !focusTarget.isBlank() && defenderArmy.getOrDefault(focusTarget, 0) <= 0) {
                throw new IllegalArgumentException("指定集火目标不在战场上");
            }
            orders.put(entry.getKey(), new BattleService.UnitOrder(action, focusTarget));
        }
        return orders;
    }

    private Map<String, Integer> initialPositions(Map<String, Integer> army, int position) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        army.forEach((unit, count) -> positions.put(unit, position));
        return positions;
    }

    /**
     * 以当前指挥方视角序列化战场，防守方需要看到守军作为“我军”才能提交正确兵种指令。
     */
    private Map<String, Object> battleView(BattleSession session, boolean finished, boolean defendingViewer) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("marchId", session.getMarchId());
        Player attacker = playerRepository.findById(session.getPlayerId()).orElse(null);
        view.put("targetName", defendingViewer ? (attacker != null ? attacker.getUsername() : "来袭敌军") : session.getTargetName());
        view.put("targetKind", session.getTargetKind());
        view.put("action", session.getAction());
        view.put("side", defendingViewer ? "defender" : "attacker");
        view.put("round", session.getRoundNo());
        view.put("roundDeadlineAt", session.getRoundDeadlineAt());
        view.put("maxRound", BattleService.MAX_ROUND);
        view.put("initialDistance", session.getInitialDistance());
        view.put("attackerArmy", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderArmy() : session.getAttackerArmy()));
        view.put("defenderArmy", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerArmy() : session.getDefenderArmy()));
        view.put("attackerPositions", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderPositions() : session.getAttackerPositions()));
        view.put("defenderPositions", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerPositions() : session.getDefenderPositions()));
        view.put("attackerTech", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderTech() : session.getAttackerTech()));
        view.put("defenderTech", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerTech() : session.getDefenderTech()));
        view.put("attackerSkills", JsonUtil.parseIntMap(defendingViewer ? session.getDefenderSkills() : session.getAttackerSkills()));
        view.put("defenderSkills", JsonUtil.parseIntMap(defendingViewer ? session.getAttackerSkills() : session.getDefenderSkills()));
        Object target = defendingViewer ? targets.findTargetById(session.getTargetKind(), session.getTargetId()) : null;
        Long viewerId = defendingViewer && target instanceof PlayerCity city && targets.hasRealOwner(city)
                ? city.getOwnerId() : defendingViewer ? null : session.getPlayerId();
        if (viewerId != null) {
            view.put("defaultActions", battleActionPreferences.get(viewerId)
                    .get(defendingViewer ? "defending" : "outgoing"));
        }
        view.put("log", session.getBattleLog());
        view.put("finished", finished);
        return view;
    }

    private record TacticalBattleAccess(March march, boolean defending) {}

    @Transactional
    public void cancelMarch(Long playerId, Long marchId) {
        March march = marchRepository.findById(marchId)
                .orElseThrow(() -> new IllegalArgumentException("行军任务不存在"));
        if (!playerId.equals(march.getPlayerId()) || march.getCitySlot() != cityScope.slot(playerId)) {
            throw new IllegalArgumentException("无权取消该行军任务");
        }
        if (Boolean.TRUE.equals(march.getReturning()) || Boolean.TRUE.equals(march.getGathering())) {
            throw new IllegalArgumentException("行军已进入返程或采集阶段，无法取消");
        }
        if (march.getBattleId() != null) {
            throw new IllegalArgumentException("部队已进入战场，请完成战斗后再返程");
        }
        // 撤回是在途行军应进入返程，而不是立即删除记录。这样地图和军情页
        // 能继续展示返城进度，部队与资源在返程到达后统一归队。
        long now = System.currentTimeMillis();
        long outboundStart = march.getStartAt() != null ? march.getStartAt() : now;
        long outboundEnd = march.getArriveAt() != null ? march.getArriveAt() : now;
        long duration = Math.max(1000L, outboundEnd - outboundStart);
        long travelled = Math.max(1000L, Math.min(duration, now - outboundStart));
        startReturnMarch(march, now);
        // 保留完整路线并回推返程起点时间：地图标记从撤回时的位置掉头，
        // 剩余返城时间等于已行进时间，不会瞬移到尚未抵达的敌城。
        march.setStartAt(now + travelled - duration);
        march.setArriveAt(now + travelled);
        marchRepository.save(march);
        notifyIncomingChange(march, "cancelled");
    }

    // ========================================================================
    // 野地驻军与就地采集/收获/撤回
    // ========================================================================

    @Transactional
    public Map<String, Object> startWildGather(Long playerId, Long wildTileId) {
        WildTile wt = wildTileRepository.findById(wildTileId)
                .orElseThrow(() -> new IllegalArgumentException("野地不存在"));
        if (!Boolean.TRUE.equals(wt.getOccupied()) || !playerId.equals(wt.getOccupiedBy())) {
            throw new IllegalArgumentException("只能在自己占领的野地上开启采集");
        }
        if (Boolean.TRUE.equals(wt.getGathering())) {
            throw new IllegalArgumentException("该野地已在采集中");
        }
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
        int load = calcArmyLoad(garrison);
        if (load <= 0) {
            throw new IllegalArgumentException("当前驻军无运载能力，无法采集");
        }
        int gatherAmount = Math.min(remaining, load);
        long now = System.currentTimeMillis();
        long gatherDuration = (long) Math.max(30L, Math.ceil(gatherAmount / 10.0)) * 1000L;

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
        return res;
    }

    @Transactional
    public Map<String, Object> harvestWild(Long playerId, Long wildTileId) {
        WildTile wt = wildTileRepository.findById(wildTileId)
                .orElseThrow(() -> new IllegalArgumentException("野地不存在"));
        if (!Boolean.TRUE.equals(wt.getOccupied()) || !playerId.equals(wt.getOccupiedBy())) {
            throw new IllegalArgumentException("该野地未被你占领");
        }
        if (!Boolean.TRUE.equals(wt.getGathering())) {
            throw new IllegalArgumentException("该野地当前并未在采集中");
        }
        long now = System.currentTimeMillis();
        long startAt = wt.getGatherStartAt() != null ? wt.getGatherStartAt() : now;
        long endAt = wt.getGatherEndAt() != null ? wt.getGatherEndAt() : now;
        int maxLoad = wt.getGatherLoad() != null ? wt.getGatherLoad() : 0;
        String resKey = wt.getGatherRes();

        int harvestAmount;
        if (now >= endAt || endAt <= startAt) {
            harvestAmount = maxLoad;
        } else {
            double ratio = (double) (now - startAt) / (endAt - startAt);
            harvestAmount = (int) Math.floor(ratio * maxLoad);
        }
        int totalRes = wt.getTotalRes() != null ? wt.getTotalRes() : 0;
        int mined = wt.getMined() != null ? wt.getMined() : 0;
        int remaining = Math.max(0, totalRes - mined);
        harvestAmount = Math.min(harvestAmount, remaining);

        if (harvestAmount > 0 && resKey != null) {
            addResources(playerId, Map.of(resKey, harvestAmount));
            wt.setMined(mined + harvestAmount);
        }

        wt.setGathering(false);
        wt.setGatherStartAt(0L);
        wt.setGatherEndAt(0L);
        wt.setGatherLoad(0);
        wt.setGatherRes(null);
        wildTileRepository.save(wt);

        // 提前收获可能得到0资源，只有实际入库才计为完成采集。
        if (harvestAmount > 0 && resKey != null) {
            questService.onEvent(playerId, "GATHER_COMPLETE", resKey, 1);
        }

        String resName = switch (resKey != null ? resKey : "") {
            case "food" -> "粮食";
            case "steel" -> "钢铁";
            case "oil" -> "石油";
            case "rare" -> "稀矿";
            default -> "资源";
        };

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", harvestAmount > 0 ? ("成功收获 " + harvestAmount + " " + resName + "！") : "当前尚未采集到资源，已停止采集");
        res.put("harvestAmount", harvestAmount);
        res.put("harvestRes", resKey);
        return res;
    }

    @Transactional
    public Map<String, Object> recallWild(Long playerId, Long wildTileId) {
        WildTile wt = wildTileRepository.findById(wildTileId)
                .orElseThrow(() -> new IllegalArgumentException("野地不存在"));
        if (!Boolean.TRUE.equals(wt.getOccupied()) || !playerId.equals(wt.getOccupiedBy())) {
            throw new IllegalArgumentException("该野地未被你占领");
        }
        Map<String, Integer> garrison = JsonUtil.parseIntMap(wt.getGarrison());
        if (garrison.isEmpty()) {
            throw new IllegalArgumentException("该野地暂无驻军可撤回");
        }
        long now = System.currentTimeMillis();
        int harvested = 0;
        String resKey = wt.getGatherRes();
        if (Boolean.TRUE.equals(wt.getGathering())) {
            long startAt = wt.getGatherStartAt() != null ? wt.getGatherStartAt() : now;
            long endAt = wt.getGatherEndAt() != null ? wt.getGatherEndAt() : now;
            int maxLoad = wt.getGatherLoad() != null ? wt.getGatherLoad() : 0;
            if (now >= endAt || endAt <= startAt) harvested = maxLoad;
            else harvested = (int) Math.floor(((double) (now - startAt) / (endAt - startAt)) * maxLoad);
            int remaining = Math.max(0, (wt.getTotalRes() != null ? wt.getTotalRes() : 0) - (wt.getMined() != null ? wt.getMined() : 0));
            harvested = Math.min(harvested, remaining);
            if (harvested > 0 && resKey != null) {
                addResources(playerId, Map.of(resKey, harvested));
                wt.setMined((wt.getMined() != null ? wt.getMined() : 0) + harvested);
            }
            wt.setGathering(false);
            wt.setGatherStartAt(0L);
            wt.setGatherEndAt(0L);
            wt.setGatherLoad(0);
            wt.setGatherRes(null);
        }

        returnArmy(playerId, garrison);
        wt.setGarrison("{}");
        wildTileRepository.save(wt);

        if (harvested > 0 && resKey != null) {
            questService.onEvent(playerId, "GATHER_COMPLETE", resKey, 1);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", harvested > 0 ? ("已撤回驻军，同时收获了已采集的 " + harvested + " 资源") : "野地驻军已成功撤回主城");
        return res;
    }

    // ========================================================================
    // createDispatch - 对应 JS world.js launchDispatch
    // ========================================================================

    public double getUnitTechSpdMul(Long playerId, String cat) {
        String catKey = switch (cat != null ? cat : "") {
            case "arm" -> "arm_engine";
            case "air" -> "air_engine";
            case "nav" -> "nav_engine";
            default -> null;
        };
        if (catKey == null) return 1.0;
        int lv = targets.getTechLevel(playerId, catKey);
        return 1.0 + 0.05 * lv;
    }

    @Transactional
    public Map<String,Object> previewRoute(Long playerId,DispatchRequest req) {
        Object target=targets.findTargetByLongId(req.targetKind(),req.targetId());
        if(target==null)throw new IllegalArgumentException("目标不存在");
        boolean transfer="transport".equals(req.action())||"rebase".equals(req.action());
        if(transfer&&(!(target instanceof PlayerCity c)||!playerId.equals(c.getOwnerId())))throw new IllegalArgumentException("只能向自己的城市运输或调遣");
        Map<String,Integer> army=new LinkedHashMap<>();double slowest=Double.MAX_VALUE;
        for(var e:(req.army()==null?Map.<String,Integer>of():req.army()).entrySet()){
            var u=GameData.UNITS.get(e.getKey());if(u==null||e.getValue()==null||e.getValue()<=0)continue;
            if("scout".equals(req.action())&&!"scout".equals(e.getKey()))continue;
            int have=armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId,cityScope.slot(playerId),e.getKey()).stream().mapToInt(v->Objects.requireNonNullElse(v.getCount(),0)).sum();
            int count=Math.min(e.getValue(),have);if(count<=0)continue;
            army.put(e.getKey(),count);
            double effectiveSpd = u.spd() * getUnitTechSpdMul(playerId, u.cat());
            slowest=Math.min(slowest,effectiveSpd);
        }
        if(army.isEmpty())throw new IllegalArgumentException("请选择出征部队以计算路线");
        var route=routes.plan(playerId,target,army,transfer);
        var cs=cityStateRepository.findByPlayerIdAndCitySlot(playerId,cityScope.slot(playerId)).orElse(null);
        double boost=cs!=null&&cs.getMarchBoostUntil()!=null&&cs.getMarchBoostUntil()>System.currentTimeMillis()?1.5:1;
        int seconds=Math.max(1,(int)Math.ceil((double)route.distance()*WorldConfig.MARCH_SEC_PER_GRID/(Math.max(0.1,slowest)*boost)));
        return Map.of("mode",route.mode(),"points",route.points(),"distance",route.distance(),"seconds",seconds,"reservedLoad",route.reservedLoad(),"cargoLimit",Math.min(calcArmyLoad(army),route.cargoLimit()));
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
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) throw new IllegalArgumentException("玩家不存在");

        String kind = req.targetKind();
        String action = req.action() != null ? req.action() : "conquer";
        boolean isGather = "wild_gather".equals(kind);
        boolean isScout = "scout".equals(action);
        boolean isStation = "station".equals(action);
        boolean transfer = "transport".equals(action) || "rebase".equals(action);
        if (!Set.of("conquer", "plunder", "scout", "gather", "transport", "rebase", "station").contains(action)) throw new IllegalArgumentException("无效行军类型");

        // 1. 查找目标
        Object target = targets.findTargetByLongId(kind, req.targetId());
        if (target instanceof PlayerCity pc && pc.getOwnerId() != null) {
            Player owner = accounts.lockPlayer(pc.getOwnerId());
            if (owner.deletionDue(System.currentTimeMillis())) throw new IllegalArgumentException("目标城池已失效");
        }

        if (target == null) throw new IllegalArgumentException("目标不存在");
        if ("player".equals(kind) && target instanceof PlayerCity pc && !targets.hasRealOwner(pc)) {
            throw new IllegalArgumentException("该城市为模拟 NPC，请使用 simulated_npc 目标类型");
        }
        if (!isGather && !isStation && targets.isDefeated(target)) throw new IllegalArgumentException("目标已被击败");

        if (transfer) {
            if (!(target instanceof PlayerCity pc) || !playerId.equals(pc.getOwnerId())) throw new IllegalArgumentException("只能向自己的城市运输或调遣");
            PlayerCity destination = cityScope.requireOwned(playerId, ((PlayerCity) target).getId(), true);
            if (destination.getCitySlot() == cityScope.slot(playerId)) throw new IllegalArgumentException("请选择另一座城市");
        } else if (target instanceof PlayerCity pc) {
            if (playerId.equals(pc.getOwnerId())) throw new IllegalArgumentException("不能攻击自己的城市");
            if (pc.getReadyAt() > System.currentTimeMillis()) throw new IllegalArgumentException("目标城市仍在建设中");
            if (isOffensiveAction(action) && !canAttackPlayerCity(playerId, pc, System.currentTimeMillis())) {
                if (guildRelations.areFriendly(playerId, pc.getOwnerId())) {
                    throw new IllegalArgumentException("友好军团成员之间不能征服或掠夺");
                }
                throw new IllegalArgumentException("请先向目标玩家宣战，或将双方军团设为敌对");
            }
        }
        if (req.commanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, req.commanderId());
            if (officer == null || officer.getCitySlot() != cityScope.slot(playerId)) throw new IllegalArgumentException("军官不在当前城市");
            if ("mayor".equals(officer.getRole()) || "commander".equals(officer.getRole())) {
                throw new IllegalArgumentException("请先解除市长或指挥官任命再出征");
            }
            if (marchRepository.existsByPlayerIdAndCommanderId(playerId, req.commanderId())) throw new IllegalArgumentException("军官正在执行行军任务");
        }

        int tx = targets.getTargetX(target);
        int ty = targets.getTargetY(target);

        // 2. 验证并配置兵力
        Map<String, Integer> reqArmy = req.army() != null ? req.army() : Collections.emptyMap();
        Map<String, Integer> customArmy = new LinkedHashMap<>();
        double slowestSpd = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : reqArmy.entrySet()) {
            String uid = entry.getKey();
            int n = entry.getValue() != null ? entry.getValue() : 0;
            if (n <= 0) continue;
            UnitDef u = GameData.UNITS.get(uid);
            if (u == null) continue;
            if (isScout && !"scout".equals(uid)) continue;
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndCitySlotAndType(playerId, cityScope.slot(playerId), uid);
            int max = existing.isEmpty() ? 0 : (existing.get(0).getCount() != null ? existing.get(0).getCount() : 0);
            if (n > max) n = max;
            if (n <= 0) continue;
            customArmy.put(uid, n);
            double effectiveSpd = u.spd() * getUnitTechSpdMul(playerId, u.cat());
            if (effectiveSpd < slowestSpd) slowestSpd = effectiveSpd;
        }
        if (customArmy.isEmpty()) throw new IllegalArgumentException("请至少选择一种兵种出征");

        // 出征编队受当前城市带兵上限约束，不能仅依赖前端拦截。
        long selectedTroops = customArmy.values().stream().mapToLong(Integer::longValue).sum();
        int armyCap = armyService.armyCap(playerId);
        if (selectedTroops > armyCap) {
            throw new IllegalArgumentException("出征兵力超过带兵上限 " + armyCap + "（当前选择 " + selectedTroops + "）");
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
        }

        MarchRouteService.Route route = routes.plan(playerId,target,customArmy,transfer);

        // 3. 按实际路线和最慢兵种计算行军时间及补给。
        int px = java.util.Objects.requireNonNullElse(cityScope.economy(playerId).getCityPosX(), 0);
        int py = java.util.Objects.requireNonNullElse(cityScope.economy(playerId).getCityPosY(), 0);
        int marchDist = route.distance();
        int secPerGrid = WorldConfig.MARCH_SEC_PER_GRID;
        double spd = Math.max(0.1, slowestSpd);
        long now = System.currentTimeMillis();
        double speedMul = 1.0;
        CityState cs = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (cs != null && cs.getMarchBoostUntil() != null && cs.getMarchBoostUntil() > now) {
            speedMul = 1.5;
        }
        int marchSec = Math.max(1, (int) Math.ceil((double) marchDist * secPerGrid / (spd * speedMul)));
        // 出征仅预扣油耗；进驻无返程，其他任务预扣往返油耗以保证返程不受库存变化影响。
        int marchFuel = calcMarchFuel(customArmy, marchDist, isStation ? 1 : 2);

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
            int load = Math.min(calcArmyLoad(customArmy),route.cargoLimit());
            long totalCarry = carryRes.values().stream().mapToLong(Integer::longValue).sum();
            if (totalCarry > load) throw new IllegalArgumentException("携带资源超出负重上限 " + load);
        }

        Map<String, Integer> totalCost = new LinkedHashMap<>(carryRes);
        totalCost.merge("oil", marchFuel, Integer::sum);
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) throw new IllegalArgumentException("资源仓库不存在，无法准备行军补给");
        for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
            int need = totalCost.getOrDefault(rk, 0);
            int have = getResourceAmount(res, rk);
            if (need > have) {
                throw new IllegalArgumentException("资源不足: " + rk + "（行军油耗和携带物资共需 " + need + "，库存 " + have + "）");
            }
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
        march.setArmy(JsonUtil.toJson(customArmy));
        march.setCommanderId(req.commanderId());
        march.setCarryRes(JsonUtil.toJson(carryRes));
        march.setStartAt(now);
        march.setArriveAt(now + marchSec * 1000L);
        march.setReturning(false);

        if (isGather) {
            march.setGathering(false);
            march.setGatherEndAt(0L);
            march.setGatherAmount(0);
            if (target instanceof WildTile wt) {
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
        if (!"player".equals(march.getTargetKind())) return;
        Object target = targets.findTargetById("player", march.getTargetId());
        if (!(target instanceof PlayerCity city) || !targets.hasRealOwner(city)
                || march.getPlayerId().equals(city.getOwnerId())) return;
        String fromName = playerRepository.findById(march.getPlayerId())
                .map(Player::getUsername).orElse("未知敌军");
        pushService.pushIncomingAttack(city.getOwnerId(), Map.of(
                "id", "march-" + march.getId(), "event", event, "fromName", fromName));
    }

    // ========================================================================
    // processIncoming - 对应 JS core.js processIncoming(now)
    // ========================================================================

    @Transactional
    public void processIncoming(Long playerId, long now) {
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

    /** 战斗结束后一次性写入伤兵、目标损失、战报与返程状态。 */
    private void settleTacticalBattle(Long playerId, March m, Object target, long now,
                                      BattleResult result, Officer commander, Officer defenderCommander, String action) {
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc) && result.isCityConquered()) {
            result.setCityConquered(false);
            result.setReport(result.getReport() + "\n城市守军已击败；本阶段不转移城市归属。\n");
        }
        recordBattleWounded(playerId, m, null, null, result.getInitialAttacker(),
                result.getSurvivorAttacker(), commander, now, result, "攻方");
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
            recordBattleWounded(pc.getOwnerId(), null, pc.getX(), pc.getY(), result.getInitialDefender(),
                    result.getSurvivorDefender(), defenderCommander, now, result, "守方");
        }
        settleBattleAwards(playerId, m, true, result);
        if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
            settleBattleAwards(pc.getOwnerId(), m, false, result);
        }
        pushBattleReport(playerId, result, m, commander, defenderCommander);

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

            // 标记与更新目标
            if (target instanceof Bandit b) {
                b.setDefeated(true);
                b.setArmy("{}");
                banditRepository.save(b);
                // 主线任务进度钩子: 击败流寇
                try { questService.onEvent(playerId, "BANDIT_DEFEAT", null, 1); } catch (Exception ignored) {}
            } else if (target instanceof NpcCity nc) {
                // 征服和掠夺均已击败本次守军，旧城不再留在地图上。
                npcCitySpawnService.replace(nc);
            } else if (target instanceof PlayerCity pc) {
                if (targets.hasRealOwner(pc)) {
                    Long defenderId = pc.getOwnerId();
                    // 真实玩家主城：更新防守方部队和城防损失
                    applyDefenderLosses(pc.getOwnerId(), survivorDefender);
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
                    if ("conquer".equals(action)) pc.setWarEndAt(now + 30 * 60 * 1000L);
                    playerCityRepository.save(pc);
                    // 城市所属人可能已经改变，战报仍应发给本次参战的防守玩家。
                    pushBattleReport(defenderId, result, m, commander, defenderCommander);
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
                    applyDefenderLosses(pc.getOwnerId(), survivorDefender);
                    pushBattleReport(pc.getOwnerId(), result, m, commander, defenderCommander);
                } else {
                    pc.setArmy(JsonUtil.toJson(remainingArmy));
                    pc.setForts(JsonUtil.toJson(remainingForts));
                    playerCityRepository.save(pc);
                }
            }
        }

        // 有幸存者则开始返程，否则删除；清空会话标记后行军才可重新由 Tick 处理返程。
        m.setBattleId(null);
        if (survivors.isEmpty()) {
            marchRepository.delete(m);
        } else {
            startReturnMarch(m, now);
            marchRepository.save(m);
        }
    }

    // ========================================================================
    // resolveScout - 侦查 (对应 JS core.js processMarches scout 分支)
    // ========================================================================

    private void resolveScout(Long playerId, March m, Object target, long now) {
        Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
        int myScouts = armyMap.getOrDefault("scout", 0);

        Map<String, Integer> enemyArmy = targets.getTargetArmy(target);
        int enemyScouts = enemyArmy.getOrDefault("scout", 0);
        Long defenderId = target instanceof PlayerCity pc && targets.hasRealOwner(pc) ? pc.getOwnerId() : null;

        UnitDef scoutInfo = GameData.UNITS.get("scout");
        double scoutSum = scoutInfo.atkAir() + scoutInfo.def() + scoutInfo.hp();
        int myPower = (int) Math.round(myScouts * scoutSum);
        int enemyPower = (int) Math.round(enemyScouts * scoutSum);
        double ratio = enemyPower > 0 ? (double) myPower / enemyPower : 999;

        int myLost;
        int enemyLost;
        String scoutResult;
        boolean showCityInfo;
        // 先手由侦察机速度、主动侦查突袭和守城方预警科技共同决定。
        int attackerInitiative = scoutInfo.spd() + 2;
        int defenderInitiative = scoutInfo.spd();
        if (defenderId != null) {
            defenderInitiative += targets.getTechLevel(defenderId, "recon_level");
            defenderInitiative += targets.getTechLevel(defenderId, "recon_radar") * 2;
        }
        boolean attackerFirst = attackerInitiative >= defenderInitiative;

        if (enemyScouts <= 0) {
            // 敌方无侦察机驻防：我方无空中阻碍，零损失，按侦察技术等级获取情报
            myLost = 0;
            enemyLost = 0;
            scoutResult = "overwhelming_victory";
            showCityInfo = true;
        } else if (myScouts <= 0) {
            myLost = 0;
            enemyLost = 0;
            scoutResult = "overwhelming_defeat";
            showCityInfo = false;
        } else if (ratio < 0.3) {
            // 我方兵力悬殊劣势，全灭
            myLost = myScouts;
            int maxInflicted = (int) Math.floor((double) myPower / (scoutInfo.def() + scoutInfo.hp()));
            enemyLost = Math.min(enemyScouts, maxInflicted);
            scoutResult = "overwhelming_defeat";
            showCityInfo = false;
            targets.setTargetScoutCount(target, Math.max(0, enemyScouts - enemyLost));
        } else {
            // 双方空战交火模拟
            int rounds = 0;
            int myRemain = myScouts;
            int enemyRemain = enemyScouts;
            while (myRemain > 0 && enemyRemain > 0 && rounds < 10) {
                rounds++;
                if (attackerFirst) {
                    double myAtk = myRemain * scoutInfo.atkAir();
                    enemyRemain -= Math.min(enemyRemain, (int) Math.floor(myAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    if (enemyRemain > 0) {
                        double enemyAtk = enemyRemain * scoutInfo.atkAir();
                        myRemain -= Math.min(myRemain, (int) Math.floor(enemyAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    }
                } else {
                    double enemyAtk = enemyRemain * scoutInfo.atkAir();
                    myRemain -= Math.min(myRemain, (int) Math.floor(enemyAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    if (myRemain > 0) {
                        double myAtk = myRemain * scoutInfo.atkAir();
                        enemyRemain -= Math.min(enemyRemain, (int) Math.floor(myAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    }
                }
            }
            myLost = myScouts - Math.max(0, myRemain);
            enemyLost = enemyScouts - Math.max(0, enemyRemain);
            if (myRemain > 0 && enemyRemain == 0) {
                scoutResult = (myLost == 0 || (double) myPower / enemyPower >= 3.0) ? "overwhelming_victory" : "close_match_win";
                showCityInfo = true;
                targets.setTargetScoutCount(target, 0);
            } else if (myRemain == 0) {
                scoutResult = (enemyLost == 0 || (double) enemyPower / myPower >= 3.0) ? "overwhelming_defeat" : "close_match_loss";
                showCityInfo = false;
                targets.setTargetScoutCount(target, Math.max(0, enemyRemain));
            } else {
                // 十回合结束后双方均有存活侦察机，视为战平；突围机仍能传回侦查情报。
                scoutResult = "draw";
                showCityInfo = true;
                targets.setTargetScoutCount(target, Math.max(0, enemyRemain));
            }
        }

        // 情报深度只取决于出征方的侦察技术。
        int myReconLv = targets.getTechLevel(playerId, "recon_level");

        // 生成侦查报告
        Map<String, Object> reportData = new LinkedHashMap<>();
        reportData.put("time", now);
        reportData.put("attackerName", playerRepository.findById(playerId)
                .map(Player::getUsername).orElse("我方"));
        reportData.put("targetName", targets.getTargetName(target));
        reportData.put("targetKind", m.getTargetKind());
        reportData.put("x", targets.getTargetX(target));
        reportData.put("y", targets.getTargetY(target));
        reportData.put("level", targets.getTargetLevel(target));
        reportData.put("result", scoutResult);
        reportData.put("showCityInfo", showCityInfo);
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
                    reportData.put("army", new LinkedHashMap<>(enemyArmy));
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

                // Lv.0: 模糊守军与工事隐藏提示
                if (myReconLv < 2) {
                    reportData.put("armyVague", buildVagueArmyDesc(enemyArmy));
                }
                if (myReconLv < 1) {
                    reportData.put("fortsVague", "敌方防御工事隐蔽在掩体与伪装网下，无法探明");
                }

                // Lv.1+: 解锁外围城防设施
                if (myReconLv >= 1) {
                    reportData.put("forts", targets.getTargetForts(target));
                }

                // Lv.2+: 解锁精确守军与统帅
                if (myReconLv >= 2) {
                    reportData.put("army", new LinkedHashMap<>(enemyArmy));
                    reportData.put("commander", targets.getDefenderCommanderName(target));
                }

                // Lv.3+: 解锁城市建筑与驻留将领人数
                if (myReconLv >= 3) {
                    reportData.put("buildings", targets.getTargetBuildingsMap(target));
                    reportData.put("officerCount", targets.getTargetOfficerCount(target));
                }

                // Lv.4+: 解锁科研科技与可掠夺测算
                if (myReconLv >= 4) {
                    reportData.put("techs", targets.getTargetTechMap(target));
                    reportData.put("plunderable", targets.calculatePlunderable(target));
                    reportData.put("warehouseProtection", targets.getWarehouseProtection(target));
                }

                // Lv.5+: 解锁驻守将领档案与综合战力评分
                if (myReconLv >= 5) {
                    reportData.put("officers", targets.getTargetOfficerList(target));
                    long power = calcDefensePower(enemyArmy, targets.getTargetForts(target));
                    reportData.put("defensePower", power);
                    reportData.put("threatLevel", calcThreatLevel(power));
                }
            }
        }

        Officer scoutCommander = m.getCommanderId() != null
                ? targets.getOfficerById(playerId, m.getCommanderId()) : targets.getCommander(playerId);
        reportData.put("wounded", woundedService.recordOutboundLosses(playerId, m,
                Map.of("scout", myScouts), Map.of("scout", myScouts - myLost), scoutCommander, now));
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
                    .map(Player::getUsername).orElse("未知敌军"));
            defenseData.put("fromX", m.getFromX());
            defenseData.put("fromY", m.getFromY());
            defenseData.put("intercepted", !showCityInfo);
            defenseData.put("result", scoutResult);
            defenseData.put("myScouts", enemyScouts);
            defenseData.put("myLost", enemyLost);
            defenseData.put("enemyScouts", myScouts);
            defenseData.put("enemyLost", myLost);
            defenseData.put("attackerInitiative", attackerInitiative);
            defenseData.put("defenderInitiative", defenderInitiative);
            defenseData.put("firstStrike", attackerFirst ? "attacker" : "defender");
            defenseData.put("wounded", woundedService.recordLosses(pc.getOwnerId(), pc.getX(), pc.getY(),
                    Map.of("scout", enemyScouts), Map.of("scout", enemyScouts - enemyLost),
                    targets.getCommander(pc.getOwnerId()), now));
            saveScoutReport(pc.getOwnerId(), target, defenseData, now);
        }
        try { questService.onEvent(playerId, "SCOUT_COMPLETE", null, 1); } catch (Exception ignored) {}
        pushService.pushMarchUpdate(playerId, marchEvent("scoutComplete", m, Collections.emptyMap()));

        // 更新行军部队损失
        armyMap.put("scout", myScouts - myLost);
        m.setArmy(JsonUtil.toJson(armyMap));

        if (myScouts - myLost > 0) {
            startReturnMarch(m, now);
            marchRepository.save(m);
        } else {
            marchRepository.delete(m);
        }
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
        Map<String, Integer> defenderArmy = targets.getArmyMap(playerId);
        Map<String, Integer> defenderForts = targets.getFortsMap(playerId);
        Map<String, Integer> defenderTech = targets.getTechMap(playerId);
        Officer commander = targets.getCommander(playerId);
        int defenderCommanderMil = 0;
        int defenderCommanderDef = 0;
        if (commander != null) {
            EquipmentService.Attributes dAttrs = equipmentService.attributes(commander);
            defenderCommanderMil = dAttrs.military();
            defenderCommanderDef = dAttrs.defense();
        }
        Map<String, Integer> defenderSkills = targets.getCommanderSkills(commander);
        int defenderWallLevel = targets.buildingLevel(playerId, "wall");

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        Map<String, Integer> defenderResources = new LinkedHashMap<>();
        if (res != null) {
            defenderResources.put("food", res.getFood() != null ? res.getFood() : 0);
            defenderResources.put("steel", res.getSteel() != null ? res.getSteel() : 0);
            defenderResources.put("oil", res.getOil() != null ? res.getOil() : 0);
            defenderResources.put("rare", res.getRare() != null ? res.getRare() : 0);
            defenderResources.put("gold", res.getGold() != null ? res.getGold() : 0);
        }
        long defenderWarehouseLevel = targets.buildingLevel(playerId, "depot");

        String action = im.getAction() != null ? im.getAction() : "conquer";

        BattleResult result = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, defenderForts,
                Collections.emptyMap(), defenderTech,
                Collections.emptyMap(), defenderSkills,
                0, 0,
                defenderCommanderMil, defenderCommanderDef,
                0, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel,
                true);
        recordBattleWounded(playerId, null, null, null, result.getInitialDefender(),
                result.getSurvivorDefender(), commander, now, result, "守方");
        settleBattleAwards(playerId, null, false, result);
        pushBattleReport(playerId, result, null, null, commander);

        // 应用守方损失
        Map<String, Integer> survivorDefender = result.getSurvivorDefender();
        if (survivorDefender == null) survivorDefender = Collections.emptyMap();
        applyDefenderLosses(playerId, survivorDefender);

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

    private void recordBattleWounded(Long playerId, March outbound, Integer x, Integer y, Map<String, Integer> initial,
                                     Map<String, Integer> survivors, Officer commander, long now,
                                     BattleResult result, String side) {
        int recoveryPercent = woundedService.recoveryPercent(playerId, commander);
        Map<String, Integer> injured = outbound == null
                ? woundedService.recordLosses(playerId, x, y, initial, survivors, commander, now)
                : woundedService.recordOutboundLosses(playerId, outbound, initial, survivors, commander, now);
        int recoveredCount = injured.values().stream().mapToInt(Integer::intValue).sum();
        int losses = countCombatLosses(initial, survivors);
        if ("攻方".equals(side)) {
            result.setAttackerRecoveryPercent(recoveryPercent);
            result.setAttackerRecoveredCount(recoveredCount);
            result.setAttackerLosses(losses);
        } else {
            result.setDefenderRecoveryPercent(recoveryPercent);
            result.setDefenderRecoveredCount(recoveredCount);
            result.setDefenderLosses(losses);
        }
        if (!injured.isEmpty()) {
            StringJoiner names = new StringJoiner("，");
            injured.forEach((unit, count) -> names.add(GameData.UNITS.get(unit).name() + "×" + count));
            result.setReport(result.getReport() + "\n" + side + "伤兵入营：" + names + "（7天内可付费治疗）\n");
        }
    }

    /**
     * 结算某一真实玩家在战斗中的声望和民心，并把结果锁定到当前战报。
     * 声望按双方作战单位的生产成本折算后的净损失计算；胜利方民心上升，守城失败方民心下降，进攻失败不影响出发城民心。
     * 城防损失不进入声望或伤兵计算，声望扣减不会使现有声望低于 0。
     * @param playerId 战报接收者的玩家 ID
     * @param march 当前行军；来袭防守战可能为空
     * @param attacker 该玩家是否为进攻方
     * @param result 已完成的战斗结果
     */
    private void settleBattleAwards(Long playerId, March march, boolean attacker, BattleResult result) {
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

        int moraleChange = attacker && !won ? 0 : (won ? 1 : -2);
        CityEconomy economy = battleEconomy(playerId, march, attacker);
        int moraleAfter = 0;
        if (economy != null) {
            moraleAfter = Math.max(0, Math.min(100, Objects.requireNonNullElse(economy.getMorale(), 70) + moraleChange));
            economy.setMorale(moraleAfter);
            saveBattleEconomy(economy);
        }
        if (attacker) {
            result.setAttackerPrestigeChange(prestigeChange);
            result.setAttackerPrestigeAfter(prestigeAfter);
            result.setAttackerMoraleChange(moraleChange);
            result.setAttackerMoraleAfter(moraleAfter);
        } else {
            result.setDefenderPrestigeChange(prestigeChange);
            result.setDefenderPrestigeAfter(prestigeAfter);
            result.setDefenderMoraleChange(moraleChange);
            result.setDefenderMoraleAfter(moraleAfter);
        }
    }

    /**
     * 按敌我作战单位的生产成本折算值计算本方声望变化。
     * 火箭、重坦和舰艇的价值高于步兵，守方变化始终与攻方相反。
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
            if (!GameData.UNITS.containsKey(entry.getKey())) continue;
            int start = Math.max(0, Objects.requireNonNullElse(entry.getValue(), 0));
            int alive = survivors == null ? 0 : Math.max(0, survivors.getOrDefault(entry.getKey(), 0));
            losses += Math.max(0, start - alive);
        }
        return losses;
    }

    /** 选择本次战斗实际发生的城市经济体，兼容主城、分城和来袭防守。 */
    private CityEconomy battleEconomy(Long playerId, March march, boolean attacker) {
        if (march != null) {
            Integer x = attacker ? march.getFromX() : march.getTargetX();
            Integer y = attacker ? march.getFromY() : march.getTargetY();
            for (PlayerCity city : playerCityRepository.findByOwnerId(playerId)) {
                if (Objects.equals(city.getX(), x) && Objects.equals(city.getY(), y)) return city;
            }
        }
        try {
            return cityScope.economy(playerId);
        } catch (IllegalArgumentException ignored) {
            return playerRepository.findById(playerId).orElse(null);
        }
    }

    /** 按经济体的实际实体类型保存民心变化。 */
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
     * 按战报接收者的攻守身份保存胜负，同时保留原始攻守数据供前端正确映射敌我双方。
     */
    private void pushBattleReport(Long playerId, BattleResult result, March m,
                                  Officer attackerCommander, Officer defenderCommander) {
        pushBattleReport(playerId, result, m, attackerCommander, defenderCommander, false);
    }

    private void pushBattleReport(Long playerId, BattleResult result, March m,
                                  Officer attackerCommander, Officer defenderCommander, boolean wildConquered) {
        long now = System.currentTimeMillis();
        String targetName = m != null && m.getTargetName() != null && !m.getTargetName().isBlank()
                ? m.getTargetName() : "目标";
        String targetKind = m != null && m.getTargetKind() != null ? m.getTargetKind() : "target";
        String action = m != null && m.getAction() != null ? m.getAction() : "conquer";
        boolean isCityConquered = result.isCityConquered();
        boolean conquered = isCityConquered || wildConquered;
        boolean defenderPerspective = m == null || !playerId.equals(m.getPlayerId());
        boolean attackerWin = result.isWin();
        boolean recipientWin = defenderPerspective ? !attackerWin : attackerWin;
        String perspective = defenderPerspective ? "defender" : "attacker";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "battle");
        report.put("perspective", perspective);
        report.put("attackerWin", attackerWin);
        report.put("win", recipientWin);
        report.put("readAt", 0L);
        report.put("time", now);
        report.put("subject", defenderPerspective
                ? buildDefenseBattleSubject(recipientWin, action, targetName)
                : buildBattleSubject(attackerWin, conquered, action, targetKind, targetName));
        report.put("targetType", targetKind);
        report.put("action", action);
        report.put("attackerName", m != null ? playerRepository.findById(m.getPlayerId())
                .map(Player::getUsername).orElse("未知敌军") : "未知敌军");
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
        report.put("losses", defenderPerspective ? result.getDefenderLosses() : result.getAttackerLosses());
        report.put("recoveryPercent", defenderPerspective ? result.getDefenderRecoveryPercent() : result.getAttackerRecoveryPercent());
        report.put("recoveredCount", defenderPerspective ? result.getDefenderRecoveredCount() : result.getAttackerRecoveredCount());
        report.put("report", result.getReport());
        report.put("cityConquered", isCityConquered);
        report.put("wildConquered", wildConquered);
        report.put("conquered", conquered);
        report.put("roundLogs", splitReportLines(result.getReport()));
        Map<String, Object> commanders = new LinkedHashMap<>();
        commanders.put("attacker", buildCommanderReport(attackerCommander));
        commanders.put("defender", buildCommanderReport(defenderCommander));
        report.put("commanders", commanders);

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

    private Map<String, Object> buildCommanderReport(Officer commander) {
        if (commander == null) return null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", commander.getName() != null ? commander.getName() : "未命名将领");
        data.put("level", commander.getLevel() != null ? commander.getLevel() : 0);
        data.put("baseMilitary", commander.getMilitary() != null ? commander.getMilitary() : 0);
        data.put("military", equipmentService.attributes(commander).military());

        List<Map<String, Object>> skills = new ArrayList<>();
        if (commander.getSkills() != null && !commander.getSkills().isBlank()) {
            for (Map<String, Object> raw : JsonUtil.parseList(commander.getSkills())) {
                Object rawId = raw.get("id");
                Object rawLevel = raw.get("lv");
                if (rawId == null || !(rawLevel instanceof Number level)) continue;

                String skillId = rawId.toString();
                OfficerSkillDef definition = OfficerSkillDef.getSkill(skillId);
                Map<String, Object> skill = new LinkedHashMap<>();
                skill.put("name", definition != null ? definition.name() : skillId);
                skill.put("level", level.intValue());
                skill.put("description", definition != null
                        ? definition.descriptionAtLevel(level.intValue()) : "");
                skills.add(skill);
            }
        }
        data.put("skills", skills);
        return data;
    }

    private String buildBattleSubject(boolean win, boolean conquered, String action, String targetKind, String targetName) {
        String act;
        if ("bandit".equals(targetKind)) {
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
        if (conquered) {
            if ("wild".equals(targetKind)) {
                return "占领野地胜利·已占领 " + targetName;
            }
            return act + "胜利·已征服 " + targetName;
        }
        return (win ? act + "胜利 " : act + "失败 ") + targetName;
    }

    /** 生成守城方视角标题，避免把来袭方的进攻结果描述成我方出征。 */
    private String buildDefenseBattleSubject(boolean win, String action, String targetName) {
        String act = "plunder".equals(action) ? "掠夺防守" : "征服防守";
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
                    .map(p -> (p.getCityName() != null && !p.getCityName().isBlank()) ? p.getCityName() : p.getUsername())
                    .orElse("我方");
        } catch (Exception e) {
            return "我方";
        }
    }

    /**
     * 判断征服或掠夺玩家城的即时权限。
     * 敌对军团永久直通；友好军团禁止作战；中立玩家仍必须处于有效的个人战争期。
     */
    private boolean canAttackPlayerCity(Long attackerId, PlayerCity defenderCity, long now) {
        Long defenderId = defenderCity.getOwnerId();
        if (defenderId == null || !targets.hasRealOwner(defenderCity)) return true;
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
        returnArmy(playerId, JsonUtil.parseIntMap(march.getArmy()));
        Map<String, Integer> carryRes = JsonUtil.parseIntMap(march.getCarryRes());
        if (!carryRes.isEmpty()) addResources(playerId, carryRes);
    }

    private boolean isOffensiveAction(String action) {
        return "conquer".equals(action) || "plunder".equals(action);
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 计算部队负重 - 对应 JS World.calcDispatchLoad (简化版, 不含科技加成)
     */
    public int calcArmyLoad(Map<String, Integer> army) {
        if (army == null || army.isEmpty()) return 0;
        double total = 0;
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            UnitDef u = GameData.UNITS.get(entry.getKey());
            if (u != null && u.load() != null && u.load() > 0) {
                total += entry.getValue() * u.load();
            }
        }
        return (int) Math.floor(total);
    }

    /**
     * 按单程距离汇总行军油耗，并按任务所需的趟数预扣，使返程不受资源库存变化影响。
     * 出征不消耗粮食，粮食库存不会影响部队是否可以出发。
     *
     * @param army 按兵种键聚合的出征数量
     * @param distance 单程路线格数
     * @param legs 需要预扣的行军趟数
     * @return 整数石油需求
     */
    private int calcMarchFuel(Map<String, Integer> army, int distance, int legs) {
        double oil = 0;
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            UnitDef unit = GameData.UNITS.get(entry.getKey());
            int count = Math.max(0, entry.getValue() != null ? entry.getValue() : 0);
            if (unit == null || count == 0) continue;
            oil += (double) count * unit.marchOil() * Math.max(0, distance) / 100;
        }
        int routeLegs = Math.max(1, legs);
        // 汇总后向上取整，确保少量兵力也会携带完整的最低补给包。
        return (int) Math.ceil(oil * routeLegs);
    }

    /**
     * 曼哈顿距离 - 对应 JS world.js dist(x1,y1,x2,y2)
     */
    public int dist(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    /**
     * 开始返程行军 - 设置 returning=true, 交换坐标, 计算返程时间
     */
    private void startReturnMarch(March m, long now) {
        long arriveAt = m.getArriveAt() != null ? m.getArriveAt() : 0L;
        long startAt = m.getStartAt() != null ? m.getStartAt() : 0L;
        long marchDur = Math.max(1000L, arriveAt - startAt);
        // 记录原目标信息用于前端展示, 坐标交换后 originX/Y/targetX/Y 顺序变了
        m.setOriginName(m.getTargetName());
        m.setOriginX(m.getTargetX());
        m.setOriginY(m.getTargetY());
        m.setReturning(true);
        swapCoords(m);
        m.setStartAt(now);
        m.setArriveAt(now + marchDur);
        // 返城的目标名就是玩家主城
        if (m.getPlayerId() != null) {
            m.setTargetName(cityScope.economy(m.getPlayerId()).getCityName());
        }
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
            case 1 -> "工事侦测";
            case 2 -> "战术全貌";
            case 3 -> "工业设施";
            case 4 -> "电磁与科研";
            default -> "全维绝密";
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
                UnitDef u = GameData.UNITS.get(e.getKey());
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

    private void returnArmy(Long playerId, Map<String, Integer> army) {
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

    private void addResources(Long playerId, Map<String, Integer> resMap) {
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
            switch (entry.getKey()) {
                case "food" -> res.setFood((res.getFood() != null ? res.getFood() : 0) + amount);
                case "steel" -> res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + amount);
                case "oil" -> res.setOil((res.getOil() != null ? res.getOil() : 0) + amount);
                case "rare" -> res.setRare((res.getRare() != null ? res.getRare() : 0) + amount);
                case "gold" -> res.setGold((res.getGold() != null ? res.getGold() : 0) + amount);
            }
        }
        resourcesRepository.save(res);
    }

    private int getResourceAmount(Resources res, String key) {
        return switch (key) {
            case "food" -> res.getFood() != null ? res.getFood() : 0;
            case "steel" -> res.getSteel() != null ? res.getSteel() : 0;
            case "oil" -> res.getOil() != null ? res.getOil() : 0;
            case "rare" -> res.getRare() != null ? res.getRare() : 0;
            case "gold" -> res.getGold() != null ? res.getGold() : 0;
            default -> 0;
        };
    }

    private void deductResource(Resources res, String key, int amount) {
        if (amount <= 0) return;
        switch (key) {
            case "food" -> res.setFood(Math.max(0, (res.getFood() != null ? res.getFood() : 0) - amount));
            case "steel" -> res.setSteel(Math.max(0, (res.getSteel() != null ? res.getSteel() : 0) - amount));
            case "oil" -> res.setOil(Math.max(0, (res.getOil() != null ? res.getOil() : 0) - amount));
            case "rare" -> res.setRare(Math.max(0, (res.getRare() != null ? res.getRare() : 0) - amount));
            case "gold" -> res.setGold(Math.max(0, (res.getGold() != null ? res.getGold() : 0) - amount));
        }
    }

    /** 应用守方损失 - 分别更新玩家部队和城防 */
    private void applyDefenderLosses(Long playerId, Map<String, Integer> survivors) {
        // 更新部队
        List<ArmyUnit> armyUnits = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, ArmyUnit> armyMap = new LinkedHashMap<>();
        for (ArmyUnit au : armyUnits) armyMap.put(au.getType(), au);

        for (String unitType : GameData.UNITS.keySet()) {
            int survivorCount = survivors.getOrDefault(unitType, 0);
            ArmyUnit au = armyMap.get(unitType);
            if (au != null) {
                au.setCount(survivorCount);
                armyUnitRepository.save(au);
            } else if (survivorCount > 0) {
                ArmyUnit newAu = new ArmyUnit();
                newAu.setPlayerId(playerId);
                newAu.setCitySlot(cityScope.slot(playerId));
                newAu.setType(unitType);
                newAu.setCount(survivorCount);
                armyUnitRepository.save(newAu);
            }
        }

        // 更新城防
        List<Fortification> forts = fortificationRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, Fortification> fortMap = new LinkedHashMap<>();
        for (Fortification f : forts) fortMap.put(f.getType(), f);

        for (String fortType : GameData.FORTS.keySet()) {
            int survivorCount = survivors.getOrDefault(fortType, 0);
            Fortification f = fortMap.get(fortType);
            if (f != null) {
                f.setCount(survivorCount);
                fortificationRepository.save(f);
            } else if (survivorCount > 0) {
                Fortification newF = new Fortification();
                newF.setPlayerId(playerId);
                newF.setCitySlot(cityScope.slot(playerId));
                newF.setType(fortType);
                newF.setCount(survivorCount);
                fortificationRepository.save(newF);
            }
        }
    }
}
