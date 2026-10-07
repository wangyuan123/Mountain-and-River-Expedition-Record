package com.wargame.service;

import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.Bandit;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** 陆地据点按四类资源野地合计配额维持等级分布，海域守军不参与配额。 */
@Service
@RequiredArgsConstructor
public class LandBanditPopulationService {
    private final WorldMapRepository worlds;
    private final NpcFootprintService footprints;
    private final BanditRepository bandits;
    private final MarchRepository marches;
    private final WorldTerrainService terrain;
    private final WildTileRepository wilds;
    private final NpcCityRepository npcs;
    private final PlayerCityRepository cities;
    private final PlayerRepository players;

    /**
     * 按等级保留现有目标、移除无行军引用的超额目标并补足缺额。
     * 已击败目标计入配额，等待迁移刷新；保留目标的守军损失和击败状态不变。
     * 没有空闲陆地或超额目标仍有行军引用时，留待下一轮维护。
     * @param worldId 需要维护的世界 ID
     */
    @Transactional
    public void ensure(Long worldId) {
        var world = worlds.lockById(worldId).orElseThrow();
        String mask = terrain.current();
        if (mask == null || mask.isEmpty()) return;
        // 自动布点限于开放战区，不能把展示世界尺寸当作可用陆地边界。
            int size = Math.min(WorldConfig.SIZE, world.getSize() == null ? WorldConfig.SIZE : world.getSize());
        List<Bandit> land = bandits.findByWorldId(worldId).stream()
                .filter(b -> !WorldTerrainService.sea(mask, b.getX(), b.getY()))
                .sorted(Comparator.comparing(Bandit::getId)).toList();
        int[] counts = new int[WorldConfig.MAX_NPC_LEVEL + 1];
        for (Bandit npc : land) {
            int level = npc.getLevel() == null ? 0 : npc.getLevel();
            boolean valid = level >= 1 && level <= WorldConfig.MAX_NPC_LEVEL;
            if (valid && counts[level] < WorldConfig.landBanditQuota(level)) {
                counts[level]++;
            } else if (!marches.existsByTargetIdAndTargetKindIn(String.valueOf(npc.getId()), List.of("bandit"))) {
                bandits.delete(npc);
            } else if (valid) {
                counts[level]++;
            }
        }
        bandits.flush();
        footprints.repair(worldId);
        boolean missing = false;
        for (int level = 1; level <= WorldConfig.MAX_NPC_LEVEL; level++)
            if (counts[level] < WorldConfig.landBanditQuota(level)) missing = true;
        if (!missing) return;
        // 世界锁保护整批占位；使用实际地图尺寸，兼容已扩展地图，并保留完整城市占地。
        var used = occupied(worldId);
        List<Integer> vacant = new ArrayList<>();
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (WorldTerrainService.vacantFootprint(mask, size, false, used, x, y, 2)) vacant.add(y * size + x);
        }
        Collections.shuffle(vacant);
        int cursor = 0;
        for (int level = 1; level <= WorldConfig.MAX_NPC_LEVEL; level++) {
            for (int n = counts[level]; n < WorldConfig.landBanditQuota(level); n++) {
                // 候选列表会与本轮新据点相交，保存前重新检查四格。
                while (cursor < vacant.size() && !WorldTerrainService.vacantFootprint(mask, size, false, used,
                        vacant.get(cursor) % size, vacant.get(cursor) / size, 2)) cursor++;
                if (cursor >= vacant.size()) return;
                int cell = vacant.get(cursor++);
                Bandit npc = new Bandit();
                npc.setWorldId(worldId); npc.setX(cell % size); npc.setY(cell / size); npc.setLevel(level);
                npc.setName(WorldConfig.BANDIT_NAMES.get(ThreadLocalRandom.current().nextInt(WorldConfig.BANDIT_NAMES.size())) + " Lv." + level);
                npc.setArmy(JsonUtil.toJson(WorldConfig.BANDIT_LEVELS.get(level - 1).army()));
                npc.setCommanderName(JapaneseOfficers.getCommanderForLevel(level));
                npc.setDefeated(false);
                bandits.save(npc);
                reserve(used, npc.getX() - 1, npc.getY() - 1, 4);
            }
        }
    }

    /** 使用完整世界坐标保留各目标与玩家四格占地，兼容超出基础地形尺寸的象限。 */
    private Set<String> occupied(Long worldId) {
        Set<String> used = new HashSet<>();
        wilds.findByWorldId(worldId).forEach(w -> used.add(w.getX() + "," + w.getY()));
        // NPC 外围预留一格，补点时不能生成首尾相连的据点。
        npcs.findByWorldId(worldId).forEach(n -> reserve(used, n.getX() - 1, n.getY() - 1, 4));
        bandits.findByWorldId(worldId).forEach(b -> reserve(used, b.getX() - 1, b.getY() - 1, 4));
        cities.findByWorldId(worldId).forEach(c -> {
            int span = c.getOwnerId() != null && players.existsById(c.getOwnerId()) ? 2 : 1;
            reserve(used, c.getX(), c.getY(), span);
        });
        players.findAll().forEach(p -> {
            if (p.getCityPosX() != null && p.getCityPosY() != null) reserve(used, p.getCityPosX(), p.getCityPosY(), 2);
        });
        return used;
    }

    private void reserve(Set<String> used, int x, int y, int span) {
        for (int dy = 0; dy < span; dy++) for (int dx = 0; dx < span; dx++) used.add((x + dx) + "," + (y + dy));
    }
}
