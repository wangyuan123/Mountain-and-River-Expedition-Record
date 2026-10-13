package com.wargame.repository;
import com.wargame.model.entity.PvpPracticeSession;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PvpPracticeSessionRepository extends JpaRepository<PvpPracticeSession, String> {
    @Query("select s.id from PvpPracticeSession s where s.status in ('ACCEPTING','LOADING','COUNTDOWN','BATTLING','RECOVERING') and (s.deadlineAt <= :now or (s.status = 'ACCEPTING' and (select count(t) from PvpMatchTicket t where t.sessionId = s.id and t.leaseUntil <= :now) > 0) or (s.status = 'BATTLING' and (select count(o) from PvpRoundOrder o where o.sessionId = s.id and o.roundNo = s.roundNo and o.locked = true) = 2)) order by s.deadlineAt")
    List<String> due(@Param("now") long now, Pageable page);
}
