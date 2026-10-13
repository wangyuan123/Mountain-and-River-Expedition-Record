package com.wargame;

import com.wargame.service.pvp.*;
import com.wargame.model.dto.pvp.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 指定合成规模的本地H2基准，记录查询数和时延，不能外推生产MySQL容量。 */
@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true","logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class PvpPerformanceTest extends BaseServiceTest {
    @Autowired PvpPracticeService practice;
    @Autowired PvpSnapshotService snapshots;
    @Autowired PvpPowerModel powers;
    @Autowired PvpMatchService matcher;
    @Autowired PvpMatchTicketRepository tickets;
    @Autowired PvpPracticeSessionRepository sessions;
    @Autowired PvpRoundResultRepository rounds;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    private String id(){return UUID.randomUUID().toString();}
    private double percentile(List<Double> values,double p){var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get(Math.min(sorted.size()-1,(int)Math.floor(p*sorted.size())));}
    private Map<String,Double> distribution(List<Double> values){return Map.of("p50",percentile(values,.5),"p95",percentile(values,.95),"max",Collections.max(values));}

    @Test void hundredTicketsEightWritersAndTwoSchedulersHaveBoundedWorkAndExactlyOnceRounds() throws Exception {
        Map<Long,PvpSnapshot> frozen=new LinkedHashMap<>();
        for(int i=0;i<100;i++){
            Long player=createTestPlayer("bench-"+id(),30).getId();createArmyUnit(player,"infantry",100+i%10);
            var preset=(PvpPreset)practice.preset(player).get("preset");
            frozen.put(player,powers.estimate(snapshots.freeze(player,new PvpPreset(0,"LAND_AIR",Map.of("infantry",100+i%10),null,preset.defaults()))));
        }
        var pool=Executors.newFixedThreadPool(8);List<Future<?>> pending=new ArrayList<>();List<Double> enqueueMs=Collections.synchronizedList(new ArrayList<>());
        var stats=entityManagerFactory.unwrap(SessionFactory.class).getStatistics();long statements=stats.getPrepareStatementCount();
        try{
            for(var e:frozen.entrySet())pending.add(pool.submit(()->{long start=System.nanoTime();practice.enqueue(e.getKey(),id(),0,e.getValue(),Map.of("queue",e.getKey()));enqueueMs.add((System.nanoTime()-start)/1e6);}));
            for(var f:pending)f.get(30,TimeUnit.SECONDS);
            long start=System.nanoTime(),beforeQueries=stats.getPrepareStatementCount();int count=0,scans=0;
            while(count<50&&scans<4){count+=matcher.match();scans++;}
            double matchMs=(System.nanoTime()-start)/1e6;long matchQueries=stats.getPrepareStatementCount()-beforeQueries;
            assertEquals(50,count);
            var matched=sessions.findAll().stream().filter(s->frozen.containsKey(s.getPlayerA())).toList();assertEquals(50,matched.size());
            List<Double> waits=new ArrayList<>(),powerGaps=new ArrayList<>(),ratingGaps=new ArrayList<>(),deadlines=new ArrayList<>();int voids=0;
            for(var s:matched){
                var a=tickets.findById(s.getTicketA()).orElseThrow();var b=tickets.findById(s.getTicketB()).orElseThrow();
                waits.add((double)(s.getCreatedAt()-a.getQueuedAt()));waits.add((double)(s.getCreatedAt()-b.getQueuedAt()));
                powerGaps.add((double)Math.max(a.getPower(),b.getPower())/Math.min(a.getPower(),b.getPower())-1);ratingGaps.add((double)Math.abs(a.getRating()-b.getRating()));
                practice.accept(s.getPlayerA(),id(),s.getTicketA(),s.getId(),Map.of("a",s.getId()));practice.accept(s.getPlayerB(),id(),s.getTicketB(),s.getId(),Map.of("b",s.getId()));
                practice.loaded(s.getPlayerA(),id(),s.getId(),Map.of("aLoaded",s.getId()));practice.loaded(s.getPlayerB(),id(),s.getId(),Map.of("bLoaded",s.getId()));
            }
            // 批量将时间轴置于刚到期，两个调度写者并发处理相同会话，测量本批的回合截止延迟。
            long due=System.currentTimeMillis()-1;
            new TransactionTemplate(transactions).executeWithoutResult(tx->{for(var s:matched){var current=sessions.findById(s.getId()).orElseThrow();current.setStartsAt(due-15000);current.setDeadlineAt(due-15000);sessions.save(current);}});
            pending.clear();
            for(var s:matched)for(int worker=0;worker<2;worker++)pending.add(pool.submit(()->practice.advance(s.getId())));
            for(var f:pending)f.get(30,TimeUnit.SECONDS);
            for(var s:matched){
                var current=sessions.findById(s.getId()).orElseThrow();var result=rounds.findBySessionIdOrderByRoundNoAsc(s.getId());assertEquals(1,result.size());assertEquals(2,current.getRoundNo());
                Map<String,Object> saved=PvpJson.read(result.get(0).getData(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                deadlines.add(((Number)saved.get("resolvedAt")).doubleValue()-due);if("VOID".equals(current.getStatus()))voids++;
            }
            var evidence=new LinkedHashMap<String,Object>();evidence.put("environment","local H2 MySQL mode, no world ticks");evidence.put("tickets",100);evidence.put("enqueueWriters",8);evidence.put("schedulerWorkers",8);evidence.put("duplicateSchedulingPerSession",2);
            evidence.put("enqueueMs",distribution(enqueueMs));evidence.put("matchingMs",matchMs);evidence.put("matchingScans",scans);evidence.put("matchingSqlStatements",matchQueries);evidence.put("waitMs",distribution(waits));evidence.put("powerGap",distribution(powerGaps));evidence.put("ratingGap",distribution(ratingGaps));
            evidence.put("deadlineResolutionDelayMs",distribution(deadlines));evidence.put("deadlineBatchDelayMs",System.currentTimeMillis()-due);evidence.put("voidRate",voids/50.0);evidence.put("sqlStatementsTotal",stats.getPrepareStatementCount()-statements);evidence.put("limits","Synthetic simultaneous arrivals; round timelines shifted for recovery batch; no production population, throughput, or waiting-time guarantee");
            Path output=Path.of("../output/pvp-validation-20261010/concurrency-benchmark.json");Files.createDirectories(output.getParent());Files.writeString(output,PvpJson.write(evidence));
        }finally{pool.shutdownNow();}
    }
}
