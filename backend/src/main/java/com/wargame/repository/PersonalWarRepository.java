package com.wargame.repository;

import com.wargame.model.entity.PersonalWar;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PersonalWarRepository extends JpaRepository<PersonalWar, Long> {
    Optional<PersonalWar> findByPlayerLowIdAndPlayerHighId(Long low, Long high);
    void deleteByPlayerLowIdOrPlayerHighId(Long low, Long high);
    void deleteByTargetCityId(Long cityId);
    boolean existsByPlayerLowIdAndWarEndAtGreaterThanOrPlayerHighIdAndWarEndAtGreaterThan(Long low, long from, Long high, long after);
}
