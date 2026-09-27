package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "market_orders")
public class MarketOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "seller_name", nullable = false, length = 64)
    private String sellerName;

    @Column(name = "seller_city_slot", nullable = false)
    private Integer sellerCitySlot = 0;

    @Column(name = "resource_type", nullable = false, length = 16)
    private String resourceType; // food, steel, oil, rare

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "price_per_unit", nullable = false)
    private Integer pricePerUnit;

    @Column(name = "total_price", nullable = false)
    private Long totalPrice;

    @Column(name = "tax_rate", nullable = false)
    private Double taxRate = 0.1;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE"; // ACTIVE, SOLD, CANCELLED

    @Column(name = "buyer_id")
    private Long buyerId;

    @Column(name = "buyer_name", length = 64)
    private String buyerName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
