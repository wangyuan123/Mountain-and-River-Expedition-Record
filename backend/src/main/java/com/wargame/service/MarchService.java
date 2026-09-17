package com.wargame.service;

import com.wargame.model.constants.FortDef;
import com.wargame.model.constants.GameData;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.OfficerSkillDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.WorldConfig;
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

    @org.springframework.beans.factory.annotation.Autowired private AccountService accounts;

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
    private final WebSocketPushService pushService;
    private final PlayerItemRepository playerItemRepository;
    private final com.wargame.service.quest.QuestService questService;

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
                        @Lazy WebSocketPushService pushService,
                        EquipmentService equipmentService,
                        PlayerItemRepository playerItemRepository,
                        com.wargame.service.quest.QuestService questService) {
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
        this.pushService = pushService;
        this.playerItemRepository = playerItemRepository;
        this.questService = questService;
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
            if (!returning) {
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

            // 玩家城保护期检查 - 对应 JS target.coolAt > now
            if ("player".equals(targetKind) && target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
                long warEndAt = pc.getWarEndAt() != null ? pc.getWarEndAt() : 0L;
                long warAt = pc.getWarAt() != null ? pc.getWarAt() : 0L;
                if (warEndAt > 0 && warEndAt > now && (warAt == 0 || now < warAt)) {
                    marchRepository.delete(m);
                    returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                    Map<String, Integer> carryRes = JsonUtil.parseIntMap(m.getCarryRes());
                    if (!carryRes.isEmpty()) {
                        addResources(playerId, carryRes);
                    }
                    continue;
                }
            }

            notifyIncomingChange(m, "resolved");
            String action = m.getAction() != null ? m.getAction() : "conquer";
            if (target instanceof PlayerCity pc && targets.hasRealOwner(pc)) {
                try (var ignored = cityScope.enter(pc)) { resolveTarget(playerId, m, target, now, action); }
            } else resolveTarget(playerId, m, target, now, action);
        }
        for (Officer officer : officerRepository.findByPlayerIdAndCitySlotAndRole(playerId, cityScope.slot(playerId), "march")) {
            if (!marchRepository.existsByPlayerIdAndCommanderId(playerId, officer.getId())) { officer.setRole("idle"); officerRepository.save(officer); }
        }
    }

    private void resolveTarget(Long playerId, March march, Object target, long now, String action) {
        if ("scout".equals(action)) resolveScout(playerId, march, target, now);
        else resolveAttack(playerId, march, target, now);
    }

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
        }
        if (req.commanderId() != null) {
            Officer officer = targets.getOfficerById(playerId, req.commanderId());
            if (officer == null || officer.getCitySlot() != cityScope.slot(playerId)) throw new IllegalArgumentException("军官不在当前城市");
            if ("mayor".equals(officer.getRole())) throw new IllegalArgumentException("请先解除市长任命再出征");
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

        // 3. 验证携带资源
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

            Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
            if (res != null) {
                for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                    int have = getResourceAmount(res, rk);
                    if (carryRes.get(rk) > have) throw new IllegalArgumentException("资源不足: " + rk);
                }
                for (String rk : List.of("food", "steel", "oil", "rare", "gold")) {
                    deductResource(res, rk, carryRes.get(rk));
                }
                resourcesRepository.save(res);
            }
        }

        // 4. 扣除兵力
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

        // 5. 计算行军距离和时间
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
        int marchSec = (int) Math.ceil((double) marchDist * secPerGrid / (spd * speedMul));
        if (marchSec < 1) marchSec = 1;

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

    private void resolveAttack(Long playerId, March m, Object target, long now) {
        Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());

        // 攻方信息
        Map<String, Integer> attackerTech = targets.getTechMap(playerId);
        Officer commander = m.getCommanderId() != null
                ? targets.getOfficerById(playerId, m.getCommanderId())
                : targets.getCommander(playerId);
        int attackerCommanderMil = commander != null ? equipmentService.attributes(commander).military() : 0;
        Map<String, Integer> attackerSkills = targets.getCommanderSkills(commander);
        Officer defenderCommander = target instanceof PlayerCity pc && pc.getOwnerId() != null
                ? targets.getCommander(pc.getOwnerId()) : null;

        // 守方信息
        Map<String, Integer> defenderArmy = targets.getTargetArmy(target);
        Map<String, Integer> defenderForts = targets.getTargetForts(target);
        Map<String, Integer> defenderResources = targets.getTargetResources(target);
        String action = m.getAction() != null ? m.getAction() : "conquer";

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
        int defenderCommanderMil = defenderCommander != null ? equipmentService.attributes(defenderCommander).military() : 0;
        int defenderWallLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "wall") : 0;
        long defenderWarehouseLevel = defenderPlayerId != null ? targets.buildingLevel(defenderPlayerId, "depot") : 0;

        BattleResult result = battleService.startWorldDispatch(
                armyMap, defenderArmy, defenderForts,
                attackerTech, defenderTech,
                attackerSkills, defenderSkills,
                attackerCommanderMil, defenderCommanderMil,
                0, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel,
                isPlayerBattle);
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
                if (result.isCityConquered()) {
                    nc.setDefeated(true);
                    nc.setArmy("{}");
                    nc.setForts("{}");
                } else {
                    nc.setArmy(JsonUtil.toJson(remainingArmy));
                    nc.setForts(JsonUtil.toJson(remainingForts));
                }
                npcCityRepository.save(nc);
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

        // 有幸存者则开始返程，否则删除
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
        int scoutSum = scoutInfo.atk() + scoutInfo.def() + scoutInfo.hp();
        int myPower = myScouts * scoutSum;
        int enemyPower = enemyScouts * scoutSum;
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
                    int myAtk = myRemain * scoutInfo.atk();
                    enemyRemain -= Math.min(enemyRemain, (int) Math.floor((double) myAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    if (enemyRemain > 0) {
                        int enemyAtk = enemyRemain * scoutInfo.atk();
                        myRemain -= Math.min(myRemain, (int) Math.floor((double) enemyAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    }
                } else {
                    int enemyAtk = enemyRemain * scoutInfo.atk();
                    myRemain -= Math.min(myRemain, (int) Math.floor((double) enemyAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    if (myRemain > 0) {
                        int myAtk = myRemain * scoutInfo.atk();
                        enemyRemain -= Math.min(enemyRemain, (int) Math.floor((double) myAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
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
                if (myRemain >= enemyRemain) {
                    scoutResult = "close_match_win";
                    showCityInfo = true;
                } else {
                    scoutResult = "close_match_loss";
                    showCityInfo = false;
                }
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
        int defenderCommanderMil = commander != null ? equipmentService.attributes(commander).military() : 0;
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
                0, defenderCommanderMil,
                0, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel,
                true);
        recordBattleWounded(playerId, null, null, null, result.getInitialDefender(),
                result.getSurvivorDefender(), commander, now, result, "守方");
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
        Map<String, Integer> injured = outbound == null
                ? woundedService.recordLosses(playerId, x, y, initial, survivors, commander, now)
                : woundedService.recordOutboundLosses(playerId, outbound, initial, survivors, commander, now);
        if (!injured.isEmpty()) {
            StringJoiner names = new StringJoiner("，");
            injured.forEach((unit, count) -> names.add(GameData.UNITS.get(unit).name() + "×" + count));
            result.setReport(result.getReport() + "\n" + side + "伤兵入营：" + names + "（7天内可付费治疗）\n");
        }
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

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "battle");
        report.put("win", result.isWin());
        report.put("readAt", 0L);
        report.put("time", now);
        report.put("subject", buildBattleSubject(result.isWin(), conquered, action, targetKind, targetName));
        report.put("targetType", targetKind);
        report.put("action", action);
        report.put("attackerName", m != null ? playerRepository.findById(m.getPlayerId())
                .map(Player::getUsername).orElse("未知敌军") : "未知敌军");
        report.put("fromName", playerCityName(playerId));
        report.put("fromCoord", (m != null && m.getFromX() != null ? m.getFromX() : 0) + "," + (m != null && m.getFromY() != null ? m.getFromY() : 0));
        report.put("toName", targetName);
        report.put("toCoord", (m != null && m.getTargetX() != null ? m.getTargetX() : 0) + "," + (m != null && m.getTargetY() != null ? m.getTargetY() : 0));
        report.put("survivorAttacker", result.getSurvivorAttacker());
        report.put("survivorDefender", result.getSurvivorDefender());
        report.put("initialAttacker", result.getInitialAttacker());
        report.put("initialDefender", result.getInitialDefender());
        report.put("plunder", result.getPlunderedResources());
        report.put("exp", result.getExpGained());
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
                OfficerSkillDef definition = OfficerSkillDef.OFFICER_SKILLS.get(skillId);
                Map<String, Object> skill = new LinkedHashMap<>();
                skill.put("name", definition != null ? definition.name() : skillId);
                skill.put("level", level.intValue());
                skill.put("description", definition != null ? definition.desc() : "");
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
                    power += (long) e.getValue() * (u.atk() + u.def() + u.hp());
                }
            }
        }
        if (forts != null) {
            for (Map.Entry<String, Integer> e : forts.entrySet()) {
                FortDef f = GameData.FORTS.get(e.getKey());
                if (f != null) {
                    power += (long) e.getValue() * (f.atk() + f.hp());
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
