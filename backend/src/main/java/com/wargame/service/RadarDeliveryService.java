package com.wargame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.entity.RadarContact;
import com.wargame.repository.RadarContactRepository;
import com.wargame.repository.RadarMonitorRepository;
import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RadarDeliveryService {
    private final RadarMonitorRepository monitors;
    private final RadarContactRepository contacts;
    private final RadarService radar;
    private final GameWebSocketHandler socket;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh(Long owner) { radar.reconcile(owner); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Long owner) {
        if (!socket.isPlayerOnline(owner) || monitors.findForUpdate(owner).isEmpty()) return;
        Map<String, Object> view = radar.view(owner, 0);
        List<?> current = (List<?>) view.get("contacts");
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<RadarContact> sent = new ArrayList<>();
        for (RadarContact pending : contacts.findByObserverIdAndNoticePendingTrue(owner)) {
            current.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(item -> Objects.equals(item.get("contactId"), pending.getId())).findFirst()
                    .ifPresent(item -> { alerts.add(new LinkedHashMap<>(item)); sent.add(pending); });
            if (!sent.contains(pending)) pending.setNoticePending(false);
        }
        view.put("alerts", alerts);
        try {
            boolean delivered = socket.trySendToPlayer(owner, mapper.writeValueAsString(Map.of("type", "regionalRadar", "data", view,
                    "timestamp", System.currentTimeMillis(), "gameTime", GameClock.now())));
            if (delivered) sent.forEach(contact -> contact.setNoticePending(false));
        } catch (Exception failure) {
            throw new IllegalStateException("区域军情推送失败，稍后重试", failure);
        }
    }
}
