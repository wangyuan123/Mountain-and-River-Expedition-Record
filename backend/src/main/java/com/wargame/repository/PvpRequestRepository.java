package com.wargame.repository;
import com.wargame.model.entity.PvpRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpRequestRepository extends JpaRepository<PvpRequest, String> {
    Optional<PvpRequest> findByPlayerIdAndRequestId(Long player, String request);
}
