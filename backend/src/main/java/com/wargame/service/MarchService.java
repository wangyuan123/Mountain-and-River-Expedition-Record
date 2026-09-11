package com.wargame.service;

import com.wargame.model.constants.GameData;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 行军服务 - 对应 JS core.js processMarches / processIncoming 和 world.js launchDispatch。
 * <p>
 * 处理所有行军（部队移动）逻辑：采集、征服野地、攻击流寇/NPC城/玩家城与侦查。
 */
@Service
public class MarchService {

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
    private final com.wargame.service.quest.QuestService questService;

    public MarchService(MarchRepository marchRepository,
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
                        com.wargame.service.quest.QuestService questService) {
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
        this.questService = questService;
    }

    // ========================================================================
    // processMarches - 对应 JS core.js processMarches(now)
    // ========================================================================

    @Transactional
    public void processMarches(Long playerId, long now) {
        List<March> marches = marchRepository.findByPlayerId(playerId);
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

            // 3a. 采集返程到达 -> 收取资源
            if ("wild_gather".equals(targetKind) && returning) {
                marchRepository.delete(m);
                WildTile gTile = findWildTileById(m.getTargetId());
                if (gTile != null) {
                    int mined = gTile.getMined() != null ? gTile.getMined() : 0;
                    int gatherAmount = m.getGatherAmount() != null ? m.getGatherAmount() : 0;
                    gTile.setMined(mined + gatherAmount);
                    wildTileRepository.save(gTile);
                }
                String gatherRes = m.getGatherRes();
                int gatherAmount = m.getGatherAmount() != null ? m.getGatherAmount() : 0;
                if (gatherRes != null && gatherAmount > 0) {
                    addResources(playerId, Map.of(gatherRes, gatherAmount));
                }
                try { questService.onEvent(playerId, "GATHER_COMPLETE", gatherRes, 1); } catch (Exception ignored) {}
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                pushService.pushMarchUpdate(playerId, marchEvent("returned", m,
                        resourceEvent(m.getGatherRes(), gatherAmount)));
                continue;
            }

            // 3b. 采集部队首次到达 -> 开始采集
            if ("wild_gather".equals(targetKind) && !returning && !gathering) {
                WildTile aTile = findWildTileById(m.getTargetId());
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
                Officer gatherCmd = getOfficerById(playerId, m.getCommanderId());
                if (gatherCmd != null) {
                    gatherSpeedMul += equipmentService.attributes(gatherCmd).logistics() / 100.0;
                }
                if (gatherSpeedMul > 3.0) gatherSpeedMul = 3.0;
                long gatherDuration = (long) Math.max(60L, Math.ceil(gatherAmount / (10.0 * gatherSpeedMul))) * 1000;
                m.setGatherEndAt(now + gatherDuration);
                marchRepository.save(m);
                continue;
            }

            // 3c. 征服/掠夺野地
            if ("wild".equals(targetKind) && !returning) {
                WildTile cTile = findWildTileById(m.getTargetId());
                if (cTile == null || Boolean.TRUE.equals(cTile.getOccupied())) {
                    marchRepository.delete(m);
                    returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                    continue;
                }
                Map<String, Integer> garrison = JsonUtil.parseIntMap(cTile.getGarrison());
                Map<String, Integer> armyMap = JsonUtil.parseIntMap(m.getArmy());
                BattleResult result = battleService.resolveWild(garrison, armyMap);
                pushBattleReport(playerId, result, m, getCommander(playerId), null);
                m.setArmy(JsonUtil.toJson(result.getSurvivorAttacker()));
                if (result.isWin()) {
                    String wildAction = m.getAction() != null ? m.getAction() : "conquer";
                    if ("plunder".equals(wildAction)) {
                        // 掠夺: 不占领野地, 根据幸存部队负重掠夺资源
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
                                    wildTileRepository.save(cTile);
                                }
                            }
                        }
                    } else {
                        // 征服: 占领野地
                        cTile.setOccupied(true);
                        cTile.setScouted(true);
                        cTile.setOccupiedBy(playerId);
                        wildTileRepository.save(cTile);
                        // 主线任务进度钩子: 占领野地
                        try { questService.onEvent(playerId, "WILD_CLAIM", null, 1); } catch (Exception ignored) {}
                    }
                }
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

            // 3e. 攻击/侦查流寇、NPC城、玩家城 - 查找目标
            Object target = findTargetById(targetKind, m.getTargetId());
            if (target == null || isDefeated(target)) {
                marchRepository.delete(m);
                returnArmy(playerId, JsonUtil.parseIntMap(m.getArmy()));
                Map<String, Integer> carryRes = JsonUtil.parseIntMap(m.getCarryRes());
                if (!carryRes.isEmpty()) {
                    addResources(playerId, carryRes);
                }
                continue;
            }

            // 玩家城保护期检查 - 对应 JS target.coolAt > now
            if ("player".equals(targetKind) && target instanceof PlayerCity pc && hasRealOwner(pc)) {
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

            String action = m.getAction() != null ? m.getAction() : "conquer";
            if ("scout".equals(action)) {
                resolveScout(playerId, m, target, now);
            } else {
                resolveAttack(playerId, m, target, now);
            }
        }
    }

    @Transactional
    public void cancelMarch(Long playerId, Long marchId) {
        March march = marchRepository.findById(marchId)
                .orElseThrow(() -> new IllegalArgumentException("行军任务不存在"));
        if (!playerId.equals(march.getPlayerId())) {
            throw new IllegalArgumentException("无权取消该行军任务");
        }
        if (Boolean.TRUE.equals(march.getReturning()) || Boolean.TRUE.equals(march.getGathering())) {
            throw new IllegalArgumentException("行军已进入返程或采集阶段，无法取消");
        }
        returnArmy(playerId, JsonUtil.parseIntMap(march.getArmy()));
        addResources(playerId, JsonUtil.parseIntMap(march.getCarryRes()));
        marchRepository.delete(march);
    }

    // ========================================================================
    // createDispatch - 对应 JS world.js launchDispatch
    // ========================================================================

    @Transactional
    public March createDispatch(Long playerId, DispatchRequest req) {
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) throw new IllegalArgumentException("玩家不存在");

        String kind = req.targetKind();
        String action = req.action() != null ? req.action() : "conquer";
        boolean isGather = "wild_gather".equals(kind);
        boolean isScout = "scout".equals(action);

        // 1. 查找目标
        Object target = findTargetByLongId(kind, req.targetId());
        if (target == null) throw new IllegalArgumentException("目标不存在");
        if ("player".equals(kind) && target instanceof PlayerCity pc && !hasRealOwner(pc)) {
            throw new IllegalArgumentException("该城市为模拟 NPC，请使用 simulated_npc 目标类型");
        }
        if (!isGather && isDefeated(target)) throw new IllegalArgumentException("目标已被击败");

        int tx = getTargetX(target);
        int ty = getTargetY(target);

        // 2. 验证并配置兵力
        Map<String, Integer> reqArmy = req.army() != null ? req.army() : Collections.emptyMap();
        Map<String, Integer> customArmy = new LinkedHashMap<>();
        int slowestSpd = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : reqArmy.entrySet()) {
            String uid = entry.getKey();
            int n = entry.getValue() != null ? entry.getValue() : 0;
            if (n <= 0) continue;
            UnitDef u = GameData.UNITS.get(uid);
            if (u == null) continue;
            if (isScout && !"scout".equals(uid)) continue;
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndType(playerId, uid);
            int max = existing.isEmpty() ? 0 : (existing.get(0).getCount() != null ? existing.get(0).getCount() : 0);
            if (n > max) n = max;
            if (n <= 0) continue;
            customArmy.put(uid, n);
            if (u.spd() < slowestSpd) slowestSpd = u.spd();
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
        }

        // 3. 验证携带资源
        Map<String, Integer> carryRes = new LinkedHashMap<>();
        for (String rk : List.of("food", "steel", "oil", "rare")) carryRes.put(rk, 0);

        if (!isGather && !isScout) {
            if (req.carryRes() != null) {
                for (Map.Entry<String, Integer> entry : req.carryRes().entrySet()) {
                    String rk = entry.getKey();
                    if (carryRes.containsKey(rk)) {
                        carryRes.put(rk, Math.max(0, entry.getValue() != null ? entry.getValue() : 0));
                    }
                }
            }
            int load = calcArmyLoad(customArmy);
            int totalCarry = carryRes.values().stream().mapToInt(Integer::intValue).sum();
            if (totalCarry > load) throw new IllegalArgumentException("携带资源超出负重上限 " + load);

            Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
            if (res != null) {
                for (String rk : List.of("food", "steel", "oil", "rare")) {
                    int have = getResourceAmount(res, rk);
                    if (carryRes.get(rk) > have) throw new IllegalArgumentException("资源不足: " + rk);
                }
                for (String rk : List.of("food", "steel", "oil", "rare")) {
                    deductResource(res, rk, carryRes.get(rk));
                }
                resourcesRepository.save(res);
            }
        }

        // 4. 扣除兵力
        for (Map.Entry<String, Integer> entry : customArmy.entrySet()) {
            String uid = entry.getKey();
            int n = entry.getValue();
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndType(playerId, uid);
            if (!existing.isEmpty()) {
                ArmyUnit unit = existing.get(0);
                int current = unit.getCount() != null ? unit.getCount() : 0;
                unit.setCount(Math.max(0, current - n));
                armyUnitRepository.save(unit);
            }
        }

        // 5. 计算行军距离和时间
        int px = player.getPosX() != null ? player.getPosX() : 0;
        int py = player.getPosY() != null ? player.getPosY() : 0;
        int marchDist = dist(px, py, tx, ty);
        int secPerGrid = WorldConfig.MARCH_SEC_PER_GRID;
        int spd = Math.max(1, slowestSpd);
        long now = System.currentTimeMillis();
        double speedMul = 1.0;
        CityState cs = cityStateRepository.findByPlayerId(playerId).orElse(null);
        if (cs != null && cs.getMarchBoostUntil() != null && cs.getMarchBoostUntil() > now) {
            speedMul = 1.5;
        }
        int marchSec = (int) Math.ceil((double) marchDist * secPerGrid / (spd * speedMul));
        if (marchSec < 1) marchSec = 1;

        // 6. 创建行军
        March march = new March();
        march.setPlayerId(playerId);
        march.setTargetKind(kind);
        march.setTargetId(String.valueOf(req.targetId()));
        march.setTargetName(getTargetName(target));
        march.setTargetX(tx);
        march.setTargetY(ty);
        march.setFromX(px);
        march.setFromY(py);
        march.setDistance(marchDist);
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

        marchRepository.save(march);

        return march;
    }

    // ========================================================================
    // processIncoming - 对应 JS core.js processIncoming(now)
    // ========================================================================

    @Transactional
    public void processIncoming(Long playerId, long now) {
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
        Map<String, Integer> attackerTech = getTechMap(playerId);
        Officer commander = getCommander(playerId);
        int attackerCommanderMil = commander != null ? equipmentService.attributes(commander).military() : 0;
        Map<String, Integer> attackerSkills = getCommanderSkills(commander);
        Officer defenderCommander = target instanceof PlayerCity pc && pc.getOwnerId() != null
                ? getCommander(pc.getOwnerId()) : null;

        // 守方信息
        Map<String, Integer> defenderArmy = getTargetArmy(target);
        Map<String, Integer> defenderForts = getTargetForts(target);
        Map<String, Integer> defenderResources = getTargetResources(target);
        String action = m.getAction() != null ? m.getAction() : "conquer";

        // 流寇奖励来自 WorldConfig
        if (target instanceof Bandit bandit) {
            int level = bandit.getLevel() != null ? bandit.getLevel() : 1;
            if (level >= 1 && level <= WorldConfig.BANDIT_LEVELS.size()) {
                defenderResources = new LinkedHashMap<>(WorldConfig.BANDIT_LEVELS.get(level - 1).reward());
            }
        }

        BattleResult result = battleService.startWorldDispatch(
                armyMap, defenderArmy, defenderForts,
                attackerTech, Collections.emptyMap(),
                attackerSkills, Collections.emptyMap(),
                attackerCommanderMil, 0,
                0, 0,
                action, defenderResources, 0);
        pushBattleReport(playerId, result, m, commander, defenderCommander);

        // 更新行军部队为幸存者
        Map<String, Integer> survivors = result.getSurvivorAttacker();
        if (survivors == null) survivors = Collections.emptyMap();
        m.setArmy(JsonUtil.toJson(survivors));

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

            // 标记目标
            if (target instanceof Bandit b) {
                b.setDefeated(true);
                banditRepository.save(b);
                // 主线任务进度钩子: 击败流寇
                try { questService.onEvent(playerId, "BANDIT_DEFEAT", null, 1); } catch (Exception ignored) {}
            } else if (target instanceof NpcCity nc) {
                if (result.isCityConquered()) {
                    nc.setDefeated(true);
                    npcCityRepository.save(nc);
                }
            } else if (target instanceof PlayerCity pc) {
                if (hasRealOwner(pc)) {
                    if (result.isCityConquered()) {
                        pc.setOwnerId(playerId);
                        pc.setWarEndAt(now + 30 * 60 * 1000L);
                        playerCityRepository.save(pc);
                    }
                    // 主线任务进度钩子: 玩家对玩家主城战斗（攻方胜 / 败均算参战）
                    try { questService.onEvent(playerId, "PLAYER_WIN", null, 1); } catch (Exception ignored) {}
                } else if (result.isCityConquered()) {
                    pc.setWarEndAt(now + 30 * 60 * 1000L);
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

        Map<String, Integer> enemyArmy = getTargetArmy(target);
        int enemyScouts = enemyArmy.getOrDefault("scout", 0);

        UnitDef scoutInfo = GameData.UNITS.get("scout");
        int scoutSum = scoutInfo.atk() + scoutInfo.def() + scoutInfo.hp();
        int myPower = myScouts * scoutSum;
        int enemyPower = enemyScouts * scoutSum;
        double ratio = enemyPower > 0 ? (double) myPower / enemyPower : 999;

        int myLost;
        int enemyLost;
        String scoutResult;
        boolean showCityInfo;

        if (ratio < 0.3) {
            myLost = myScouts;
            enemyLost = 0;
            scoutResult = "overwhelming_defeat";
            showCityInfo = false;
        } else if (ratio > 3) {
            myLost = (int) Math.ceil(myScouts * 0.1);
            enemyLost = enemyScouts;
            scoutResult = "overwhelming_victory";
            showCityInfo = true;
            setTargetScoutCount(target, 0);
        } else {
            int rounds = 0;
            int myRemain = myScouts;
            int enemyRemain = enemyScouts;
            while (myRemain > 0 && enemyRemain > 0 && rounds < 10) {
                rounds++;
                int myAtk = myRemain * scoutInfo.atk();
                int enemyCasualty = Math.min(enemyRemain, (int) Math.floor((double) myAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                enemyRemain -= enemyCasualty;
                if (enemyRemain > 0) {
                    int enemyAtk = enemyRemain * scoutInfo.atk();
                    int myCasualty = Math.min(myRemain, (int) Math.floor((double) enemyAtk / (scoutInfo.def() + scoutInfo.hp()) + 1));
                    myRemain -= myCasualty;
                }
            }
            myLost = myScouts - Math.max(0, myRemain);
            enemyLost = enemyScouts - Math.max(0, enemyRemain);
            if (myRemain > 0) {
                scoutResult = "close_match_win";
                showCityInfo = true;
                setTargetScoutCount(target, 0);
            } else {
                scoutResult = "close_match_loss";
                showCityInfo = false;
                setTargetScoutCount(target, Math.max(0, enemyRemain));
            }
        }

        // 反侦察符: 目标为真实玩家且护符生效时, 不暴露任何情报
        if (showCityInfo && target instanceof PlayerCity pc && hasRealOwner(pc)) {
            CityState tcs = cityStateRepository.findByPlayerId(pc.getOwnerId()).orElse(null);
            if (tcs != null && tcs.getCloakUntil() != null && tcs.getCloakUntil() > now) {
                showCityInfo = false;
                scoutResult = "cloaked";
            }
        }

        // 生成侦查报告
        Map<String, Object> reportData = new LinkedHashMap<>();
        reportData.put("time", now);
        reportData.put("targetName", getTargetName(target));
        reportData.put("targetKind", m.getTargetKind());
        reportData.put("x", getTargetX(target));
        reportData.put("y", getTargetY(target));
        reportData.put("level", getTargetLevel(target));
        reportData.put("result", scoutResult);
        reportData.put("showCityInfo", showCityInfo);
        reportData.put("myScouts", myScouts);
        reportData.put("myLost", myLost);
        reportData.put("enemyScouts", enemyScouts);
        reportData.put("enemyLost", enemyLost);

        if (showCityInfo) {
            reportData.put("army", new LinkedHashMap<>(enemyArmy));
            reportData.put("forts", getTargetForts(target));
            reportData.put("resources", getTargetResources(target));
            if (target instanceof WildTile wt) {
                wt.setScouted(true);
                wildTileRepository.save(wt);
            }
        }

        ScoutReport report = new ScoutReport();
        report.setPlayerId(playerId);
        report.setTargetX(getTargetX(target));
        report.setTargetY(getTargetY(target));
        report.setTargetName(getTargetName(target));
        report.setData(JsonUtil.toJson(reportData));
        report.setCreatedAt(now);
        ScoutReport saved = scoutReportRepository.save(report);
        try { questService.onEvent(playerId, "SCOUT_COMPLETE", null, 1); } catch (Exception ignored) {}
        // 同步通过 WebSocket 把完整报告推给前端, 让战报列表即时显示
        Map<String, Object> wsReport = new LinkedHashMap<>();
        wsReport.put("id", saved.getId());
        wsReport.put("type", "scout");
        wsReport.put("time", now);
        wsReport.put("readAt", 0L);
        wsReport.put("data", reportData);
        pushService.pushScoutReport(playerId, wsReport);
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

    // ========================================================================
    // resolveIncomingBattle - 来袭军城防战斗
    // ========================================================================

    private void resolveIncomingBattle(Long playerId, IncomingMarch im, long now) {
        Map<String, Integer> attackerArmy = JsonUtil.parseIntMap(im.getArmy());

        // 玩家防守信息
        Map<String, Integer> defenderArmy = getArmyMap(playerId);
        Map<String, Integer> defenderForts = getFortsMap(playerId);
        Map<String, Integer> defenderTech = getTechMap(playerId);
        Officer commander = getCommander(playerId);
        int defenderCommanderMil = commander != null ? equipmentService.attributes(commander).military() : 0;
        Map<String, Integer> defenderSkills = getCommanderSkills(commander);
        int defenderWallLevel = buildingLevel(playerId, "wall");

        Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
        Map<String, Integer> defenderResources = new LinkedHashMap<>();
        if (res != null) {
            defenderResources.put("food", res.getFood() != null ? res.getFood() : 0);
            defenderResources.put("steel", res.getSteel() != null ? res.getSteel() : 0);
            defenderResources.put("oil", res.getOil() != null ? res.getOil() : 0);
            defenderResources.put("rare", res.getRare() != null ? res.getRare() : 0);
            defenderResources.put("gold", res.getGold() != null ? res.getGold() : 0);
        }
        long defenderWarehouseLevel = buildingLevel(playerId, "depot");

        String action = im.getAction() != null ? im.getAction() : "conquer";

        BattleResult result = battleService.startWorldDispatch(
                attackerArmy, defenderArmy, defenderForts,
                Collections.emptyMap(), defenderTech,
                Collections.emptyMap(), defenderSkills,
                0, defenderCommanderMil,
                0, defenderWallLevel,
                action, defenderResources, defenderWarehouseLevel);
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

        // 生成战报
        Map<String, Object> reportData = new LinkedHashMap<>();
        reportData.put("time", now);
        reportData.put("type", "incoming_battle");
        reportData.put("attackerName", im.getFromName() != null ? im.getFromName() : "敌军");
        reportData.put("win", result.isWin());
        reportData.put("report", result.getReport());
        Map<String, Object> commanders = new LinkedHashMap<>();
        commanders.put("attacker", null);
        commanders.put("defender", buildCommanderReport(commander));
        reportData.put("commanders", commanders);

        ScoutReport report = new ScoutReport();
        report.setPlayerId(playerId);
        report.setTargetX(im.getFromX());
        report.setTargetY(im.getFromY());
        report.setTargetName(im.getFromName());
        report.setData(JsonUtil.toJson(reportData));
        report.setCreatedAt(now);
        scoutReportRepository.save(report);
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
        long now = System.currentTimeMillis();
        String targetName = m != null && m.getTargetName() != null && !m.getTargetName().isBlank()
                ? m.getTargetName() : "目标";
        String targetKind = m != null && m.getTargetKind() != null ? m.getTargetKind() : "target";
        String action = m != null && m.getAction() != null ? m.getAction() : "conquer";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "battle");
        report.put("win", result.isWin());
        report.put("readAt", 0L);
        report.put("time", now);
        report.put("subject", buildBattleSubject(result.isWin(), result.isCityConquered(), action, targetKind, targetName));
        report.put("targetType", targetKind);
        report.put("fromName", playerCityName(playerId));
        report.put("fromCoord", (m != null && m.getFromX() != null ? m.getFromX() : 0) + "," + (m != null && m.getFromY() != null ? m.getFromY() : 0));
        report.put("toName", targetName);
        report.put("toCoord", (m != null && m.getTargetX() != null ? m.getTargetX() : 0) + "," + (m != null && m.getTargetY() != null ? m.getTargetY() : 0));
        report.put("survivorAttacker", result.getSurvivorAttacker());
        report.put("survivorDefender", result.getSurvivorDefender());
        report.put("plunder", result.getPlunderedResources());
        report.put("exp", result.getExpGained());
        report.put("report", result.getReport());
        report.put("cityConquered", result.isCityConquered());
        report.put("roundLogs", splitReportLines(result.getReport()));
        Map<String, Object> commanders = new LinkedHashMap<>();
        commanders.put("attacker", buildCommanderReport(attackerCommander));
        commanders.put("defender", buildCommanderReport(defenderCommander));
        report.put("commanders", commanders);

        // 持久化到战报表(type=battle)，刷新页面/离线后仍可通过 GET /reports 拉取
        Long reportId = null;
        try {
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
            reportId = sr.getId();
        } catch (Exception e) {
            System.err.println("[MarchService] 持久化战斗战报失败: playerId=" + playerId + ", " + e.getMessage());
        }
        report.put("id", reportId != null ? reportId : ("battle-" + now));

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
        if (conquered) return act + "胜利·已征服 " + targetName;
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
            playerRepository.findById(m.getPlayerId())
                    .ifPresent(p -> m.setTargetName(
                            p.getCityName() != null && !p.getCityName().isBlank()
                                    ? p.getCityName() : "新城市"));
        }
    }

    /** 交换行军起点和终点坐标 */
    private void swapCoords(March m) {
        Integer origFromX = m.getFromX();
        Integer origFromY = m.getFromY();
        m.setFromX(m.getTargetX());
        m.setFromY(m.getTargetY());
        m.setTargetX(origFromX);
        m.setTargetY(origFromY);
    }

    // ===== 目标查找 =====

    private WildTile findWildTileById(String targetId) {
        if (targetId == null) return null;
        try {
            Long id = Long.parseLong(targetId);
            return wildTileRepository.findById(id).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object findTargetById(String kind, String targetId) {
        if (targetId == null) return null;
        try {
            Long id = Long.parseLong(targetId);
            return findTargetByLongId(kind, id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object findTargetByLongId(String kind, Long id) {
        if (kind == null || id == null) return null;
        switch (kind) {
            case "wild":
            case "wild_gather":
                return wildTileRepository.findById(id).orElse(null);
            case "bandit":
                return banditRepository.findById(id).orElse(null);
            case "npc":
                return npcCityRepository.findById(id).orElse(null);
            case "simulated_npc":
                return playerCityRepository.findById(id).filter(pc -> !hasRealOwner(pc)).orElse(null);
            case "player":
                return playerCityRepository.findById(id).filter(this::hasRealOwner).orElse(null);
            default:
                return null;
        }
    }

    private boolean hasRealOwner(PlayerCity city) {
        return city.getOwnerId() != null && playerRepository.existsById(city.getOwnerId());
    }

    private boolean isDefeated(Object target) {
        if (target instanceof Bandit b) return Boolean.TRUE.equals(b.getDefeated());
        if (target instanceof NpcCity n) return Boolean.TRUE.equals(n.getDefeated());
        return false;
    }

    // ===== 目标属性访问器 =====

    private Map<String, Integer> getTargetArmy(Object target) {
        if (target instanceof WildTile wt) return JsonUtil.parseIntMap(wt.getGarrison());
        if (target instanceof Bandit b) return JsonUtil.parseIntMap(b.getArmy());
        if (target instanceof NpcCity n) return JsonUtil.parseIntMap(n.getArmy());
        if (target instanceof PlayerCity p) return JsonUtil.parseIntMap(p.getArmy());
        return Collections.emptyMap();
    }

    private Map<String, Integer> getTargetForts(Object target) {
        if (target instanceof NpcCity n) return JsonUtil.parseIntMap(n.getForts());
        if (target instanceof PlayerCity p) return JsonUtil.parseIntMap(p.getForts());
        return Collections.emptyMap();
    }

    private Map<String, Integer> getTargetResources(Object target) {
        if (target instanceof WildTile wt) {
            WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(wt.getType());
            if (wtDef == null || wtDef.res() == null) return Collections.emptyMap();
            int remain = Math.max(0, (wt.getTotalRes() != null ? wt.getTotalRes() : 0)
                    - (wt.getMined() != null ? wt.getMined() : 0));
            return Map.of(wtDef.res(), remain);
        }
        if (target instanceof NpcCity n) return JsonUtil.parseIntMap(n.getResources());
        if (target instanceof PlayerCity p) return JsonUtil.parseIntMap(p.getResources());
        return Collections.emptyMap();
    }

    private String getTargetName(Object target) {
        if (target instanceof WildTile wt) {
            WildTypeDef wtDef = WildTypeDef.WILD_TYPES.get(wt.getType());
            return (wtDef != null ? wtDef.name() : "野地") + " Lv." + (wt.getLevel() != null ? wt.getLevel() : 1);
        }
        if (target instanceof Bandit b) return b.getName();
        if (target instanceof NpcCity n) return n.getName();
        if (target instanceof PlayerCity p) return p.getName();
        return "";
    }

    private Integer getTargetX(Object target) {
        if (target instanceof WildTile wt) return wt.getX();
        if (target instanceof Bandit b) return b.getX();
        if (target instanceof NpcCity n) return n.getX();
        if (target instanceof PlayerCity p) return p.getX();
        return 0;
    }

    private Integer getTargetY(Object target) {
        if (target instanceof WildTile wt) return wt.getY();
        if (target instanceof Bandit b) return b.getY();
        if (target instanceof NpcCity n) return n.getY();
        if (target instanceof PlayerCity p) return p.getY();
        return 0;
    }

    private Integer getTargetLevel(Object target) {
        if (target instanceof WildTile wt) return wt.getLevel();
        if (target instanceof Bandit b) return b.getLevel();
        if (target instanceof NpcCity n) return n.getLevel();
        if (target instanceof PlayerCity p) return p.getLevel();
        return 1;
    }

    private void setTargetScoutCount(Object target, int scoutCount) {
        if (target instanceof WildTile wt) {
            Map<String, Integer> army = JsonUtil.parseIntMap(wt.getGarrison());
            army.put("scout", scoutCount);
            wt.setGarrison(JsonUtil.toJson(army));
            wildTileRepository.save(wt);
        } else if (target instanceof PlayerCity pc) {
            Map<String, Integer> army = JsonUtil.parseIntMap(pc.getArmy());
            army.put("scout", scoutCount);
            pc.setArmy(JsonUtil.toJson(army));
            playerCityRepository.save(pc);
        } else if (target instanceof NpcCity nc) {
            Map<String, Integer> army = JsonUtil.parseIntMap(nc.getArmy());
            army.put("scout", scoutCount);
            nc.setArmy(JsonUtil.toJson(army));
            npcCityRepository.save(nc);
        }
    }

    // ===== 玩家信息获取 =====

    private Map<String, Integer> getArmyMap(Long playerId) {
        List<ArmyUnit> units = armyUnitRepository.findByPlayerId(playerId);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ArmyUnit u : units) {
            int count = u.getCount() != null ? u.getCount() : 0;
            if (count > 0) map.put(u.getType(), count);
        }
        return map;
    }

    private Map<String, Integer> getFortsMap(Long playerId) {
        List<Fortification> forts = fortificationRepository.findByPlayerId(playerId);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Fortification f : forts) {
            int count = f.getCount() != null ? f.getCount() : 0;
            if (count > 0) map.put(f.getType(), count);
        }
        return map;
    }

    private Map<String, Integer> getTechMap(Long playerId) {
        List<Technology> techs = technologyRepository.findByPlayerId(playerId);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Technology t : techs) {
            map.put(t.getType(), t.getLevel() != null ? t.getLevel() : 0);
        }
        return map;
    }

    private Officer getCommander(Long playerId) {
        List<Officer> officers = officerRepository.findByPlayerIdAndRole(playerId, "commander");
        return (officers != null && !officers.isEmpty()) ? officers.get(0) : null;
    }

    /** 根据 officerId 取具体军官（行军里的 commanderId 字段） */
    private Officer getOfficerById(Long playerId, Long officerId) {
        if (officerId == null) return null;
        return officerRepository.findById(officerId).filter(o -> playerId.equals(o.getPlayerId())).orElse(null);
    }

    /** 解析军官技能 JSON -> Map<skillId, level> - 对应 JS Core.getCommanderSkills */
    private Map<String, Integer> getCommanderSkills(Officer commander) {
        if (commander == null || commander.getSkills() == null || commander.getSkills().isBlank()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> skillList = JsonUtil.parseList(commander.getSkills());
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map<String, Object> skill : skillList) {
            Object id = skill.get("id");
            Object lv = skill.get("lv");
            if (id != null && lv != null) {
                map.put(id.toString(), ((Number) lv).intValue());
            }
        }
        return map;
    }

    private int buildingLevel(Long playerId, String type) {
        List<Building> buildings = buildingRepository.findByPlayerIdAndType(playerId, type);
        int sum = 0;
        for (Building b : buildings) {
            sum += b.getLevel() != null ? b.getLevel() : 0;
        }
        return sum;
    }

    // ===== 资源/部队操作 =====

    private void returnArmy(Long playerId, Map<String, Integer> army) {
        if (army == null || army.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) continue;
            List<ArmyUnit> existing = armyUnitRepository.findByPlayerIdAndType(playerId, type);
            if (existing.isEmpty()) {
                ArmyUnit unit = new ArmyUnit();
                unit.setPlayerId(playerId);
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
        Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (res == null) {
            res = new Resources();
            res.setPlayerId(playerId);
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
        List<ArmyUnit> armyUnits = armyUnitRepository.findByPlayerId(playerId);
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
                newAu.setType(unitType);
                newAu.setCount(survivorCount);
                armyUnitRepository.save(newAu);
            }
        }

        // 更新城防
        List<Fortification> forts = fortificationRepository.findByPlayerId(playerId);
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
                newF.setType(fortType);
                newF.setCount(survivorCount);
                fortificationRepository.save(newF);
            }
        }
    }
}
