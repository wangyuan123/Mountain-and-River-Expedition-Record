package com.wargame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import jakarta.persistence.Transient;

/** Prevent stale requests and settlement jobs from overwriting committed game state. */
@MappedSuperclass
public abstract class VersionedEntity {
    @Version
    @Column(nullable = false)
    private long version;
    @Transient
    private String radarFingerprint;

    public String radarFingerprint() { return radarFingerprint; }
    public void radarFingerprint(String value) { radarFingerprint = value; }

    public long getVersion() {
        return version;
    }
}
