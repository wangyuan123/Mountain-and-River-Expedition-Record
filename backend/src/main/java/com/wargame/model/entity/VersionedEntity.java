package com.wargame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/** Prevent stale requests and settlement jobs from overwriting committed game state. */
@MappedSuperclass
public abstract class VersionedEntity {
    @Version
    @Column(nullable = false)
    private long version;

    public long getVersion() {
        return version;
    }
}
