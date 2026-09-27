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

    Page<MarketOrder> findByStatusOrderByPricePerUnitAscCreatedAtDesc(String status, Pageable pageable);

    Page<MarketOrder> findByStatusAndResourceTypeOrderByPricePerUnitAscCreatedAtDesc(String status, String resourceType, Pageable pageable);

    List<MarketOrder> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    int countBySellerIdAndStatus(Long sellerId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MarketOrder o WHERE o.id = :id")
    Optional<MarketOrder> findByIdForUpdate(@Param("id") Long id);
}
