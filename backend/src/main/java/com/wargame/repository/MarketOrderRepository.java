package com.wargame.repository;

import com.wargame.model.entity.MarketOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketOrderRepository extends JpaRepository<MarketOrder, Long> {
    /** 先取得稳定的参与账号，按账号顺序加锁后再读取可变的订单状态和城市。 */
    @Query("select o.sellerId from MarketOrder o where o.id = :id")
    Optional<Long> findSellerId(@Param("id") Long id);

    Page<MarketOrder> findByStatusOrderByPricePerUnitAscCreatedAtDesc(String status, Pageable pageable);

    Page<MarketOrder> findByStatusAndResourceTypeOrderByPricePerUnitAscCreatedAtDesc(String status, String resourceType, Pageable pageable);

    List<MarketOrder> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    int countBySellerIdAndStatus(Long sellerId, String status);

    /** 持有卖方账号锁后执行锁定当前读，避免 MySQL 可重复读快照漏掉刚提交的跨城挂单。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o.id from MarketOrder o where o.sellerId = :sellerId and o.status = 'ACTIVE' order by o.id")
    List<Long> lockActiveOrderIds(@Param("sellerId") Long sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MarketOrder o WHERE o.id = :id")
    Optional<MarketOrder> findByIdForUpdate(@Param("id") Long id);
}
