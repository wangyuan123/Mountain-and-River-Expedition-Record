package com.wargame.repository;

import com.wargame.model.entity.ArmyPopulationBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArmyPopulationBalanceRepository extends JpaRepository<ArmyPopulationBalance, Long> {
    // 玩家锁后使用当前读，避免并发首次建池时复用早于锁的 MySQL 一致性快照。
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<ArmyPopulationBalance> findByPlayerIdAndUnitType(Long playerId, String unitType);
    void deleteByPlayerId(Long playerId);
}
