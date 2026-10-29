package com.wargame.service;

import com.wargame.model.constants.UnitPopulationCosts;
import com.wargame.model.constants.GameData;
import com.wargame.model.entity.ArmyPopulationBalance;
import com.wargame.model.entity.ArmyProductionQueue;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Map;

/** 保留实扣人口成本，防止调整单价后用旧部队反复生成平民人口。 */
@Service
@Transactional
public class ArmyPopulationService {
    private final ArmyPopulationBalanceRepository balances;
    private final PlayerRepository players;
    private final ArmyUnitRepository armies;
    private final MarchRepository marches;
    private final WildTileRepository wilds;
    private final WoundedUnitRepository wounded;

    public ArmyPopulationService(ArmyPopulationBalanceRepository balances, PlayerRepository players,
                                 ArmyUnitRepository armies, MarchRepository marches,
                                 WildTileRepository wilds, WoundedUnitRepository wounded) {
        this.balances = balances;
        this.players = players;
        this.armies = armies;
        this.marches = marches;
        this.wilds = wilds;
        this.wounded = wounded;
    }

    /** 在首次新征召前登记历史库存；所有城市、在途部队、野地驻军及有效伤兵只登记一次。 */
    public ArmyPopulationBalance balance(Long playerId, String type) {
        players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        return balances.findByPlayerIdAndUnitType(playerId, type).orElseGet(() -> {
            long count = armies.findByPlayerIdAndType(playerId, type).stream()
                    .mapToLong(unit -> Math.max(0, Objects.requireNonNullElse(unit.getCount(), 0))).sum();
            count += marches.findByPlayerId(playerId).stream()
                    .mapToLong(march -> Math.max(0, JsonUtil.parseIntMap(march.getArmy()).getOrDefault(type, 0))).sum();
            // garrison_routes 是同一驻军的路线副本，不能重复计入人口成本。
            count += wilds.findByOccupiedBy(playerId).stream().filter(tile -> Boolean.TRUE.equals(tile.getOccupied()))
                    .mapToLong(tile -> Math.max(0, JsonUtil.parseIntMap(tile.getGarrison()).getOrDefault(type, 0))).sum();
            count += wounded.findByPlayerIdAndExpiresAtGreaterThanOrderByExpiresAtAscIdAsc(playerId, GameClock.now())
                    .stream().filter(batch -> type.equals(batch.getType()))
                    .mapToLong(batch -> Math.max(0, batch.getCount())).sum();
            ArmyPopulationBalance result = new ArmyPopulationBalance();
            result.setPlayerId(playerId);
            result.setUnitType(type);
            result.setReferenceCount(count);
            result.setRefundablePopulation(Math.multiplyExact(count, UnitPopulationCosts.legacy(type)));
            return balances.save(result);
        });
    }

    /** 完工前增加这批实际支付的人口；旧队列仍按旧单价入池，不补扣人口。 */
    public void complete(Long playerId, ArmyProductionQueue queue) {
        ArmyPopulationBalance balance = balance(playerId, queue.getUnitType());
        balance.setReferenceCount(Math.addExact(balance.getReferenceCount(), queue.getUnitCount()));
        balance.setRefundablePopulation(Math.addExact(balance.getRefundablePopulation(), paidPopulation(queue)));
        balances.save(balance);
    }

    /** 赠兵和战俘招募保留原有解散待遇，按发放时的单价入池，不能重估已登记的旧部队。 */
    public void grant(Long playerId, String type, int count) {
        if (count <= 0 || !GameData.UNITS.containsKey(type)) return;
        ArmyPopulationBalance balance = balance(playerId, type);
        balance.setReferenceCount(Math.addExact(balance.getReferenceCount(), count));
        balance.setRefundablePopulation(Math.addExact(balance.getRefundablePopulation(),
                Math.multiplyExact((long) GameData.UNITS.get(type).pop(), count)));
        balances.save(balance);
    }

    /**
     * 同账号同兵种按剩余累计成本加权返还，整数余数留在池内，分批解散不能多退。
     * 战损不重新分摊到幸存者；赠兵仅在实际发放时入池，调城和返城不得再次入池。
     */
    public long dismiss(Long playerId, String type, int count) {
        ArmyPopulationBalance balance = balance(playerId, type);
        return withdraw(balance, count);
    }

    /** 归降移交实际带走的兵力同时转移加权成本，双方在移交前登记库存，避免二次建池。 */
    public void transfer(Long fromPlayer, Long toPlayer, Map<String, Long> army) {
        army.forEach((type, count) -> {
            if (count <= 0) return;
            ArmyPopulationBalance source = balance(fromPlayer, type);
            ArmyPopulationBalance target = balance(toPlayer, type);
            long units = Math.min(count, source.getReferenceCount());
            long population = withdraw(source, units);
            target.setReferenceCount(Math.addExact(target.getReferenceCount(), units));
            target.setRefundablePopulation(Math.addExact(target.getRefundablePopulation(), population));
            balances.save(target);
        });
    }

    private long withdraw(ArmyPopulationBalance balance, long count) {
        long units = Math.min(Math.max(0, count), balance.getReferenceCount());
        if (units == 0) return 0;
        long population = BigInteger.valueOf(balance.getRefundablePopulation()).multiply(BigInteger.valueOf(units))
                .divide(BigInteger.valueOf(balance.getReferenceCount())).longValueExact();
        balance.setReferenceCount(balance.getReferenceCount() - units);
        balance.setRefundablePopulation(balance.getRefundablePopulation() - population);
        balances.save(balance);
        return population;
    }

    /** 无快照的历史订单使用发布前单价；新订单优先使用创建时保存的实扣总人口。 */
    public static int paidPopulation(ArmyProductionQueue queue) {
        return queue.getCostPopulation() != null ? queue.getCostPopulation()
                : Math.multiplyExact(UnitPopulationCosts.legacy(queue.getUnitType()), queue.getUnitCount());
    }
}
