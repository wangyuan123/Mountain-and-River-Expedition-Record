package com.wargame.service;

import com.wargame.model.entity.NpcCity;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.NpcCityRepository;
import com.wargame.repository.WorldMapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** NPC 城市已取消；仅保留旧行军、旧战斗完成所需的记录清理入口。 */
@Service
@RequiredArgsConstructor
public class NpcCitySpawnService {
    private final WorldMapRepository worlds;
    private final NpcCityRepository cities;
    private final MarchRepository marches;
    private final BattleSessionRepository battles;

    /** 旧战斗胜利后标记击败，阻止其他旧行军重复领取奖励，不再生成替代城市。 */
    @Transactional
    public void replace(NpcCity defeated) {
        worlds.lockById(defeated.getWorldId()).orElseThrow();
        defeated.setDefeated(true);
        defeated.setArmy("{}");
        defeated.setForts("{}");
        cities.save(defeated);
    }

    /** 启动和定时人口维护时清理旧城；有行军或战斗引用的记录保留至结算结束。 */
    @Transactional
    public void removeInactive(Long worldId) {
        worlds.lockById(worldId).orElseThrow();
        for (NpcCity city : cities.findByWorldId(worldId)) {
            String id = String.valueOf(city.getId());
            if (!marches.existsByTargetIdAndTargetKindIn(id, List.of("npc"))
                    && !battles.existsByTargetKindAndTargetId("npc", id)) cities.delete(city);
        }
        cities.flush();
    }
}
