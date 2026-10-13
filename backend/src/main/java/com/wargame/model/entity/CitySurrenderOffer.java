package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A persisted diplomatic offer; the two deadlines are fixed at send/accept time. */
@Data
@lombok.EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "city_surrender_offers")
public class CitySurrenderOffer extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;
    @Column(name = "initiator_id", nullable = false)
    private Long initiatorId;
    @Column(name = "defender_id", nullable = false)
    private Long defenderId;
    @Column(name = "city_id", nullable = false)
    private Long cityId;
    @Column(name = "city_name", nullable = false, length = 100)
    private String cityName;
    @Column(name = "city_x", nullable = false)
    private Integer cityX;
    @Column(name = "city_y", nullable = false)
    private Integer cityY;
    @Column(name = "offer_mail_id")
    private Long offerMailId;
    @Column(name = "status", nullable = false, length = 16)
    private String status;
    @Column(name = "sent_at", nullable = false)
    private Long sentAt;
    @Column(name = "response_expires_at", nullable = false)
    private Long responseExpiresAt;
    @Column(name = "accepted_at")
    private Long acceptedAt;
    @Column(name = "transfer_deadline_at")
    private Long transferDeadlineAt;
    @Column(name = "completed_at")
    private Long completedAt;
    @Column(name = "cancelled_at")
    private Long cancelledAt;
    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;
}
