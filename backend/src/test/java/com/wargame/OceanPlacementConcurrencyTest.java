package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.service.CityService;
import com.wargame.service.WorldTerrainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OceanPlacementConcurrencyTest extends BaseServiceTest {
    @Autowired CityService cities;
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void twoPlayersCannotPayForOverlappingCityFootprints() throws Exception {
        WorldMap w=createTestWorld();char[] mask=new char[40000];Arrays.fill(mask,'0');
        for(int y=0;y<200;y++)for(int x=100;x<200;x++)mask[y*200+x]='1';
        w.setTerrainData(new String(mask));worldMapRepository.save(w);
        List<Player> players=new ArrayList<>();
        for(int i=0;i<2;i++){
            Player p=createTestPlayer("coastal-race-"+i,30);p.setMilitaryRank(4);playerRepository.save(p);giveResources(p.getId(),100000,100000,100000,100000,100000);players.add(p);
            PlayerCity c=new PlayerCity();c.setOwnerId(p.getId());c.setCitySlot(0);c.setWorldId(w.getId());c.setX(10+i*3);c.setY(10);c.setName("主城");c.setLevel(1);playerCityRepository.save(c);
        }
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<2;i++){final int n=i;attempts.add(pool.submit(()->{start.await();try{cities.foundAt(players.get(n).getId(),null,98,50+n,"海岸"+n);return true;}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("重叠"));return false;}}));}
            start.countDown();int succeeded=0;for(var f:attempts)if(f.get(15,TimeUnit.SECONDS))succeeded++;
            assertEquals(1,succeeded);assertEquals(3,playerCityRepository.findByWorldId(w.getId()).size());
            int totalGold=players.stream().mapToInt(p->resourcesRepository.findByPlayerIdAndCitySlot(p.getId(),0).orElseThrow().getGold()).sum();assertEquals(190000,totalGold);
        } finally {pool.shutdownNow();}
    }
}
