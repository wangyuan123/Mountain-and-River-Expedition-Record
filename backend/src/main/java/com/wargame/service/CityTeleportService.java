package com.wargame.service;

import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 城市传送只移动当前请求城市，四格选址、坐标更新和道具扣除在同一事务中完成。 */
@Service
@RequiredArgsConstructor
public class CityTeleportService {
    public static final String NORMAL = "teleportNormal";
    public static final String ADVANCED = "teleportAdvanced";
    public static final Set<String> BATTLEFIELDS = Set.of("northwest", "northeast", "southwest", "southeast");
    private final AccountService accounts;
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final PlayerItemRepository items;
    private final MarchRepository marches;
    private final BattleSessionRepository battles;
    private final CityScope scope;
    private final WorldTerrainService terrain;
    private final WebSocketPushService push;
    private final EntityManager em;

    /** 高级传送预览不受建城名额限制；提交时仍重新检查占用及城市状态。 */
    @Transactional
    public Map<String, Object> preview(Long playerId, String battlefield, int x, int y) {
        requireBattlefield(battlefield);
        Placement placement = placement(playerId);
        String reason = terrain.siteReason(placement.world().getId(), placement.mask(), x, y, null);
        if (reason.isEmpty() && !inBattlefield(battlefield, x, y)) reason = "传送坐标不属于所选战场，四格占地必须完整位于同一战场";
        if (reason.isEmpty()) reason = mobilityReason(placement.city());
        return Map.of("cityId", placement.city().getId(), "x", x, "y", y, "span", 2,
                "valid", reason.isEmpty(), "reason", reason, "battlefield", battlefield,
                "maxCoordinate", WorldConfig.WORLD_SIZE - 2);
    }

    /** 普通传送忽略客户端坐标，高级传送严格使用左上角整数坐标；失败回滚且不扣道具。 */
    @Transactional
    public Map<String, Object> teleport(Long playerId, String itemId, String battlefield, Integer x, Integer y) {
        if (!Set.of(NORMAL, ADVANCED).contains(Objects.requireNonNullElse(itemId, "")))
            throw new IllegalArgumentException("请选择普通传送或高级传送道具");
        if (ADVANCED.equals(itemId) && (x == null || y == null))
            throw new IllegalArgumentException("高级传送需要指定坐标");
        requireBattlefield(battlefield);
        Placement placement = placement(playerId);
        PlayerCity city = placement.city();
        String blocker = mobilityReason(city);
        if (!blocker.isEmpty()) throw new IllegalArgumentException(blocker);
        if (NORMAL.equals(itemId)) {
            int[] target = randomDestination(placement.world().getId(), placement.mask(), battlefield);
            x = target[0]; y = target[1];
        }
        if (!inBattlefield(battlefield, x, y)) throw new IllegalArgumentException("传送坐标不属于所选战场，四格占地必须完整位于同一战场");
        String reason = terrain.siteReason(placement.world().getId(), placement.mask(), x, y, null);
        if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
        long now = System.currentTimeMillis();
        if (items.tryConsume(playerId, itemId, 1, now) == 0)
            throw new IllegalArgumentException(ItemDef.ITEMS.get(itemId).name() + "数量不足");

        // tryConsume 会清除持久化上下文；重新读取锁住的城市/玩家，避免合并旧经济快照。
        city = cities.findForUpdate(city.getId()).orElseThrow();
        Player player = players.lockById(playerId).orElseThrow();
        int oldX = city.getX(), oldY = city.getY();
        city.setX(x); city.setY(y);
        cities.saveAndFlush(city);
        // 主城另存于玩家表；分城传送不能覆盖主城坐标或另一个标签页选择的视角。
        if (city.getCitySlot() == 0) { player.setCityPosX(x); player.setCityPosY(y); }
        if (Objects.equals(player.getActiveCityId(), city.getId())
                || (player.getActiveCityId() == null && city.getCitySlot() == 0)) {
            player.setPosX(x); player.setPosY(y);
        }
        players.saveAndFlush(player);
        Map<String, Object> result = Map.of("success", true, "cityId", city.getId(), "itemId", itemId,
                "x", x, "y", y, "oldX", oldX, "oldY", oldY,
                "message", city.getName() + "已传送至 (" + x + ", " + y + ")");
        // 推送服务在提交后广播，其他玩家和同账号标签页才会刷新新坐标。
        push.broadcast("city_teleport", Map.of("cityId", city.getId(), "ownerId", playerId, "x", x, "y", y));
        return result;
    }

