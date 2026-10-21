package com.wargame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.WorldMapRepository;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 按登录账号保存固定坐标；名称取收藏时的公开目标信息，不追踪目标迁移。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MapFavoriteService {
    public static final int MAX_FAVORITES = 100;
    private final PlayerRepository players;
    private final WorldMapRepository worlds;
    private final WorldMapService maps;
    private final jakarta.persistence.EntityManager entityManager;

    public record Favorite(int x, int y, String name) {}
    public record SaveRequest(Integer x, Integer y, String kind, Long targetId) {}

    public List<Favorite> list(Long viewer) {
        return read(players.findById(viewer).orElseThrow(() -> new IllegalArgumentException("玩家不存在")));
    }

    /** 同账号写入串行化，同一坐标重复收藏幂等，列表按最近收藏优先展示。 */
    @Transactional
    public List<Favorite> add(Long viewer, SaveRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择需要收藏的坐标");
        validateCoordinate(request.x(), request.y());
        Player player = locked(viewer);
        List<Favorite> favorites = read(player);
        if (favorites.stream().anyMatch(f -> f.x() == request.x() && f.y() == request.y())) return favorites;
        if (favorites.size() >= MAX_FAVORITES) throw new IllegalArgumentException("最多收藏100个坐标，请先移除不需要的收藏");
        String label = "坐标标记";
        if (!"site".equals(request.kind())) {
            if (request.kind() == null || request.targetId() == null) throw new IllegalArgumentException("请选择有效的地图目标");
            Map<String, Object> target = maps.target(viewer, request.kind(), request.targetId());
            // 收藏固定位置，不能把已经搬迁的城市名称写到旧坐标上。
            if (!request.x().equals(target.get("x")) || !request.y().equals(target.get("y"))) {
                throw new IllegalArgumentException("目标位置已变化，请刷新地图后重新收藏");
            }
            label = String.valueOf(target.getOrDefault("name", "地图目标"));
            if ("player".equals(request.kind())) {
                label = String.valueOf(target.getOrDefault("ownerName", label));
            } else if ("wild".equals(request.kind())) {
                WildTypeDef type = WildTypeDef.WILD_TYPES.get(String.valueOf(target.get("type")));
                if (type != null) label = type.name();
            }
        }
        favorites.add(0, new Favorite(request.x(), request.y(), label));
        player.setMapFavorites(JsonUtil.toJson(favorites));
        players.save(player);
        return favorites;
    }

    /** 只操作当前账号；目标消失后仍能通过原坐标移除收藏。 */
    @Transactional
    public List<Favorite> remove(Long viewer, int x, int y) {
        Player player = locked(viewer);
        List<Favorite> favorites = read(player);
        if (favorites.removeIf(f -> f.x() == x && f.y() == y)) {
            player.setMapFavorites(JsonUtil.toJson(favorites));
            players.save(player);
        }
        return favorites;
    }

    private Player locked(Long viewer) {
        Player player = players.findById(viewer).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        // 鉴权可能已加载旧实体，持锁后刷新，避免并发请求丢失其他设备刚写入的收藏。
        entityManager.flush();
        entityManager.refresh(player, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return player;
    }

    private void validateCoordinate(Integer x, Integer y) {
        int size = worlds.findFirstByOrderByIdAsc().map(w -> w.getSize() == null ? WorldConfig.WORLD_SIZE : w.getSize())
                .orElse(WorldConfig.WORLD_SIZE);
        if (x == null || y == null || x < 0 || y < 0 || x >= size || y >= size) {
            throw new IllegalArgumentException("收藏坐标超出地图范围");
        }
    }

    private List<Favorite> read(Player player) {
        if (player.getMapFavorites() == null || player.getMapFavorites().isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(JsonUtil.getMapper().readerForListOf(Favorite.class).<List<Favorite>>readValue(player.getMapFavorites()));
        } catch (JsonProcessingException e) {
            // 读取异常时保留原数据，避免下一次操作把已有收藏覆盖为空。
            throw new IllegalStateException("收藏读取失败，请稍后重试", e);
        }
    }
}
