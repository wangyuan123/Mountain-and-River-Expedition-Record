package com.wargame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/** Stable player-local city key. Slot zero preserves every existing main-city row. */
@MappedSuperclass
@Getter
@Setter
public abstract class CityOwnedEntity extends VersionedEntity {
    @Column(name = "city_slot", nullable = false)
    private Integer citySlot = 0;
}