    /** 与行军共用账号锁，与建城/地图补点共用世界锁；锁后刷新以绕过鉴权旧快照。 */
    private Placement placement(Long playerId) {
        accounts.lockPlayer(playerId);
        WorldMap world = terrain.lockWorld();
        String mask = WorldTerrainService.playableMask(terrain.ensure());
        PlayerCity selected = scope.selected(playerId).orElseThrow(() -> new IllegalArgumentException("城市不存在"));
        PlayerCity city = cities.findForUpdate(selected.getId()).orElseThrow();
        em.flush();
        em.refresh(city, LockModeType.PESSIMISTIC_WRITE);
        if (!playerId.equals(city.getOwnerId()) || city.getCitySlot() == null
                || !world.getId().equals(city.getWorldId())) throw new IllegalArgumentException("城市不存在或不属于你");
        return new Placement(world, mask, city);
    }

    /** 保留全部行军的来源和目标坐标，必须等相关部队返城或撤防后才能移动城市。 */
    private String mobilityReason(PlayerCity city) {
        if (city.getReadyAt() > System.currentTimeMillis()) return "城市仍在建设中，不能传送";
        if (city.isSurrenderFrozen(System.currentTimeMillis())) return "归降移交中的城市不能传送";
        if (!marches.findCityTeleportDepartures(city.getOwnerId(), city.getCitySlot()).isEmpty())
            return "本城仍有外派、采集、驻防或返程部队，请等待部队返城后传送";
        if (!marches.findCityTeleportArrivals(city.getId().toString()).isEmpty())
            return "本城仍有来袭、运输、调遣或驻防部队，不能传送";
        if (!battles.findActivePlayerCityBattles(city.getId().toString()).isEmpty()) return "城市正在交战，不能传送";
        if (city.getCitySlot() == 0 && !em.createQuery("select m from IncomingMarch m where m.targetPlayerId = :id", IncomingMarch.class)
                .setParameter("id", city.getOwnerId()).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList().isEmpty())
            return "本城仍有日寇来袭，不能传送";
        return "";
    }

    /** 蓄水池抽样使每个合法左上角坐标等概率；全图无落点时明确失败，不反复随机碰运气。 */
    private int[] randomDestination(Long worldId, String mask, String battlefield) {
        Set<String> used = terrain.occupiedCoordinates(worldId);
        int count = 0;
        int[] target = null;
        int minX = battlefieldX(battlefield), minY = battlefieldY(battlefield);
        for (int y = minY; y < minY + WorldConfig.SIZE - 1; y++) {
            for (int x = minX; x < minX + WorldConfig.SIZE - 1; x++) {
                if (WorldTerrainService.vacantFootprint(mask, WorldConfig.WORLD_SIZE, false, used, x, y, 2)
                        && ThreadLocalRandom.current().nextInt(++count) == 0) target = new int[]{x, y};
            }
        }
        if (target == null) throw new IllegalArgumentException("地图中没有满足四格建城条件的传送目的地");
        return target;
    }

    /** 战场必须显式选择，四格不能跨越战场中轴；普通和高级传送共用范围校验。 */
    private static void requireBattlefield(String battlefield) {
        if (battlefield == null || !BATTLEFIELDS.contains(battlefield)) throw new IllegalArgumentException("请选择有效的战场");
    }

    private static boolean inBattlefield(String battlefield, int x, int y) {
        return x >= battlefieldX(battlefield) && x <= battlefieldX(battlefield) + WorldConfig.SIZE - 2
                && y >= battlefieldY(battlefield) && y <= battlefieldY(battlefield) + WorldConfig.SIZE - 2;
    }

    private static int battlefieldX(String battlefield) { return battlefield.endsWith("east") ? WorldConfig.SIZE : 0; }
    private static int battlefieldY(String battlefield) { return battlefield.startsWith("south") ? WorldConfig.SIZE : 0; }

    private record Placement(WorldMap world, String mask, PlayerCity city) {}
}
