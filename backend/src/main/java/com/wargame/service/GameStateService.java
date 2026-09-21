package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.TechDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GameStateService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired
    private WorldTerrainService terrain;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.quest.OnboardingService onboarding;

    @org.springframework.beans.factory.annotation.Autowired private CityService cityService;
    @org.springframework.beans.factory.annotation.Autowired private ArmyProductionQueueRepository armyQueues;
    @org.springframework.beans.factory.annotation.Autowired private TechService techService;
    @org.springframework.beans.factory.annotation.Autowired private TechResearchQueueRepository techResearchQueueRepository;

    private final WorldViewService worldViewService;
    private final PlayerRepository playerRepository;
    private final ResourcesRepository resourcesRepository;
    private final BuildingRepository buildingRepository;
    private final ArmyUnitRepository armyUnitRepository;
    private final WoundedUnitRepository woundedUnitRepository;
    private final FortificationRepository fortificationRepository;
    private final TechnologyRepository technologyRepository;
    private final OfficerRepository officerRepository;
    private final ConstructionRepository constructionRepository;
    private final AcademyRepository academyRepository;
    private final WorldMapRepository worldMapRepository;
    private final CityStateRepository cityStateRepository;
    private final NpcCityRepository npcCityRepository;
    private final PlayerCityRepository playerCityRepository;
    private final BanditRepository banditRepository;
    private final WildTileRepository wildTileRepository;
    private final MarchRepository marchRepository;
    private final IncomingMarchRepository incomingMarchRepository;
    private final ScoutReportRepository scoutReportRepository;
    private final PlayerItemRepository playerItemRepository;
    private final OfficerEquipmentRepository equipmentRepository;
    private final EquipmentService equipmentService;
    private final GuildMemberRepository guildMemberRepository;

    private final GuildRepository guildRepository;
    private final MailService mailService;

    private static final Set<String> MULTI_SLOT = Set.of(
            "house", "farm", "refinery", "oilfield", "raremine", "factory", "depot"
    );

    public GameStateService(WorldViewService worldViewService, PlayerRepository playerRepository,
                            ResourcesRepository resourcesRepository,
                            BuildingRepository buildingRepository,
                            ArmyUnitRepository armyUnitRepository, WoundedUnitRepository woundedUnitRepository,
                            FortificationRepository fortificationRepository,
                            TechnologyRepository technologyRepository,
                            OfficerRepository officerRepository,
                            ConstructionRepository constructionRepository,
                            AcademyRepository academyRepository,
                            WorldMapRepository worldMapRepository,
                            CityStateRepository cityStateRepository,
                            NpcCityRepository npcCityRepository,
                            PlayerCityRepository playerCityRepository,
                            BanditRepository banditRepository,
                            WildTileRepository wildTileRepository,
                            MarchRepository marchRepository,
                            IncomingMarchRepository incomingMarchRepository,
                            ScoutReportRepository scoutReportRepository,
                            PlayerItemRepository playerItemRepository,
                            OfficerEquipmentRepository equipmentRepository,
                            EquipmentService equipmentService,
                            GuildMemberRepository guildMemberRepository,
                            GuildRepository guildRepository,
                            MailService mailService) {
        this.worldViewService = worldViewService;
        this.playerRepository = playerRepository;
        this.resourcesRepository = resourcesRepository;
        this.buildingRepository = buildingRepository;
        this.armyUnitRepository = armyUnitRepository;
        this.woundedUnitRepository = woundedUnitRepository;
        this.fortificationRepository = fortificationRepository;
        this.technologyRepository = technologyRepository;
        this.officerRepository = officerRepository;
        this.constructionRepository = constructionRepository;
        this.academyRepository = academyRepository;
        this.worldMapRepository = worldMapRepository;
        this.cityStateRepository = cityStateRepository;
        this.npcCityRepository = npcCityRepository;
        this.playerCityRepository = playerCityRepository;
        this.banditRepository = banditRepository;
        this.wildTileRepository = wildTileRepository;
        this.marchRepository = marchRepository;
        this.incomingMarchRepository = incomingMarchRepository;
        this.scoutReportRepository = scoutReportRepository;
        this.playerItemRepository = playerItemRepository;
        this.equipmentRepository = equipmentRepository;
        this.equipmentService = equipmentService;
        this.guildMemberRepository = guildMemberRepository;
        this.guildRepository = guildRepository;
        this.mailService = mailService;
    }

    @Transactional
    public void setCityName(Long playerId, String cityName) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        cityScope.economy(playerId).setCityName(cityName);
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);
        cityScope.selected(playerId).ifPresent(city -> {
            city.setName(cityName);
            playerCityRepository.save(city);
        });
    }

    @Transactional
    public void setAvatar(Long playerId, String avatar) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        player.setAvatar(avatar == null ? "" : avatar.trim());
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);
    }

    // ================================================================
    // getGameState
    // ================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> getGameState(Long playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Invalid player ID: null");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        Map<String, Object> state = new LinkedHashMap<>();

        // --- player ---
        Map<String, Object> playerInfo = new LinkedHashMap<>();
        playerInfo.put("id", player.getId());
        playerInfo.put("username", player.getUsername());
        playerInfo.put("faction", player.getFaction());
        playerInfo.put("cityName", cityScope.economy(playerId).getCityName() == null || cityScope.economy(playerId).getCityName().isBlank()
                ? "新城市" : cityScope.economy(playerId).getCityName());
        playerInfo.put("avatar", player.getAvatar() != null ? player.getAvatar() : "");
        int rankTier = player.getMilitaryRank() != null ? player.getMilitaryRank() : 1;
        playerInfo.put("militaryRank", rankTier);
        playerInfo.put("militaryRankName", MilitaryRankDef.getRankName(rankTier));
        playerInfo.put("vipLevel", player.getVipLevel() != null ? player.getVipLevel() : 0);
        playerInfo.put("tutorialDismissed", Boolean.TRUE.equals(player.getTutorialDismissed()));
        guildMemberRepository.findByPlayerId(playerId).ifPresent(member -> guildRepository.findById(member.getGuildId()).ifPresent(guild -> {
            Map<String, Object> guildInfo = new LinkedHashMap<>();
            guildInfo.put("id", guild.getId());
            guildInfo.put("name", guild.getName());
            guildInfo.put("icon", guild.getIcon());
            guildInfo.put("role", member.getRole());
            playerInfo.put("guild", guildInfo);
        }));
        cityScope.selected(playerId).ifPresent(city -> {
            playerInfo.put("activeCityId", city.getId());
            playerInfo.put("mainCity", city.getCitySlot() == 0);
        });
        playerInfo.put("citySlot", cityScope.slot(playerId));
        state.put("cityOverview", cityService.overview(playerId));
        state.put("player", playerInfo);
        state.put("unreadReportCount", scoutReportRepository.countUnreadByPlayerId(playerId));

        // --- resources ---
        Resources resources = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        Map<String, Object> resMap = new LinkedHashMap<>();
        if (resources != null) {
            resMap.put("food", resources.getFood() != null ? resources.getFood() : 0);
            resMap.put("steel", resources.getSteel() != null ? resources.getSteel() : 0);
            resMap.put("oil", resources.getOil() != null ? resources.getOil() : 0);
            resMap.put("rare", resources.getRare() != null ? resources.getRare() : 0);
            resMap.put("gold", resources.getGold() != null ? resources.getGold() : 0);
            resMap.put("diamond", java.util.Objects.requireNonNullElse(cityScope.wallet(playerId).getDiamond(), 0));
        } else {
            resMap.put("food", 0);
            resMap.put("steel", 0);
            resMap.put("oil", 0);
            resMap.put("rare", 0);
            resMap.put("gold", 0);
            resMap.put("diamond", 0);
        }
        state.put("resources", resMap);

        // --- buildings ---
        List<Building> buildings = buildingRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        state.put("buildings", buildBuildingsMap(buildings));

        int populationCapacity = buildings.stream()
                .filter(b -> "house".equals(b.getType()))
                .mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0)
                .sum() * com.wargame.model.constants.BuildingDef.BUILDINGS.get("house").popPer();
        int civilians = Math.max(0, cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0);
        int morale = cityScope.economy(playerId).getMorale() != null ? cityScope.economy(playerId).getMorale() : 70;
        int effectiveCapacity = populationCapacity <= 0 ? 0 : Math.max(10, (int) Math.round(populationCapacity * Math.min(1.0, morale / 70.0)));
        Map<String, Object> population = new LinkedHashMap<>();
        population.put("civilian", civilians);
        population.put("capacity", populationCapacity);
        population.put("effectiveCapacity", effectiveCapacity);
        population.put("recruitable", civilians);
        population.put("growthPerHour", populationCapacity * 0.03 * Math.max(0.2, morale / 70.0));
        state.put("population", population);

        // --- army ---
        List<ArmyUnit> armyUnits = armyUnitRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, Object> armyMap = new LinkedHashMap<>();
        for (ArmyUnit unit : armyUnits) {
            armyMap.put(unit.getType(), unit.getCount());
        }
        state.put("army", armyMap);
        state.put("woundedCount", woundedUnitRepository
                .findByPlayerIdAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(playerId, System.currentTimeMillis())
                .stream().mapToLong(WoundedUnit::getCount).sum());

        // --- forts ---
        List<Fortification> forts = fortificationRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        Map<String, Object> fortMap = new LinkedHashMap<>();
        for (Fortification fort : forts) {
            fortMap.put(fort.getType(), fort.getCount());
        }
        state.put("forts", fortMap);

        // --- items (server-authoritative inventory) ---
        List<PlayerItem> items = playerItemRepository.findByPlayerId(playerId);
        Map<String, Object> itemMap = new LinkedHashMap<>();
        for (PlayerItem it : items) {
            itemMap.put(it.getItemKey(), it.getCount());
        }
        state.put("items", itemMap);

        // --- tech ---
        if (techService != null) {
            techService.settleCompletedResearch(playerId, System.currentTimeMillis());
        }
        List<Technology> techs = technologyRepository.findByPlayerId(playerId);
        Map<String, Object> techMap = new LinkedHashMap<>();
        for (Technology tech : techs) {
            if (!TechDef.TECHS.containsKey(tech.getType())) continue;
            techMap.put(tech.getType(), tech.getLevel());
        }
        state.put("tech", techMap);
        state.put("research", techService != null ? techService.getActiveResearch(playerId) : null);

        // --- officers ---
        List<Officer> officers = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        List<Map<String, Object>> officerList = new ArrayList<>();
        for (Officer o : officers) {
            Map<String, Object> offMap = new LinkedHashMap<>();
            // 军官操作接口接收 Long，状态中的 ID 必须保持数据库数字 ID，
            // 不能包装成 "o_1" 这类前端显示标识，否则任命/解雇等接口无法反序列化。
            offMap.put("id", o.getId());
            offMap.put("name", o.getName());
            offMap.put("star", o.getStar() != null ? o.getStar() : 1);
            offMap.put("level", o.getLevel() != null ? o.getLevel() : 1);
            offMap.put("bio", o.getBio() != null ? o.getBio() : "");
            offMap.put("exp", o.getExp() != null ? o.getExp() : 0L);
            offMap.put("attrPoints", o.getAttrPoints() != null ? o.getAttrPoints() : 0);
            EquipmentService.Attributes attrs = equipmentService.attributes(o);
            offMap.put("baseLogistics", o.getLogistics() != null ? o.getLogistics() : 0);
            offMap.put("baseMilitary", o.getMilitary() != null ? o.getMilitary() : 0);
            offMap.put("baseDefense", o.getDefense() != null ? o.getDefense() : 0);
            offMap.put("baseKnowledge", o.getKnowledge() != null ? o.getKnowledge() : 0);
            offMap.put("logistics", attrs.logistics());
            offMap.put("military", attrs.military());
            offMap.put("defense", attrs.defense());
            offMap.put("knowledge", attrs.knowledge());
            offMap.put("setBonuses", attrs.bonuses());
            offMap.put("skills", JsonUtil.parseList(o.getSkills()));
            offMap.put("loyalty", o.getLoyalty() != null ? o.getLoyalty() : 0);
            offMap.put("salary", o.getSalary() != null ? o.getSalary() : 0);
            offMap.put("role", o.getRole() != null ? o.getRole() : "idle");
            offMap.put("recruitAt", o.getRecruitAt() != null ? o.getRecruitAt() : 0);
            offMap.put("rewardAt", o.getRewardAt() != null ? o.getRewardAt() : 0);
            offMap.put("equipment", equipmentRepository.findByPlayerIdAndOfficerId(playerId, o.getId()).stream().map(e -> {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", e.getId()); em.put("itemId", e.getItemKey()); em.put("setType", e.getSetType()); em.put("tier", e.getTier()); em.put("slot", e.getSlot());
                em.put("military", e.getMilitaryBonus()); em.put("logistics", e.getLogisticsBonus()); em.put("knowledge", e.getKnowledgeBonus()); return em;
            }).toList());
            officerList.add(offMap);
        }
        state.put("officers", officerList);

        // --- constructions ---
        List<Construction> constructions = constructionRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        List<Map<String, Object>> constructionList = new ArrayList<>();
        Map<String, List<Building>> buildingsByType = new LinkedHashMap<>();
        for (Building b : buildings) {
            buildingsByType.computeIfAbsent(b.getType(), k -> new ArrayList<>()).add(b);
        }
        for (List<Building> blist : buildingsByType.values()) {
            blist.sort(Comparator.comparing(b -> b.getSlot() == null ? 0 : b.getSlot()));
        }
        for (Construction c : constructions) {
            Map<String, Object> cMap = new LinkedHashMap<>();
            cMap.put("queueId", c.getId());
            cMap.put("id", c.getBuildingType());
            cMap.put("slot", c.getSlot());
            int targetLv = c.getTargetLevel() != null ? c.getTargetLevel() : 0;
            cMap.put("targetLevel", targetLv);
            cMap.put("startedAt", c.getStartAt() != null ? c.getStartAt() : 0);
            cMap.put("finishesAt", c.getFinishAt() != null ? c.getFinishAt() : 0);

            int curLv = 0;
            List<Building> typeBuildings = buildingsByType.get(c.getBuildingType());
            if (typeBuildings != null) {
                if (c.getSlot() != null && MULTI_SLOT.contains(c.getBuildingType())) {
                    if (c.getSlot() < typeBuildings.size()) {
                        Building b = typeBuildings.get(c.getSlot());
                        curLv = b.getLevel() != null ? b.getLevel() : 0;
                    }
                } else if (!typeBuildings.isEmpty()) {
                    Building b = typeBuildings.get(0);
                    curLv = b.getLevel() != null ? b.getLevel() : 0;
                }
            }

            if (targetLv < curLv) {
                cMap.put("action", "dismantle");
                cMap.put("fromLevel", curLv);
            } else {
                cMap.put("action", "upgrade");
                cMap.put("fromLevel", Math.max(0, targetLv - 1));
            }

            constructionList.add(cMap);
        }
        state.put("constructions", constructionList);
        state.put("freeBuildSpeedUpSeconds", BuildService.FREE_SPEED_UP_SECONDS);

        // --- academy ---
        Academy academy = academyRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        Map<String, Object> academyMap = new LinkedHashMap<>();
        if (academy != null) {
            academyMap.put("list", JsonUtil.parseList(academy.getOfficers()));
            academyMap.put("refreshAt", academy.getRefreshAt() != null ? academy.getRefreshAt() : 0);
        } else {
            academyMap.put("list", new ArrayList<>());
            academyMap.put("refreshAt", 0);
        }
        int academyLevel = buildings.stream().filter(b -> "academy".equals(b.getType()))
                .mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0).sum();
        academyMap.put("fiveStarBatchChance", OfficerService.academyFiveStarBatchChance(academyLevel));
        state.put("academy", academyMap);

        // --- world ---
        state.put("world", worldViewService.getWorld(player,
                player.getPosX() == null ? 0 : player.getPosX(),
                player.getPosY() == null ? 0 : player.getPosY(), WorldConfig.VIEW_RADIUS));

        // --- cityState ---
        CityState cityState = cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        Map<String, Object> csMap = new LinkedHashMap<>();
        if (cityState != null) {
            csMap.put("status", cityState.getStatus() != null ? cityState.getStatus() : "peace");
            csMap.put("warTarget", cityState.getWarTargetId());
            csMap.put("warUntil", cityState.getWarEndAt() != null ? cityState.getWarEndAt() : 0);
            csMap.put("shieldUntil", cityState.getShieldUntil() != null ? cityState.getShieldUntil() : 0);
            csMap.put("peaceUntil", cityState.getPeaceUntil() != null ? cityState.getPeaceUntil() : 0);
        } else {
            csMap.put("status", "peace");
            csMap.put("warTarget", null);
            csMap.put("warUntil", 0);
            csMap.put("shieldUntil", 0);
            csMap.put("peaceUntil", 0);
        }
        state.put("cityState", csMap);

        // --- scalar fields ---
        state.put("tax", cityScope.economy(playerId).getTax() != null ? cityScope.economy(playerId).getTax() : 30);
        state.put("morale", cityScope.economy(playerId).getMorale() != null ? cityScope.economy(playerId).getMorale() : 70);
        state.put("resentment", cityScope.economy(playerId).getResentment() != null ? cityScope.economy(playerId).getResentment() : 0);
        state.put("lastAppeaseAt", cityScope.economy(playerId).getLastAppeaseAt() != null ? cityScope.economy(playerId).getLastAppeaseAt() : 0L);
        state.put("prestige", player.getPrestige() != null ? player.getPrestige() : 0);
        state.put("lastTick", cityScope.economy(playerId).getLastTick() != null ? cityScope.economy(playerId).getLastTick() : 0);
        state.put("stats", new LinkedHashMap<>());
        state.put("progress", new LinkedHashMap<>());

        return state;
    }

    // ================================================================
    // setTax - 设置税率
    // ================================================================

    @Transactional
    public Map<String, Object> setTax(Long playerId, int tax) {
        if (playerId == null) {
            throw new IllegalArgumentException("Invalid player ID: null");
        }
        if (tax < 0 || tax > 100) {
            throw new IllegalArgumentException("税率必须在 0-100 之间");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        int oldTax = cityScope.economy(playerId).getTax() != null ? cityScope.economy(playerId).getTax() : 30;
        int taxDelta = tax - oldTax;
        int curMorale = cityScope.economy(playerId).getMorale() != null ? cityScope.economy(playerId).getMorale() : 70;
        int newMorale = Math.max(0, Math.min(100, curMorale - taxDelta));
        cityScope.economy(playerId).setTax(tax);
        cityScope.economy(playerId).setMorale(newMorale);
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "税率已设置为 " + tax + "%");
        result.put("tax", tax);
        result.put("morale", newMorale);
        result.put("state", getGameState(playerId));
        return result;
    }

    // ================================================================
    // appease - 安抚民心
    // ================================================================

    @Transactional
    public Map<String, Object> appease(Long playerId, String type) {
        if (playerId == null) {
            throw new IllegalArgumentException("Invalid player ID: null");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
        Resources resources = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId))
                .orElseThrow(() -> new IllegalArgumentException("Resources not found for player: " + playerId));

        int moraleGain;
        int resentmentLoss;
        String message;

        if ("gold".equalsIgnoreCase(type)) {
            int civilians = Math.max(0, cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0);
            int goldCost = Math.max(1000, Math.min(10000, civilians * 2));
            int currentGold = resources.getGold() != null ? resources.getGold() : 0;
            if (currentGold < goldCost) {
                throw new IllegalArgumentException("黄金不足，开仓赈灾需要 " + goldCost + " 黄金（当前: " + currentGold + "）");
            }
            resources.setGold(currentGold - goldCost);
            moraleGain = 10;
            resentmentLoss = 5;
            message = "消耗 " + goldCost + " 黄金开仓赈民，民心 +" + moraleGain + "，民怨 -" + resentmentLoss;
        } else if ("diamond".equalsIgnoreCase(type)) {
            int diamondCost = 20;
            Resources wallet = cityScope.wallet(playerId);
            int currentDiamond = java.util.Objects.requireNonNullElse(wallet.getDiamond(), 0);
            if (currentDiamond < diamondCost) {
                throw new IllegalArgumentException("钻石不足，特赦犒赏需要 " + diamondCost + " 钻石（当前: " + currentDiamond + "）");
            }
            wallet.setDiamond(currentDiamond - diamondCost);
            resourcesRepository.save(wallet);
            moraleGain = 25;
            resentmentLoss = 20;
            message = "消耗 " + diamondCost + " 钻石大赦天下与重金犒赏，民心 +" + moraleGain + "，民怨 -" + resentmentLoss;
        } else {
            throw new IllegalArgumentException("未知的安抚类型: " + type);
        }

        int curMorale = cityScope.economy(playerId).getMorale() != null ? cityScope.economy(playerId).getMorale() : 70;
        int curResentment = cityScope.economy(playerId).getResentment() != null ? cityScope.economy(playerId).getResentment() : 0;

        cityScope.economy(playerId).setMorale(Math.min(100, Math.max(0, curMorale + moraleGain)));
        cityScope.economy(playerId).setResentment(Math.max(0, curResentment - resentmentLoss));
        cityScope.economy(playerId).setLastAppeaseAt(System.currentTimeMillis());

        resourcesRepository.save(resources);
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        result.put("morale", cityScope.economy(playerId).getMorale());
        result.put("resentment", cityScope.economy(playerId).getResentment());
        result.put("state", getGameState(playerId));
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void repairExistingPlayerCoordinates() {
        Set<String> used = collectStaticCoordinates();
        playerCityRepository.findAll().stream().filter(c -> c.getCitySlot() != null && c.getCitySlot() > 0)
                .forEach(c -> used.add(c.getX() + "," + c.getY()));
        List<Player> players = new ArrayList<>(playerRepository.findAll());
        players.sort(Comparator.comparing(Player::getId));
        for (Player player : players) {
            // 到期注销账号保留墓碑；启动修复不得重新分配地块或重建其主城。
            if (!player.isGameInitialized() || player.deletionDue(System.currentTimeMillis())) continue;
            int x = player.getCityPosX() == null ? -1 : player.getCityPosX();
            int y = player.getCityPosY() == null ? -1 : player.getCityPosY();
            String key = x + "," + y;
            if (x < 0 || x >= WorldConfig.SIZE || y < 0 || y >= WorldConfig.SIZE || used.contains(key)) {
                int[] coord = freeCoord(used);
                player.setCityPosX(coord[0]);
                player.setCityPosY(coord[1]);
                if (player.getPosX() == null || player.getPosX().equals(x)) player.setPosX(coord[0]);
                if (player.getPosY() == null || player.getPosY().equals(y)) player.setPosY(coord[1]);
            } else {
                used.add(key);
            }
            playerRepository.save(player);
            ensureRealPlayerCity(player);
        }
    }

    private void ensureRealPlayerCity(Player player) {
        Long worldId = worldMapRepository.findFirstByOrderByIdAsc().map(WorldMap::getId).orElse(null);
        if (worldId == null || player.getCityPosX() == null || player.getCityPosY() == null) return;

        var taggedMain = playerCityRepository.findByOwnerIdAndCitySlot(player.getId(), 0);
        if (taggedMain.isPresent()) {
            PlayerCity main = taggedMain.get();
            main.setX(player.getCityPosX()); main.setY(player.getCityPosY());
            main.setName(player.getCityName() == null || player.getCityName().isBlank() ? "新城市" : player.getCityName());
            playerCityRepository.save(main);
            return;
        }

        boolean exists = playerCityRepository.findByOwnerId(player.getId()).stream()
                .anyMatch(city -> worldId.equals(city.getWorldId())
                        && player.getCityPosX().equals(city.getX())
                        && player.getCityPosY().equals(city.getY()));
        if (exists) {
            playerCityRepository.findByOwnerId(player.getId()).stream()
                    .filter(c -> player.getCityPosX().equals(c.getX()) && player.getCityPosY().equals(c.getY()))
                    .filter(c -> c.getCitySlot() == null).findFirst().ifPresent(c -> { c.setCitySlot(0); playerCityRepository.save(c); });
            return;
        }

        PlayerCity city = new PlayerCity();
        city.setWorldId(worldId);
        city.setName(player.getCityName() == null || player.getCityName().isBlank()
                ? "新城市" : player.getCityName());
        city.setOwnerId(player.getId());
        city.setCitySlot(0);
        city.setLevel(1);
        city.setX(player.getCityPosX());
        city.setY(player.getCityPosY());
        city.setForts("{}");
        city.setResources("{}");
        city.setPrestige(player.getPrestige() == null ? 0 : player.getPrestige());
        city.setWarAt(0L);
        city.setWarEndAt(0L);
        city.setScoutedBy("[]");
        playerCityRepository.save(city);
    }

    private Set<String> collectStaticCoordinates() {
        Set<String> used = new HashSet<>();
        worldMapRepository.findFirstByOrderByIdAsc().ifPresent(world -> {
            Long worldId = world.getId();
            npcCityRepository.findByWorldId(worldId).forEach(city -> used.add(city.getX() + "," + city.getY()));
            banditRepository.findByWorldId(worldId).forEach(bandit -> used.add(bandit.getX() + "," + bandit.getY()));
            wildTileRepository.findByWorldId(worldId).forEach(tile -> used.add(tile.getX() + "," + tile.getY()));
        });
        return used;
    }

    private Set<String> collectNonPlayerCoordinates() {
        Set<String> used = collectStaticCoordinates();
        worldMapRepository.findFirstByOrderByIdAsc().ifPresent(world ->
                playerCityRepository.findByWorldId(world.getId()).forEach(city -> reserveCity(used,city.getX(),city.getY(),city.getOwnerId()==null?1:2)));
        return used;
    }

    private void reserveCity(Set<String> used,int x,int y,int span) {
        x=WorldTerrainService.anchor(x,span);y=WorldTerrainService.anchor(y,span);
        for(int yy=y;yy<y+span;yy++)for(int xx=x;xx<x+span;xx++)used.add(xx+","+yy);
    }

    private Set<String> collectRealPlayerCoordinates() {
        Set<String> used = new HashSet<>();
        playerRepository.findAll().forEach(player -> {
            if (player.getCityPosX() != null && player.getCityPosY() != null) {
                reserveCity(used,player.getCityPosX(),player.getCityPosY(),2);
            }
        });
        return used;
    }

    // ================================================================
    // initializeNewPlayer
    // ================================================================

    @Transactional
    public void initializeNewPlayer(Long playerId) {
        try (var ignored = cityScope.enter(playerId, 0)) {
            initializeMainCity(playerId);
            playerRepository.findById(playerId).orElseThrow().setGameInitialized(true);
        }
    }

    private void initializeMainCity(Long playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Invalid player ID: null");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        player.setActiveCityId(null);
        playerCityRepository.deleteAll(playerCityRepository.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId));
        playerCityRepository.flush();
        WorldMap placementWorld=terrain.lockWorld(); terrain.ensure();
        Set<String> used = terrain.occupiedCoordinates(placementWorld.getId());
        used.addAll(collectRealPlayerCoordinates());
        int[] cityCoord = freeCoord(used, 2);
        player.setPosX(cityCoord[0]);
        player.setPosY(cityCoord[1]);
        player.setCityPosX(cityCoord[0]);
        player.setCityPosY(cityCoord[1]);
        player.setTax(30);
        player.setMorale(70);
        player.setPrestige(0);
        player.setLevel(1);
        player.setVipLevel(0);
        // 为步兵、侦察和运输的小批征召留出人口，同时保留税收人口。
        player.setCivilianPopulation(150);
        player.setPopulationGrowthRemainder(0.0);
        player.setLastTick(System.currentTimeMillis());
        playerRepository.save(player);
        ensureRealPlayerCity(player);

        // 初始储备覆盖基础建设与小批征兵，后续发展仍需生产和出征补给。
        resourcesRepository.deleteByPlayerId(playerId);
        Resources resources = new Resources();
        resources.setPlayerId(playerId);
        resources.setCitySlot(cityScope.slot(playerId));
        resources.setFood(2000);
        resources.setSteel(3000);
        resources.setOil(1000);
        resources.setRare(200);
        resources.setGold(1000);
        resources.setDiamond(0);
        resourcesRepository.save(resources);

        // Clean all player data before re-initializing
        fortificationRepository.findByPlayerId(playerId).forEach(fortificationRepository::delete);
        technologyRepository.findByPlayerId(playerId).forEach(technologyRepository::delete);
        if (techResearchQueueRepository != null) {
            techResearchQueueRepository.deleteByPlayerId(playerId);
        }
        officerRepository.findByPlayerId(playerId).forEach(officerRepository::delete);
        constructionRepository.findByPlayerId(playerId).forEach(constructionRepository::delete);
        marchRepository.findByPlayerId(playerId).forEach(marchRepository::delete);
        incomingMarchRepository.findByTargetPlayerId(playerId).forEach(incomingMarchRepository::delete);
        scoutReportRepository.findByPlayerId(playerId).forEach(scoutReportRepository::delete);

        // Default buildings
        buildingRepository.deleteByPlayerId(playerId);
        buildingRepository.flush();
        saveBuilding(playerId, "command", 1);
        saveBuilding(playerId, "house", 1);
        saveBuilding(playerId, "farm", 1);
        saveBuilding(playerId, "refinery", 1);

        armyQueues.deleteByPlayerId(playerId);

        // Default army
        armyUnitRepository.deleteByPlayerId(playerId);
        armyUnitRepository.flush();
        woundedUnitRepository.deleteByPlayerId(playerId);
        saveArmyUnit(playerId, "infantry", 50);

        // Default forts (all 0 - no rows needed, frontend defaults to 0)

        // Default tech (all 0 - no rows needed, frontend defaults to 0)

        // Default item inventory (server-authoritative)
        seedDefaultItems(playerId);

        // NOTE: genWorld() is intentionally NOT called here.
        // Previously each new player triggered `genWorld(null)` which:
        //   1) created a brand-new isolated WorldMap (orphaning the shared one)
        //   2) wiped every existing bandit / npc_city / player_city / wild_tile
        // World initialization must only run ONCE at server bootstrap
        // (see WorldBootstrap / DataInitializer), not on every signup.

        // Academy with initial refresh
        academyRepository.deleteByPlayerId(playerId);
        Academy academy = new Academy();
        academy.setPlayerId(playerId);
        academy.setCitySlot(cityScope.slot(playerId));
        academy.setRefreshAt(0L);
        academy.setOfficers("[]");
        academyRepository.save(academy);

        // CityState
        cityStateRepository.deleteByPlayerId(playerId);
        CityState cs = new CityState();
        cs.setPlayerId(playerId);
        cs.setCitySlot(cityScope.slot(playerId));
        cs.setStatus("peace");
        cs.setWarTargetId(null);
        cs.setWarAt(0L);
        cs.setWarEndAt(0L);
        cs.setShieldUntil(0L);
        cs.setPeaceUntil(0L);
        cs.setMarchBoostUntil(0L);
        cityStateRepository.save(cs);

        // 邮件种子 (欢迎/礼包/通告)
        mailService.seedForNewPlayer(playerId);
        onboarding.start(playerId);
    }

    /**
     * 新玩家初始物品库存（服务端权威）。
     * 数量比之前 [V5] seed 略高，给玩家开荒期更友好。
     */
    private void seedDefaultItems(Long playerId) {
        long now = System.currentTimeMillis();
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("skillBook", 5);
        defaults.put("expBook", 5);
        defaults.put("loyaltyBox", 2);
        defaults.put("expBookAdv", 2);
        defaults.put("renameCard", 1);
        defaults.put("goldBox", 2);
        defaults.put("resBox", 2);
        defaults.put("speedUp1h", 10);
        defaults.put("shield", 1);

        for (Map.Entry<String, Integer> e : defaults.entrySet()) {
            // upsert
            playerItemRepository.findByPlayerIdAndItemKey(playerId, e.getKey()).ifPresentOrElse(
                existing -> {
                    existing.setCount(e.getValue());
                    existing.setUpdatedAt(now);
                    playerItemRepository.save(existing);
                },
                () -> {
                    PlayerItem pi = new PlayerItem();
                    pi.setPlayerId(playerId);
                    pi.setItemKey(e.getKey());
                    pi.setCount(e.getValue());
                    pi.setUpdatedAt(now);
                    playerItemRepository.save(pi);
                }
            );
        }
    }

    // ================================================================
    // genWorld
    // ================================================================

    @Transactional
    public WorldMap genWorld(Long worldId) {
        WorldMap worldMap;
        if (worldId == null) {
            worldMap = new WorldMap();
            worldMap.setSize(WorldConfig.SIZE);
            worldMap.setScanRadius(WorldConfig.VIEW_RADIUS);
            worldMap.setPosX(WorldConfig.SIZE / 2);
            worldMap.setPosY(WorldConfig.SIZE / 2);
            worldMap = worldMapRepository.save(worldMap);
            worldId = worldMap.getId();
        } else {
            final Long queryWorldId = worldId;
            worldMap = worldMapRepository.findById(worldId)
                    .orElseThrow(() -> new IllegalArgumentException("World not found: " + queryWorldId));
        }

        // Clear existing world entities for this world
        banditRepository.findByWorldId(worldId).forEach(banditRepository::delete);
        npcCityRepository.findByWorldId(worldId).forEach(npcCityRepository::delete);
        playerCityRepository.findByWorldId(worldId).forEach(playerCityRepository::delete);
        wildTileRepository.findByWorldId(worldId).forEach(wildTileRepository::delete);

        Set<String> used = collectRealPlayerCoordinates();
        int center = WorldConfig.SIZE / 2;
        used.add(center + "," + center);

        // Generate 60 bandits
        for (int i = 0; i < 60; i++) {
            int lvIdx = rand(0, WorldConfig.BANDIT_LEVELS.size() - 1);
            WorldConfig.BanditLevel bl = WorldConfig.BANDIT_LEVELS.get(lvIdx);
            int[] coord = freeCoord(used);
            Bandit bandit = new Bandit();
            bandit.setWorldId(worldId);
            bandit.setName(WorldConfig.BANDIT_NAMES.get(i % WorldConfig.BANDIT_NAMES.size()) + " Lv." + bl.lv());
            bandit.setLevel(bl.lv());
            bandit.setX(coord[0]);
            bandit.setY(coord[1]);
            bandit.setArmy(JsonUtil.toJson(bl.army()));
            bandit.setDefeated(false);
            banditRepository.save(bandit);
        }

        // Generate 40 wild tiles
        List<String> wildTypes = new ArrayList<>(WildTypeDef.WILD_TYPES.keySet());
        List<String> wDefUnits = List.of("infantry", "motor", "armored", "ltank");

        for (int w = 0; w < 40; w++) {
            int[] coord = freeCoord(used);
            int wLv = rand(1, WorldConfig.MAX_NPC_LEVEL);
            String wType = wildTypes.get(rand(0, wildTypes.size() - 1));
            Map<String, Integer> wGarrison = new LinkedHashMap<>();
            wGarrison.put(wDefUnits.get(rand(0, wDefUnits.size() - 1)), 5 * wLv);

            WildTile wildTile = new WildTile();
            wildTile.setWorldId(worldId);
            wildTile.setType(wType);
            wildTile.setX(coord[0]);
            wildTile.setY(coord[1]);
            wildTile.setLevel(wLv);
            wildTile.setGarrison(JsonUtil.toJson(wGarrison));
            wildTile.setScouted(false);
            wildTile.setOccupied(false);
            wildTile.setOccupiedBy(null);
            wildTile.setTotalRes(wLv * 800);
            wildTile.setMined(0);
            wildTileRepository.save(wildTile);
        }

        terrain.ensure();
        return worldMap;
    }

    // ================================================================
    // Private helpers - state assembly
    // ================================================================

    private Map<String, Object> buildBuildingsMap(List<Building> buildings) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, List<Building>> byType = new LinkedHashMap<>();
        for (Building b : buildings) {
            byType.computeIfAbsent(b.getType(), k -> new ArrayList<>()).add(b);
        }
        for (Map.Entry<String, List<Building>> entry : byType.entrySet()) {
            String type = entry.getKey();
            List<Building> list = entry.getValue();
            if (MULTI_SLOT.contains(type)) {
                // 必须按 slot 排序 (空 slot 视作 0)，否则数据库物理存储顺序
                // 会让 state.buildings[id] 数组下标与 DB.slot 错位。
                // 错位后：玩家在 UI 上点 #N 升级，JS 发送 slot = N-1，
                // 但后端按 slot = N-1 查到的可能是另一栋楼（等级已满），
                // 返回 success=false，但前端仍会提示"开始升级"。
                list.sort(Comparator.comparing(b -> b.getSlot() == null ? 0 : b.getSlot()));
                List<Integer> levels = new ArrayList<>(list.size());
                for (Building b : list) levels.add(b.getLevel() != null ? b.getLevel() : 0);
                map.put(type, levels);
            } else {
                map.put(type, list.isEmpty() ? 0
                        : (list.get(0).getLevel() != null ? list.get(0).getLevel() : 0));
            }
        }
        return map;
    }

    // ================================================================
    // Private helpers - world generation
    // ================================================================

    private int[] freeCoord(Set<String> used) {
        return freeCoord(used,1);
    }

    private int[] freeCoord(Set<String> used, int span) {
        String mask=terrain.current(); if(mask==null)mask=WorldTerrainService.generate();
        int start=rand(0,WorldConfig.SIZE*WorldConfig.SIZE-1);
        for(int i=0;i<WorldConfig.SIZE*WorldConfig.SIZE;i++){
            int n=(start+i)%(WorldConfig.SIZE*WorldConfig.SIZE),x=n%WorldConfig.SIZE,y=n/WorldConfig.SIZE;
            if(x+span>WorldConfig.SIZE||y+span>WorldConfig.SIZE)continue;
            boolean free=true;
            for(int yy=y;yy<y+span;yy++)for(int xx=x;xx<x+span;xx++)if(used.contains(xx+","+yy)||WorldTerrainService.sea(mask,xx,yy))free=false;
            if(free){for(int yy=y;yy<y+span;yy++)for(int xx=x;xx<x+span;xx++)used.add(xx+","+yy);return new int[]{x,y};}
        }
        throw new IllegalArgumentException("地图没有足够空地");
    }

    private void saveBuilding(Long playerId, String type, int level) {
        Building b = new Building();
        b.setPlayerId(playerId);
        b.setCitySlot(cityScope.slot(playerId));
        b.setType(type);
        b.setLevel(level);
        buildingRepository.save(b);
    }

    private void saveArmyUnit(Long playerId, String type, int count) {
        ArmyUnit u = new ArmyUnit();
        u.setPlayerId(playerId);
        u.setCitySlot(cityScope.slot(playerId));
        u.setType(type);
        u.setCount(count);
        armyUnitRepository.save(u);
    }

    /**
     * Inclusive random integer in [a, b], matching JS G.rand(a, b).
     */
    private int rand(int a, int b) {
        return ThreadLocalRandom.current().nextInt(a, b + 1);
    }
}
