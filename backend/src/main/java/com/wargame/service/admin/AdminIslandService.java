package com.wargame.service.admin;

import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.March;
import com.wargame.model.entity.PlayerCity;
import com.wargame.model.entity.WildTile;
import com.wargame.model.entity.WorldMap;
import com.wargame.repository.BanditRepository;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.WildTileRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 海洋岛屿管理服务：
 * 负责大地图海洋岛屿的智能识别、分类、统计分析、动态生成与安全移除。
 *
 * 岛屿三大类型定义：
 * 1. 小微岛礁 (MICRO): 24 ~ 40 格，如单点/小型珊瑚礁群，适合作为前哨站或单点资源据点；
 * 2. 中型海岛 (MEDIUM): 75 ~ 100 格，面积适中，可容纳多种野地或单座独立城市；
 * 3. 大型/巨型群岛 (LARGE): 140 ~ 608 格，具备宽阔的内部腹地与漫长海岸线，可容纳多座城市与综合野地群。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminIslandService {

    public static final String CAT_MICRO = "MICRO";
    public static final String CAT_MEDIUM = "MEDIUM";
    public static final String CAT_LARGE = "LARGE";

    private final WorldMapRepository worldMapRepository;
    private final PlayerCityRepository playerCityRepository;
    private final BanditRepository banditRepository;
    private final WildTileRepository wildTileRepository;
    private final MarchRepository marchRepository;
    private final BattleSessionRepository battleSessionRepository;
    private final AdminAuditLogService auditLogService;
    private final WorldTerrainService worldTerrainService;

    public record IslandInfo(
            int id,
            String category,
            String categoryName,
            int cellCount,
            int centerX,
            int centerY,
            int minX,
            int maxX,
            int minY,
            int maxY,
            List<Integer> cells
    ) {}

    /**
     * 判断坐标 (px, py)（200尺度）是否属于深水/海洋区域。
     */
    public static boolean baseWater(int x, int y) {
        double px = x * 200.0 / WorldConfig.SIZE;
        double py = y * 200.0 / WorldConfig.SIZE;
        double coast = 162 + 12 * Math.sin(py * .065) + 6 * Math.sin(py * .19) - 28 * Math.exp(-Math.pow((py - 105) / 27.0, 2));
        return px > coast || (px > 95 && py > 186 + 6 * Math.sin(px * .07));
    }

    /**
     * 根据当前世界地表掩码（400×400），基于连通分量泛洪算法自动识别并分类全部海岛。
     */
    public List<IslandInfo> detectIslands(String mask) {
        if (mask == null || mask.isEmpty()) {
            mask = worldTerrainService.ensure();
        }
        int size = WorldConfig.SIZE;
        int total = size * size;

        // 1. 泛洪标记大陆（西侧边界 x=0 的连通陆地）
        boolean[] mainland = new boolean[total];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int y = 0; y < size; y++) {
            int idx = y * size;
            if (mask.charAt(idx) == '0') {
                mainland[idx] = true;
                q.add(idx);
            }
        }
        while (!q.isEmpty()) {
            int curr = q.remove();
            int cx = curr % size;
            int cy = curr / size;
            for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    int nidx = ny * size + nx;
                    if (!mainland[nidx] && mask.charAt(nidx) == '0') {
                        mainland[nidx] = true;
                        q.add(nidx);
                    }
                }
            }
        }

        // 2. 识别海洋中不与大陆连通的所有独立陆地岛屿分量
        boolean[] visited = new boolean[total];
        List<List<Integer>> components = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int idx = y * size + x;
                if (mask.charAt(idx) == '0' && !mainland[idx] && !visited[idx]) {
                    List<Integer> comp = new ArrayList<>();
                    ArrayDeque<Integer> cq = new ArrayDeque<>();
                    visited[idx] = true;
                    cq.add(idx);
                    while (!cq.isEmpty()) {
                        int p = cq.remove();
                        comp.add(p);
                        int px = p % size;
                        int py = p / size;
                        for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                            int nx = px + d[0];
                            int ny = py + d[1];
                            if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                int nidx = ny * size + nx;
                                if (!visited[nidx] && mask.charAt(nidx) == '0' && !mainland[nidx]) {
                                    visited[nidx] = true;
                                    cq.add(nidx);
                                }
                            }
                        }
                    }
                    if (!comp.isEmpty()) {
                        components.add(comp);
                    }
                }
            }
        }

        // 3. 构建岛屿信息列表并按面积分类排序
        components.sort(Comparator.comparingInt(List::size));
        List<IslandInfo> result = new ArrayList<>();
        int idSeq = 1;
        for (List<Integer> comp : components) {
            int count = comp.size();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            long sumX = 0, sumY = 0;
            for (int cell : comp) {
                int x = cell % size;
                int y = cell / size;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                sumX += x;
                sumY += y;
            }
            int centerX = (int) (sumX / count);
            int centerY = (int) (sumY / count);

            String cat;
            String catName;
            if (count <= 55) {
                cat = CAT_MICRO;
                catName = "小微岛礁 (24~40格)";
            } else if (count < 120) {
                cat = CAT_MEDIUM;
                catName = "中型海岛 (75~100格)";
            } else {
                cat = CAT_LARGE;
                catName = "大型/巨型群岛 (140~608格)";
            }

            result.add(new IslandInfo(idSeq++, cat, catName, count, centerX, centerY, minX, maxX, minY, maxY, comp));
        }

        return result;
    }

    /**
     * 统计分析大地图上海洋岛屿现状、各大类数量分布及安全状态。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getIslandStats() {
        WorldMap world = worldMapRepository.findFirstByOrderByIdAsc().orElse(null);
        String mask = world != null && world.getTerrainData() != null ? world.getTerrainData() : worldTerrainService.ensure();
        List<IslandInfo> islands = detectIslands(mask);

        // 提取全图占用与活跃状态，检查岛屿是否可安全删除
        List<PlayerCity> allCities = playerCityRepository.findAll();
        List<Bandit> allBandits = banditRepository.findAll();
        List<WildTile> allWilds = wildTileRepository.findAll();
        List<March> allMarches = marchRepository.findAll();
        List<BattleSession> allBattles = battleSessionRepository.findAll();

        Set<String> cityCoords = new HashSet<>();
        for (PlayerCity c : allCities) {
            int span = c.getOwnerId() != null ? 2 : 1;
            for (int dy = 0; dy < span; dy++) {
                for (int dx = 0; dx < span; dx++) {
                    cityCoords.add((c.getX() + dx) + "," + (c.getY() + dy));
                }
            }
        }

        Set<String> banditCoords = new HashSet<>();
        for (Bandit b : allBandits) {
            if (!Boolean.TRUE.equals(b.getDefeated())) {
                banditCoords.add(b.getX() + "," + b.getY());
            }
        }

        Map<String, WildTile> wildMap = new HashMap<>();
        for (WildTile w : allWilds) {
            wildMap.put(w.getX() + "," + w.getY(), w);
        }

        Set<String> marchTargets = new HashSet<>();
        for (March m : allMarches) {
            if (m.getTargetX() != null && m.getTargetY() != null) {
                marchTargets.add(m.getTargetX() + "," + m.getTargetY());
            }
        }

        Set<String> battleTargets = new HashSet<>();
        for (BattleSession b : allBattles) {
            if (b.getTargetId() != null) {
                battleTargets.add(b.getTargetId());
            }
        }

        int microCount = 0, mediumCount = 0, largeCount = 0;
        int microCells = 0, mediumCells = 0, largeCells = 0;
        int microDeletable = 0, mediumDeletable = 0, largeDeletable = 0;

        List<Map<String, Object>> islandList = new ArrayList<>();
        int size = WorldConfig.SIZE;

        for (IslandInfo isl : islands) {
            // 岛屿在4个战场的镜像坐标格统计
            int citiesOnIsland = 0;
            int banditsOnIsland = 0;
            int wildsOnIsland = 0;
            int occupiedWilds = 0;
            boolean hasActiveMarch = false;

            for (int cell : isl.cells()) {
                int lx = cell % size;
                int ly = cell / size;
                // 遍历 4 个象限
                int[][] quadCoords = new int[][]{
                        {lx, ly},
                        {WorldConfig.WORLD_SIZE - 1 - lx, ly},
                        {lx, WorldConfig.WORLD_SIZE - 1 - ly},
                        {WorldConfig.WORLD_SIZE - 1 - lx, WorldConfig.WORLD_SIZE - 1 - ly}
                };
                for (int[] coord : quadCoords) {
                    String ck = coord[0] + "," + coord[1];
                    if (cityCoords.contains(ck)) citiesOnIsland++;
                    if (banditCoords.contains(ck)) banditsOnIsland++;
                    if (marchTargets.contains(ck)) hasActiveMarch = true;
                    WildTile wt = wildMap.get(ck);
                    if (wt != null) {
                        wildsOnIsland++;
                        if (Boolean.TRUE.equals(wt.getOccupied()) || (wt.getOccupiedBy() != null && wt.getOccupiedBy() > 0)) {
                            occupiedWilds++;
                        }
                    }
                }
            }

            boolean deletable = (citiesOnIsland == 0 && occupiedWilds == 0 && !hasActiveMarch);
            String deleteReason = "";
            if (!deletable) {
                List<String> reasons = new ArrayList<>();
                if (citiesOnIsland > 0) reasons.add("存在玩家城池 " + citiesOnIsland + " 格");
                if (occupiedWilds > 0) reasons.add("存在玩家占领的领地 " + occupiedWilds + " 块");
                if (hasActiveMarch) reasons.add("有行军正在前往该岛屿");
                deleteReason = String.join("，", reasons);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", isl.id());
            item.put("category", isl.category());
            item.put("categoryName", isl.categoryName());
            item.put("cellCount", isl.cellCount());
            item.put("centerX", isl.centerX());
            item.put("centerY", isl.centerY());
            item.put("minX", isl.minX());
            item.put("maxX", isl.maxX());
            item.put("minY", isl.minY());
            item.put("maxY", isl.maxY());
            item.put("cityCount", citiesOnIsland);
            item.put("banditCount", banditsOnIsland);
            item.put("wildCount", wildsOnIsland);
            item.put("occupiedWildCount", occupiedWilds);
            item.put("deletable", deletable);
            item.put("deleteReason", deleteReason);
            islandList.add(item);

            switch (isl.category()) {
                case CAT_MICRO -> {
                    microCount++;
                    microCells += isl.cellCount();
                    if (deletable) microDeletable++;
                }
                case CAT_MEDIUM -> {
                    mediumCount++;
                    mediumCells += isl.cellCount();
                    if (deletable) mediumDeletable++;
                }
                case CAT_LARGE -> {
                    largeCount++;
                    largeCells += isl.cellCount();
                    if (deletable) largeDeletable++;
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", islands.size());
        stats.put("totalIslands", islands.size());
        stats.put("totalCells", microCells + mediumCells + largeCells);
        stats.put("microCount", microCount);
        stats.put("mediumCount", mediumCount);
        stats.put("largeCount", largeCount);
        stats.put("microCells", microCells);
        stats.put("mediumCells", mediumCells);
        stats.put("largeCells", largeCells);
        stats.put("microDeletable", microDeletable);
        stats.put("mediumDeletable", mediumDeletable);
        stats.put("largeDeletable", largeDeletable);
        stats.put("worldScale", "4战场全图镜像 (800×800)，基底海域尺寸 (400×400)");

        Map<String, Object> byCategory = new LinkedHashMap<>();
        byCategory.put(CAT_MICRO, Map.of(
                "category", CAT_MICRO,
                "name", "小微岛礁",
                "desc", "24 ~ 40 格，单点珊瑚礁/前哨据点",
                "count", microCount,
                "totalCells", microCells,
                "avgCells", microCount > 0 ? microCells / microCount : 0,
                "deletableCount", microDeletable
        ));
        byCategory.put(CAT_MEDIUM, Map.of(
                "category", CAT_MEDIUM,
                "name", "中型海岛",
                "desc", "75 ~ 100 格，可容纳多种野地或单座独立城市",
                "count", mediumCount,
                "totalCells", mediumCells,
                "avgCells", mediumCount > 0 ? mediumCells / mediumCount : 0,
                "deletableCount", mediumDeletable
        ));
        byCategory.put(CAT_LARGE, Map.of(
                "category", CAT_LARGE,
                "name", "大型/巨型群岛",
                "desc", "140 ~ 608 格，宽阔腹地与漫长海岸线，多城群岛",
                "count", largeCount,
                "totalCells", largeCells,
                "avgCells", largeCount > 0 ? largeCells / largeCount : 0,
                "deletableCount", largeDeletable
        ));
        stats.put("byCategory", byCategory);
        stats.put("list", islandList);

        return stats;
    }

    /**
     * 在开阔海洋中批量安全生成指定类型的新岛屿。
     *
     * @param category      岛屿类型：MICRO (小微岛礁 24~40格) / MEDIUM (中型海岛 75~100格) / LARGE (大型群岛 140~608格)
     * @param count         生成数量 (1 ~ 10)
     * @param seedResources 是否自动附带生成海岛基础野地资源点
     */
    @Transactional
    public List<Map<String, Object>> spawnIslands(String category, int count, boolean seedResources, String adminUsername, HttpServletRequest req) {
        if (count <= 0 || count > 10) {
            throw new IllegalArgumentException("单次生成岛屿数量需在 1 ~ 10 之间");
        }
        String cat = (category != null && !category.isBlank()) ? category.trim().toUpperCase() : CAT_MICRO;
        if (!Set.of(CAT_MICRO, CAT_MEDIUM, CAT_LARGE).contains(cat)) {
            throw new IllegalArgumentException("未知的岛屿类型: " + category + "，可选 MICRO / MEDIUM / LARGE");
        }

        WorldMap world = worldMapRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("世界地图未初始化"));
        String storedMask = world.getTerrainData();
        if (storedMask == null || storedMask.length() != WorldConfig.SIZE * WorldConfig.SIZE) {
            storedMask = worldTerrainService.ensure();
        }
        char[] maskChars = storedMask.toCharArray();
        int size = WorldConfig.SIZE;

        // 计算水域相对现有陆地的距离场（用于寻找水域开阔中心点）
        int[] dist = new int[size * size];
        Arrays.fill(dist, -1);
        ArrayDeque<Integer> dq = new ArrayDeque<>();
        for (int i = 0; i < size * size; i++) {
            if (maskChars[i] == '0') {
                dist[i] = 0;
                dq.add(i);
            }
        }
        while (!dq.isEmpty()) {
            int curr = dq.remove();
            int d = dist[curr];
            int cx = curr % size, cy = curr / size;
            for (int[] dir : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = cx + dir[0], ny = cy + dir[1];
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    int nidx = ny * size + nx;
                    if (dist[nidx] == -1) {
                        dist[nidx] = d + 1;
                        dq.add(nidx);
                    }
                }
            }
        }

        int minCandidateDist = CAT_MICRO.equals(cat) ? 2 : (CAT_MEDIUM.equals(cat) ? 3 : 4);
        List<Integer> candidateCenters = new ArrayList<>();
        for (int i = 0; i < size * size; i++) {
            if (dist[i] >= minCandidateDist) {
                candidateCenters.add(i);
            }
        }
        if (candidateCenters.isEmpty() && minCandidateDist > 2) {
            for (int i = 0; i < size * size; i++) {
                if (dist[i] >= 2) candidateCenters.add(i);
            }
        }

        List<Map<String, Object>> createdList = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            // 根据类别配置半径区间与目标格子数
            double rxMin, rxMax, ryMin, ryMax;
            int targetMinCells, targetMaxCells;
            if (CAT_MICRO.equals(cat)) {
                // 24 ~ 40 格
                rxMin = 2.7; rxMax = 3.4;
                ryMin = 3.5; ryMax = 4.4;
                targetMinCells = 24;
                targetMaxCells = 40;
            } else if (CAT_MEDIUM.equals(cat)) {
                // 75 ~ 100 格
                rxMin = 4.6; rxMax = 5.5;
                ryMin = 5.5; ryMax = 6.5;
                targetMinCells = 75;
                targetMaxCells = 100;
            } else {
                // 140 ~ 608 格 (自适应当前海域)
                rxMin = 5.5; rxMax = 8.5;
                ryMin = 6.5; ryMax = 11.0;
                targetMinCells = 120;
                targetMaxCells = 350;
            }

            Collections.shuffle(candidateCenters);
            boolean placed = false;
            double chosenCx = 0, chosenCy = 0;
            List<int[]> islandCoords = new ArrayList<>();

            for (int cIdx : candidateCenters) {
                int cx = cIdx % size;
                int cy = cIdx / size;
                double rx = ThreadLocalRandom.current().nextDouble(rxMin, rxMax);
                double ry = ThreadLocalRandom.current().nextDouble(ryMin, ryMax);

                int xMin = Math.max(1, (int) Math.floor(cx - rx * 1.3));
                int xMax = Math.min(size - 2, (int) Math.ceil(cx + rx * 1.3));
                int yMin = Math.max(1, (int) Math.floor(cy - ry * 1.3));
                int yMax = Math.min(size - 2, (int) Math.ceil(cy + ry * 1.3));

                Set<Integer> currentIslandCells = new HashSet<>();
                boolean valid = true;

                for (int y = yMin; y <= yMax; y++) {
                    for (int x = xMin; x <= xMax; x++) {
                        double dx = (x - cx) / rx;
                        double dy = (y - cy) / ry;
                        double angle = Math.atan2(dy, dx);
                        double edge = 1 + .13 * Math.sin(angle * 3 + cx) + .08 * Math.sin(angle * 5 + cy);
                        if (dx * dx + dy * dy < edge * edge) {
                            int idx = y * size + x;
                            if (maskChars[idx] != '1') {
                                valid = false;
                                break;
                            }
                            currentIslandCells.add(idx);
                        }
                    }
                    if (!valid) break;
                }

                if (valid && currentIslandCells.size() >= targetMinCells && currentIslandCells.size() <= targetMaxCells) {
                    // 确保与现有陆地保持至少 1 格水域隔离，不与任何大陆或既有海岛融合
                    boolean touches = false;
                    for (int idx : currentIslandCells) {
                        int ix = idx % size, iy = idx / size;
                        for (int[] dir : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                            int nx = ix + dir[0], ny = iy + dir[1];
                            if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                int nidx = ny * size + nx;
                                if (!currentIslandCells.contains(nidx) && maskChars[nidx] == '0') {
                                    touches = true;
                                    break;
                                }
                            }
                        }
                        if (touches) break;
                    }

                    if (!touches) {
                        chosenCx = cx;
                        chosenCy = cy;
                        for (int idx : currentIslandCells) {
                            islandCoords.add(new int[]{idx % size, idx / size});
                        }
                        placed = true;
                        break;
                    }
                }
            }

            if (!placed) {
                if (createdList.isEmpty()) {
                    throw new IllegalStateException("海洋中已无足够开阔水域容纳新的 " + cat + " 岛屿，请先移除部分空闲岛屿。");
                }
                break;
            }

            // 在地形掩码上着色为陆地 ('0')
            for (int[] coord : islandCoords) {
                maskChars[coord[1] * size + coord[0]] = '0';
            }

            // 附带生成海岛基础野地资源点
            int spawnedWilds = 0;
            if (seedResources) {
                List<int[]> availableCells = new ArrayList<>(islandCoords);
                Collections.shuffle(availableCells, ThreadLocalRandom.current());
                int targetWilds = CAT_MICRO.equals(cat) ? 1 : (CAT_MEDIUM.equals(cat) ? 2 : 4);
                int actualWilds = Math.min(targetWilds, availableCells.size());
                List<String> resTypes = List.of("oil", "ironworks", "grainfield", "rarefactory");

                List<WildTile> wildsToSave = new ArrayList<>();
                for (int w = 0; w < actualWilds; w++) {
                    int[] basePt = availableCells.get(w);
                    int level = ThreadLocalRandom.current().nextInt(2, 8);
                    String resType = (w == 0 && targetWilds >= 2) ? "forest" : resTypes.get(w % resTypes.size());

                    // 在 4 个战场镜像生成对应资源野地
                    int[][] quadCoords = new int[][]{
                            {basePt[0], basePt[1]},
                            {WorldConfig.WORLD_SIZE - 1 - basePt[0], basePt[1]},
                            {basePt[0], WorldConfig.WORLD_SIZE - 1 - basePt[1]},
                            {WorldConfig.WORLD_SIZE - 1 - basePt[0], WorldConfig.WORLD_SIZE - 1 - basePt[1]}
                    };
                    for (int[] qc : quadCoords) {
                        WildTile tile = new WildTile();
                        tile.setWorldId(world.getId());
                        tile.setType(resType);
                        tile.setX(qc[0]);
                        tile.setY(qc[1]);
                        tile.setLevel(level);
                        tile.setGarrison(JsonUtil.toJson(WorldConfig.wildGarrison(level, false)));
                        tile.setScouted(false);
                        tile.setOccupied(false);
                        tile.setOccupiedBy(null);
                        tile.setOccupiedCitySlot(0);
                        tile.setTotalRes(level * WorldConfig.RES_PER_WILD_LEVEL);
                        tile.setMined(0);
                        wildsToSave.add(tile);
                    }
                }
                if (!wildsToSave.isEmpty()) {
                    wildTileRepository.saveAll(wildsToSave);
                    spawnedWilds = wildsToSave.size();
                }
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("category", cat);
            info.put("categoryName", CAT_MICRO.equals(cat) ? "小微岛礁" : (CAT_MEDIUM.equals(cat) ? "中型海岛" : "大型群岛"));
            info.put("cellCount", islandCoords.size());
            info.put("centerX", (int) Math.round(chosenCx));
            info.put("centerY", (int) Math.round(chosenCy));
            info.put("spawnedWilds", spawnedWilds);
            createdList.add(info);
        }

        // 保存新地形数据并推进地形版本
        world.setTerrainData(new String(maskChars));
        world.setTerrainVersion((world.getTerrainVersion() != null ? world.getTerrainVersion() : 3) + 1);
        worldMapRepository.saveAndFlush(world);

        auditLogService.record(adminUsername, "SPAWN_ISLANDS", "WORLD_TERRAIN", String.valueOf(world.getId()),
                String.format("成功在开阔海域批量生成 %d 个【%s】岛屿 (自动生成野地数: %d)",
                        createdList.size(), cat, createdList.stream().mapToInt(c -> (int) c.get("spawnedWilds")).sum()), req);

        return createdList;
    }

    /**
     * 批量或定向移除空闲岛屿（严格安全校验）。
     *
     * @param category  按类别批量移除 (MICRO / MEDIUM / LARGE)
     * @param count     批量移除数量
     * @param islandIds 指定要移除的岛屿编号列表
     */
    @Transactional
    public Map<String, Object> deleteIslands(String category, Integer count, List<Integer> islandIds, String adminUsername, HttpServletRequest req) {
        WorldMap world = worldMapRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("世界地图未初始化"));
        String storedMask = world.getTerrainData();
        if (storedMask == null || storedMask.length() != WorldConfig.SIZE * WorldConfig.SIZE) {
            storedMask = worldTerrainService.ensure();
        }
        char[] maskChars = storedMask.toCharArray();
        int size = WorldConfig.SIZE;

        List<IslandInfo> allIslands = detectIslands(new String(maskChars));

        // 收集受保护的占用坐标（城池、行军、交战、被占领领地）
        List<PlayerCity> allCities = playerCityRepository.findAll();
        Set<String> protectedCoords = new HashSet<>();
        for (PlayerCity c : allCities) {
            int span = c.getOwnerId() != null ? 2 : 1;
            for (int dy = 0; dy < span; dy++) {
                for (int dx = 0; dx < span; dx++) {
                    protectedCoords.add((c.getX() + dx) + "," + (c.getY() + dy));
                }
            }
        }
        for (March m : marchRepository.findAll()) {
            if (m.getTargetX() != null && m.getTargetY() != null) {
                protectedCoords.add(m.getTargetX() + "," + m.getTargetY());
            }
        }
        for (WildTile w : wildTileRepository.findAll()) {
            if (Boolean.TRUE.equals(w.getOccupied()) || (w.getOccupiedBy() != null && w.getOccupiedBy() > 0)) {
                protectedCoords.add(w.getX() + "," + w.getY());
            }
        }

        // 确定要删除的候选岛屿
        List<IslandInfo> candidates = new ArrayList<>();
        if (islandIds != null && !islandIds.isEmpty()) {
            Set<Integer> targetIdSet = new HashSet<>(islandIds);
            for (IslandInfo isl : allIslands) {
                if (targetIdSet.contains(isl.id())) {
                    candidates.add(isl);
                }
            }
        } else if (category != null && !category.isBlank()) {
            String cat = category.trim().toUpperCase();
            int limit = (count != null && count > 0) ? count : 1;
            for (IslandInfo isl : allIslands) {
                if (isl.category().equals(cat) && candidates.size() < limit) {
                    candidates.add(isl);
                }
            }
        } else {
            throw new IllegalArgumentException("请指定要删除的岛屿类别或岛屿编号列表");
        }

        List<Integer> deletedIds = new ArrayList<>();
        List<Integer> skippedIds = new ArrayList<>();
        Map<Integer, String> skippedReasons = new LinkedHashMap<>();

        int clearedWildTiles = 0;
        int clearedBandits = 0;

        for (IslandInfo isl : candidates) {
            boolean safe = true;
            String reason = "";

            // 检查4个象限镜像是否存在受保护物
            for (int cell : isl.cells()) {
                int lx = cell % size;
                int ly = cell / size;
                int[][] quadCoords = new int[][]{
                        {lx, ly},
                        {WorldConfig.WORLD_SIZE - 1 - lx, ly},
                        {lx, WorldConfig.WORLD_SIZE - 1 - ly},
                        {WorldConfig.WORLD_SIZE - 1 - lx, WorldConfig.WORLD_SIZE - 1 - ly}
                };
                for (int[] qc : quadCoords) {
                    String key = qc[0] + "," + qc[1];
                    if (protectedCoords.contains(key)) {
                        safe = false;
                        reason = "岛屿坐标 (" + key + ") 存在玩家城池/领地/行军，受到绝对保护";
                        break;
                    }
                }
                if (!safe) break;
            }

            if (!safe) {
                skippedIds.add(isl.id());
                skippedReasons.put(isl.id(), reason);
                continue;
            }

            // 清理岛屿上的无主野地与寇
            for (int cell : isl.cells()) {
                int lx = cell % size;
                int ly = cell / size;
                int[][] quadCoords = new int[][]{
                        {lx, ly},
                        {WorldConfig.WORLD_SIZE - 1 - lx, ly},
                        {lx, WorldConfig.WORLD_SIZE - 1 - ly},
                        {WorldConfig.WORLD_SIZE - 1 - lx, WorldConfig.WORLD_SIZE - 1 - ly}
                };
                for (int[] qc : quadCoords) {
                    List<WildTile> toDelWilds = wildTileRepository.findByWorldIdAndXBetweenAndYBetweenOrderByIdAsc(
                            world.getId(), qc[0], qc[0], qc[1], qc[1]);
                    for (WildTile wt : toDelWilds) {
                        if (!Boolean.TRUE.equals(wt.getOccupied())) {
                            wildTileRepository.delete(wt);
                            clearedWildTiles++;
                        }
                    }
                    List<Bandit> toDelBandits = banditRepository.findByWorldId(world.getId()).stream()
                            .filter(b -> b.getX() == qc[0] && b.getY() == qc[1])
                            .toList();
                    for (Bandit b : toDelBandits) {
                        banditRepository.delete(b);
                        clearedBandits++;
                    }
                }

                // 将该格置回海水 ('1')
                maskChars[ly * size + lx] = '1';
            }

            deletedIds.add(isl.id());
        }

        if (!deletedIds.isEmpty()) {
            world.setTerrainData(new String(maskChars));
            world.setTerrainVersion((world.getTerrainVersion() != null ? world.getTerrainVersion() : 3) + 1);
            worldMapRepository.saveAndFlush(world);
            wildTileRepository.flush();
            banditRepository.flush();

            auditLogService.record(adminUsername, "DELETE_ISLANDS", "WORLD_TERRAIN", String.valueOf(world.getId()),
                    String.format("成功移除 %d 个岛屿 (编号: %s, 清理无主野地: %d 块, 清理寇据点: %d 个)",
                            deletedIds.size(), deletedIds, clearedWildTiles, clearedBandits), req);
        }

        return Map.of(
                "success", true,
                "deletedCount", deletedIds.size(),
                "deletedIds", deletedIds,
                "skippedCount", skippedIds.size(),
                "skippedIds", skippedIds,
                "skippedReasons", skippedReasons,
                "clearedWildTiles", clearedWildTiles,
                "clearedBandits", clearedBandits
        );
    }

    /**
     * 获取指定岛屿类别 (MICRO / MEDIUM / LARGE / ALL_ISLANDS / MAINLAND) 在选定战场中的全部有效陆地坐标集合。
     */
    public List<int[]> getQualifiedCoordinates(String mask, String islandCategory, String battlefield) {
        String cat = (islandCategory != null && !islandCategory.isBlank()) ? islandCategory.trim().toUpperCase() : "ALL";
        int size = WorldConfig.SIZE;

        // 若不限地形（ALL），直接返回 null 代表使用全图陆地坐标遍历
        if ("ALL".equals(cat)) {
            return null;
        }

        List<IslandInfo> islands = detectIslands(mask);
        Set<Integer> targetBaseCells = new HashSet<>();

        if ("MAINLAND".equals(cat) || "MAINLAND_ONLY".equals(cat)) {
            // 大陆本土格子
            boolean[] mainland = new boolean[size * size];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int y = 0; y < size; y++) {
                int idx = y * size;
                if (mask.charAt(idx) == '0') {
                    mainland[idx] = true;
                    q.add(idx);
                }
            }
            while (!q.isEmpty()) {
                int curr = q.remove();
                int cx = curr % size, cy = curr / size;
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int nx = cx + d[0], ny = cy + d[1];
                    if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                        int nidx = ny * size + nx;
                        if (!mainland[nidx] && mask.charAt(nidx) == '0') {
                            mainland[nidx] = true;
                            q.add(nidx);
                        }
                    }
                }
            }
            for (int i = 0; i < size * size; i++) {
                if (mainland[i]) targetBaseCells.add(i);
            }
        } else {
            // 海岛格过滤
            for (IslandInfo isl : islands) {
                if ("ALL_ISLANDS".equals(cat) || isl.category().equals(cat)) {
                    targetBaseCells.addAll(isl.cells());
                }
            }
        }

        if (targetBaseCells.isEmpty()) {
            return Collections.emptyList();
        }

        // 映射到指定的战场或全图
        String bf = (battlefield != null && !battlefield.isBlank()) ? battlefield.trim().toLowerCase() : "all";
        List<int[]> result = new ArrayList<>();

        boolean doNW = "all".equals(bf) || "northwest".equals(bf);
        boolean doNE = "all".equals(bf) || "northeast".equals(bf);
        boolean doSW = "all".equals(bf) || "southwest".equals(bf);
        boolean doSE = "all".equals(bf) || "southeast".equals(bf);

        for (int cell : targetBaseCells) {
            int lx = cell % size;
            int ly = cell / size;
            if (doNW) result.add(new int[]{lx, ly});
            if (doNE) result.add(new int[]{WorldConfig.WORLD_SIZE - 1 - lx, ly});
            if (doSW) result.add(new int[]{lx, WorldConfig.WORLD_SIZE - 1 - ly});
            if (doSE) result.add(new int[]{WorldConfig.WORLD_SIZE - 1 - lx, WorldConfig.WORLD_SIZE - 1 - ly});
        }

        return result;
    }

    /**
     * 判断任意 800×800 世界坐标的地形及岛屿类型归属。
     */
    public String classifyCoordinate(String mask, int x, int y) {
        if (WorldTerrainService.sea(mask, x, y)) {
            return "海洋";
        }
        int size = WorldConfig.SIZE;
        int lx = (x >= size && x < size * 2) ? (size * 2 - 1 - x) : x;
        int ly = (y >= size && y < size * 2) ? (size * 2 - 1 - y) : y;
        int cellIdx = ly * size + lx;

        List<IslandInfo> islands = detectIslands(mask);
        for (IslandInfo isl : islands) {
            if (isl.cells().contains(cellIdx)) {
                return isl.categoryName();
            }
        }
        return "大陆本土";
    }
}
