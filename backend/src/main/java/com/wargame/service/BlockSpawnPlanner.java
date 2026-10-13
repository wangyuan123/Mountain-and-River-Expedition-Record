package com.wargame.service;

import com.wargame.model.entity.Bandit;
import com.wargame.model.entity.NpcCity;
import com.wargame.model.entity.WildTile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** 在一次布点批次内按 16×16 分块均衡同类目标密度；陆地、海域各自计算。 */
public final class BlockSpawnPlanner {
    public static final int BLOCK_SIZE = 16;

    private final int size;
    private final int columns;
    private final List<Block> blocks = new ArrayList<>();

    public BlockSpawnPlanner(String mask, int size, boolean sea, Set<String> used, List<int[]> targets) {
        this(mask, size, sea, used, targets, null);
    }

    /**
     * 以同类地形总面积折算密度，避免海岸小块因绝对数量少而被过量补点。
     * @param targets 当前可见的同类目标；隐藏待刷新目标不计入密度
     * @param candidates 可选的局部候选格（按 size 编码）；null 表示整个开放战区
     */
    public BlockSpawnPlanner(String mask, int size, boolean sea, Set<String> used,
                             List<int[]> targets, Collection<Integer> candidates) {
        if (size < 1) throw new IllegalArgumentException("布点范围必须大于零");
        this.size = size;
        columns = (size + BLOCK_SIZE - 1) / BLOCK_SIZE;
        for (int i = 0; i < columns * columns; i++) blocks.add(new Block());
        Set<Integer> allowed = candidates == null ? null : new HashSet<>(candidates);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (WorldTerrainService.sea(mask, x, y) != sea) continue;
            Block block = blocks.get(blockIndex(x, y));
            block.area++;
            if (!used.contains(key(x, y)) && (allowed == null || allowed.contains(y * size + x)))
                block.cells.add(y * size + x);
        }
        for (int[] target : targets) {
            int x = target[0], y = target[1];
            if (x >= 0 && y >= 0 && x < size && y < size && WorldTerrainService.sea(mask, x, y) == sea)
                blocks.get(blockIndex(x, y)).count++;
        }
        blocks.forEach(block -> Collections.shuffle(block.cells));
    }

    /**
     * 选择新增后密度最低且有合法空位的块，密度相同时随机选块，块内随机取点。
     * 返回前立即增加密度并预留单格，保证同批目标均衡且不重叠；无空位返回 null。
     * @param used 本批次只增加占位的集合；调用方可继续加入 NPC 间距禁区
     * @param oldX 迁移前横坐标；与 oldY 同时提供时排除原格
     * @param oldY 迁移前纵坐标
     */
    public int[] pick(Set<String> used, Integer oldX, Integer oldY) {
        Block selected = null;
        int selectedCell = -1;
        int ties = 0;
        for (Block block : blocks) {
            if (block.area == 0 || block.cells.isEmpty()) continue;
            // 交叉相乘精确比较 (现有数量 + 1) / 地形面积，不受浮点误差影响。
            int comparison = selected == null ? -1 : Long.compare(
                    (long) (block.count + 1) * selected.area, (long) (selected.count + 1) * block.area);
            if (comparison > 0) continue;
            int cell = availableCell(block, used, oldX, oldY);
            if (cell < 0) continue;
            if (comparison < 0) ties = 0;
            if (ThreadLocalRandom.current().nextInt(++ties) == 0) {
                selected = block;
                selectedCell = cell;
            }
        }
        if (selected == null) return null;
        selected.cells.remove(Integer.valueOf(selectedCell));
        selected.count++;
        int x = selectedCell % size, y = selectedCell / size;
        used.add(key(x, y));
        return new int[]{x, y};
    }

    private int availableCell(Block block, Set<String> used, Integer oldX, Integer oldY) {
        for (int i = block.cells.size() - 1; i >= 0; i--) {
            int cell = block.cells.get(i), x = cell % size, y = cell / size;
            if (used.contains(key(x, y))) {
                // 同批占位只增加，已失效候选可永久移除，避免每补一个据点都重扫全图。
                block.cells.remove(i);
                continue;
            }
            if (oldX != null && oldY != null && x == oldX && y == oldY) continue;
            return cell;
        }
        return -1;
    }

    /** NPC 城市与流寇合并统计，已击败目标在成功重生后才重新计入密度。 */
    public static List<int[]> npcPositions(List<NpcCity> cities, List<Bandit> bandits) {
        List<int[]> positions = new ArrayList<>();
        cities.stream().filter(n -> !Boolean.TRUE.equals(n.getDefeated()))
                .forEach(n -> positions.add(new int[]{n.getX(), n.getY()}));
        bandits.stream().filter(n -> !Boolean.TRUE.equals(n.getDefeated()))
                .forEach(n -> positions.add(new int[]{n.getX(), n.getY()}));
        return positions;
    }

    /** 已占领的可见野地仍计入地图密度，耗尽且废弃的隐藏野地不计入。 */
    public static List<int[]> wildPositions(List<WildTile> wilds) {
        List<int[]> positions = new ArrayList<>();
        wilds.stream().filter(w -> !w.isDormant()).forEach(w -> positions.add(new int[]{w.getX(), w.getY()}));
        return positions;
    }

    private int blockIndex(int x, int y) { return y / BLOCK_SIZE * columns + x / BLOCK_SIZE; }
    private static String key(int x, int y) { return x + "," + y; }

    private static final class Block {
        private int area;
        private int count;
        private final List<Integer> cells = new ArrayList<>();
    }
}
