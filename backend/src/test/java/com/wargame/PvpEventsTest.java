package com.wargame;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.service.pvp.*;
import com.wargame.model.dto.pvp.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实演习事务验证推送只在提交后发送，回滚时不会留下虚假匹配提示。 */
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class PvpEventsTest extends BaseServiceTest {
    @MockBean GameWebSocketHandler sockets;
    @Autowired PvpPracticeService practice;
    @Autowired PvpSnapshotService snapshots;
    @Autowired PvpPowerModel powers;
    @Autowired PlatformTransactionManager transactions;
    @Test void queueNotificationsWaitForCommitAndRolledBackQueueIsNeverAnnounced() {
        Long player=createTestPlayer("push-"+UUID.randomUUID(),30).getId();createArmyUnit(player,"infantry",100);
        var defaults=((PvpPreset)practice.preset(player).get("preset")).defaults();
        var snap=powers.estimate(snapshots.freeze(player,new PvpPreset(0,"LAND_AIR",Map.of("infantry",100),null,defaults)));
        when(sockets.isPlayerOnline(player)).thenReturn(true);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            practice.enqueue(player,UUID.randomUUID().toString(),0,snap,Map.of("rollback",1));
            verify(sockets,never()).sendToPlayer(anyLong(),anyString());throw new IllegalStateException("rollback");
        }));
        verify(sockets,never()).sendToPlayer(anyLong(),anyString());assertEquals("IDLE",practice.status(player).get("status"));
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            practice.enqueue(player,UUID.randomUUID().toString(),0,snap,Map.of("commit",1));verify(sockets,never()).sendToPlayer(anyLong(),anyString());
        });
        verify(sockets,times(1)).sendToPlayer(eq(player),argThat(json->json.contains("pvpQueueChanged")&&json.contains("QUEUED")));
    }
}
