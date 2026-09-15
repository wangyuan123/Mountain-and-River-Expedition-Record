package com.wargame.service;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Explicit city context, pinned per request/tick, including independent defender contexts. */
@Component
public class CityScope {
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final ResourcesRepository resources;
    private final ThreadLocal<Map<Long, Integer>> slots = ThreadLocal.withInitial(HashMap::new);

    public CityScope(PlayerRepository players, PlayerCityRepository cities, ResourcesRepository resources) {
        this.players = players; this.cities = cities; this.resources = resources;
    }

    public int slot(Long playerId) {
        Integer pinned = slots.get().get(playerId);
        if (pinned != null) return pinned;
        return players.findById(playerId).map(Player::getActiveCityId)
                .flatMap(cities::findById).filter(c -> playerId.equals(c.getOwnerId()))
                .map(c -> Objects.requireNonNullElse(c.getCitySlot(), 0)).orElse(0);
    }

    public Scope enter(Long playerId, int slot) {
        Integer previous = slots.get().put(playerId, slot);
        return () -> {
            if (previous == null) slots.get().remove(playerId); else slots.get().put(playerId, previous);
            if (slots.get().isEmpty()) slots.remove();
        };
    }

    public Scope enter(PlayerCity city) { return enter(city.getOwnerId(), Objects.requireNonNullElse(city.getCitySlot(), 0)); }
    public interface Scope extends AutoCloseable { @Override void close(); }

    public PlayerCity requireOwned(Long playerId, Long cityId, boolean ready) {
        PlayerCity city = cities.findById(cityId).filter(c -> playerId.equals(c.getOwnerId()) && c.getCitySlot() != null)
                .orElseThrow(() -> new IllegalArgumentException("城市不存在或不属于你"));
        if (ready && city.getReadyAt() > System.currentTimeMillis()) throw new IllegalArgumentException("城市仍在建设中");
        return city;
    }

    public Optional<PlayerCity> selected(Long playerId) {
        return cities.findByOwnerIdAndCitySlot(playerId, slot(playerId));
    }

    public CityEconomy economy(Long playerId) {
        int slot = slot(playerId);
        if (slot == 0) return players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        return cities.findByOwnerIdAndCitySlot(playerId, slot).orElseThrow(() -> new IllegalArgumentException("城市不存在"));
    }

    public void saveEconomy(Long playerId) {
        CityEconomy economy = economy(playerId);
        if (economy instanceof Player player) players.save(player);
        else cities.save((PlayerCity) economy);
    }

    /** Diamonds remain in the existing main-city wallet and are shared across all cities. */
    public Resources wallet(Long playerId) {
        return resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow(() -> new IllegalArgumentException("玩家资产不存在"));
    }
}
