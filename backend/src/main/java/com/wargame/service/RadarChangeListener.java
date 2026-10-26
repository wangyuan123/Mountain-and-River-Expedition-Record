package com.wargame.service;

import com.wargame.model.entity.*;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Objects;

@Component
public class RadarChangeListener {
    @Autowired private RadarEvents events;

    private String fingerprint(Object entity) {
        if (entity instanceof PlayerCity city) return Arrays.asList(city.getOwnerId(), city.getCitySlot(), city.getX(), city.getY(), city.getReadyAt(), city.isRadarEnabled(), city.getRadarRevision(), city.getName(), city.isAcceptGarrison()).toString();
        if (entity instanceof WildTile tile) return Arrays.asList(tile.getOccupied(), tile.getOccupiedBy()).toString();
        if (entity instanceof Building building) return Arrays.asList(building.getPlayerId(), building.getCitySlot(), building.getType(), building.getLevel()).toString();
        if (entity instanceof GuildMember member) return Arrays.asList(member.getPlayerId(), member.getGuildId()).toString();
        if (entity instanceof March march) return Arrays.asList(march.getPlayerId(), march.getTargetKind(), march.getTargetId(), march.getTargetName(), march.getAction(), march.getFromX(), march.getFromY(), march.getTargetX(), march.getTargetY(), march.getRouteData(), march.getRouteMode(), march.getStartAt(), march.getArriveAt(), march.getArmy(), march.getReturning(), march.getStationed(), march.getGathering(), march.getBattleId(), march.getSupportOwnerId()).toString();
        return null;
    }

    @PostLoad
    public void remember(Object entity) { ((VersionedEntity) entity).radarFingerprint(fingerprint(entity)); }

    @PostPersist @PostUpdate
    public void changed(Object entity) {
        if (entity instanceof Building building && !"guild_radar".equals(building.getType())) return;
        VersionedEntity versioned = (VersionedEntity) entity;
        String next = fingerprint(entity);
        if (Objects.equals(versioned.radarFingerprint(), next)) return;
        versioned.radarFingerprint(next);
        events.changed();
    }

    @PostRemove
    public void removed(Object entity) {
        if (entity instanceof Building building && !"guild_radar".equals(building.getType())) return;
        events.changed();
    }
}
