package com.wargame.repository;
import com.wargame.model.entity.PvpPowerCalibration;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpPowerCalibrationRepository extends JpaRepository<PvpPowerCalibration, String> {
}
