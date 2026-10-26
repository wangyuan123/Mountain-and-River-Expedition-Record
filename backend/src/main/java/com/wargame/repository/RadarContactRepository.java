package com.wargame.repository;

import com.wargame.model.entity.RadarContact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RadarContactRepository extends JpaRepository<RadarContact, Long> {
    List<RadarContact> findByObserverIdOrderByFirstSeenAtDescIdDesc(Long observerId);
    List<RadarContact> findByObserverIdAndNoticePendingTrue(Long observerId);
    boolean existsByObserverId(Long observerId);
    List<RadarContact> findByMarchId(Long marchId);
    @org.springframework.data.jpa.repository.Query("select distinct c.observerId from RadarContact c where c.status = 'active'")
    List<Long> findActiveObserverIds();
    void deleteByObserverId(Long observerId);
}
