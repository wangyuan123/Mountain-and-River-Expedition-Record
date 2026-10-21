package com.wargame.repository;
import com.wargame.model.entity.MailGiftDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailGiftDeliveryRepository extends JpaRepository<MailGiftDelivery, Long> {
    boolean existsByServerIdAndActivityIdAndPlayerId(String serverId, String activityId, Long playerId);
}
