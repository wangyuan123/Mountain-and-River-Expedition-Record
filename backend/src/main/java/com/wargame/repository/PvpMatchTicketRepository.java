package com.wargame.repository;
import com.wargame.model.entity.PvpMatchTicket;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpMatchTicketRepository extends JpaRepository<PvpMatchTicket, String> {
    List<PvpMatchTicket> findByRegionAndStatusOrderByQueuedAtAsc(String region, String status, Pageable page);
    @Query("select t from PvpMatchTicket t where t.region = :region and t.battlefield = :field and t.rulesVersion = :rules and t.powerModelVersion = :model and t.status = 'QUEUED' and t.leaseUntil > :now and t.power between :minP and :maxP and t.rating between :minR and :maxR order by t.queuedAt")
    List<PvpMatchTicket> candidates(@Param("region") String region, @Param("field") String field,
            @Param("rules") String rules, @Param("model") String model, @Param("now") long now, @Param("minP") long minP, @Param("maxP") long maxP,
            @Param("minR") int minR, @Param("maxR") int maxR, Pageable page);
    @Query("select t.id from PvpMatchTicket t where t.status = 'QUEUED' and (t.leaseUntil <= :now or t.queuedAt <= :cutoff) order by t.leaseUntil")
    List<String> expired(@Param("now") long now, @Param("cutoff") long cutoff, Pageable page);
    @Query(value = "select s.created_at - t.queued_at from pvp_match_tickets t join pvp_practice_sessions s on s.id = t.session_id where t.region = :region and t.battlefield = :field and t.rules_version = :rules and t.status = 'MATCHED' and s.status <> 'VOID' and s.created_at >= :since order by s.created_at desc", nativeQuery = true)
    List<Long> recentWaits(@Param("region") String region, @Param("field") String field, @Param("rules") String rules,
            @Param("since") long since, Pageable page);
}
