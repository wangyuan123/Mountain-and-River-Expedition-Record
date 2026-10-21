package com.wargame.repository;
import com.wargame.model.entity.MailGiftActivity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface MailGiftActivityRepository extends JpaRepository<MailGiftActivity, String> {
    // 已发放和不具资格的活动先在数据库排除，避免前一百条挡住后续有效活动。
    @Query("select a.id from MailGiftActivity a where a.serverId=:server and a.closed=false and a.startsAt<=:now and a.endsAt>:now and (a.newAccountsOnly=false or (a.startsAt<=:registeredAt and a.endsAt>:registeredAt)) and not exists(select d.id from MailGiftDelivery d where d.serverId=:server and d.activityId=a.id and d.playerId=:player) order by a.startsAt,a.id")
    List<String> eligibleIds(@Param("server") String server, @Param("player") Long player, @Param("registeredAt") long registeredAt, @Param("now") long now, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MailGiftActivity a where a.id=:id")
    Optional<MailGiftActivity> lock(@Param("id") String id);
}
