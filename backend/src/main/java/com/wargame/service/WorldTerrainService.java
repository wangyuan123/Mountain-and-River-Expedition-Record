package com.wargame.service;

import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** One immutable, authoritative land mask per world. 0 = land, 1 = sea. */
@Service
@RequiredArgsConstructor
public class WorldTerrainService {
    public static final int SIZE = WorldConfig.SIZE;
    private final WorldMapRepository worlds;
    private final PlayerCityRepository cities;
    private final NpcCityRepository npcs;
    private final BanditRepository bandits;
    private final WildTileRepository wilds;
    private final PlayerRepository players;
    private final jakarta.persistence.EntityManager entityManager;

    public static boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < SIZE && y < SIZE; }
    public static int anchor(int n, int span) { return Math.min(n, SIZE - span); }
    public static boolean sea(String mask, int x, int y) { return inside(x,y) && mask.charAt(y*SIZE+x) == '1'; }
    public static String generate() {
        char[] mask = new char[SIZE*SIZE];
        for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
            double coast = 162 + 12*Math.sin(y*.065) + 6*Math.sin(y*.19) - 28*Math.exp(-Math.pow((y-105)/27.0,2));
            boolean water = x > coast || (x > 95 && y > 186 + 6*Math.sin(x*.07));
            if (Math.pow((x-182)/5.0,2)+Math.pow((y-78)/8.0,2)<1 || Math.pow((x-176)/4.0,2)+Math.pow((y-142)/6.0,2)<1) water=false;
            mask[y*SIZE+x] = water ? '1' : '0';
        }
        return new String(mask);
    }
    private void protect(char[] mask, int x, int y, int span) {
        x=anchor(x,span); y=anchor(y,span);
        for(int yy=y-1;yy<y+span+1;yy++) for(int xx=x-1;xx<x+span;xx++)
            if(inside(xx,yy)) mask[yy*SIZE+xx]='0';
    }
    public WorldMap lockWorld() {
        Long id=worlds.findFirstByOrderByIdAsc().map(WorldMap::getId).orElseThrow(()->new IllegalArgumentException("世界尚未初始化"));
        return worlds.lockById(id).orElseThrow();
    }
    @Transactional
    public String ensure() {
        WorldMap world=worlds.findFirstByOrderByIdAsc().orElseThrow(()->new IllegalArgumentException("世界尚未初始化"));
        if(world.getTerrainData()!=null) return world.getTerrainData();
        world=worlds.lockById(world.getId()).orElseThrow();
        if(world.getTerrainData()!=null) return world.getTerrainData();
        char[] mask=generate().toCharArray(); Long id=world.getId();
        cities.findByWorldId(id).forEach(c->protect(mask,c.getX(),c.getY(),citySpan(c)));
        npcs.findByWorldId(id).forEach(c->protect(mask,c.getX(),c.getY(),1));
        bandits.findByWorldId(id).forEach(c->protect(mask,c.getX(),c.getY(),1));
        wilds.findByWorldId(id).forEach(c->protect(mask,c.getX(),c.getY(),1));
        players.findAll().forEach(p->{if(p.getCityPosX()!=null&&p.getCityPosY()!=null)protect(mask,p.getCityPosX(),p.getCityPosY(),2);});
        // Keep only sea connected to the world border; old assets may cut off small pools.
        boolean[] visited=new boolean[mask.length]; ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int i=0;i<mask.length;i++) if(mask[i]=='1'&&(i%SIZE==0||i%SIZE==SIZE-1||i/SIZE==0||i/SIZE==SIZE-1)){visited[i]=true;q.add(i);}
        while(!q.isEmpty()){int p=q.remove();for(int n:neighbors(p))if(!visited[n]&&mask[n]=='1'){visited[n]=true;q.add(n);}}
        for(int i=0;i<mask.length;i++)if(mask[i]=='1'&&!visited[i])mask[i]='0';
        world.setTerrainData(new String(mask)); worlds.saveAndFlush(world); return world.getTerrainData();
    }
    /** Read-only callers never initialize terrain during state assembly. */
    public String current() { return worlds.findFirstByOrderByIdAsc().map(WorldMap::getTerrainData).orElse(null); }
    @Transactional
    public Map<String,Object> descriptor() {
        String data=ensure();
        return Map.of("version",1,"size",SIZE,"cells",data,"seaCells",data.chars().filter(c->c=='1').count());
    }
    public static List<Integer> neighbors(int p) {
        List<Integer> out=new ArrayList<>(4);int x=p%SIZE,y=p/SIZE;
        if(x>0)out.add(p-1);if(x<SIZE-1)out.add(p+1);if(y>0)out.add(p-SIZE);if(y<SIZE-1)out.add(p+SIZE);return out;
    }
    public static List<Integer> shores(String mask,int x,int y,int span) {
        List<Integer> out=new ArrayList<>(); x=anchor(x,span);y=anchor(y,span);
        for(int i=0;i<span;i++){
            if(sea(mask,x+i,y-1))out.add((y-1)*SIZE+x+i);
            if(sea(mask,x+i,y+span))out.add((y+span)*SIZE+x+i);
            if(sea(mask,x-1,y+i))out.add((y+i)*SIZE+x-1);
            if(sea(mask,x+span,y+i))out.add((y+i)*SIZE+x+span);
        }return out;
    }
    public static boolean coastal(String mask,int x,int y,int span) { return mask!=null&&!shores(mask,x,y,span).isEmpty(); }
    /**
     * Returns the founding terrain family for a 2x2 footprint.  The world
     * currently stores an authoritative land/sea mask; the visual terrain is
     * generated from the same coordinates, so this small deterministic
     * classifier keeps founding rules stable between requests.
     */
    public static String foundingTerrain(String mask,int x,int y) {
        if (coastal(mask,x,y,2)) return "海岸";
        int n = Math.floorMod(x * 31 + y * 17, 11);
        return n < 2 ? "丘陵" : "平原";
    }
    public int citySpan(PlayerCity c) { return c.getOwnerId()!=null&&players.existsById(c.getOwnerId())?2:1; }
    public boolean coastal(PlayerCity c) { return coastal(current(),c.getX(),c.getY(),citySpan(c)); }
    public boolean canUsePort(PlayerCity c) { return c.isLegacyNaval()||coastal(c); }
    // Locking/current reads are essential under MySQL REPEATABLE READ: authentication
    // may have opened a snapshot before this request waited for the world placement lock.
    private <T> List<T> occupants(Class<T> type,Long world,int x0,int x1,int y0,int y1) {
        return entityManager.createQuery("select t from "+type.getSimpleName()+" t where t.worldId=:w and t.x between :x0 and :x1 and t.y between :y0 and :y1",type)
                .setParameter("w",world).setParameter("x0",x0).setParameter("x1",x1).setParameter("y0",y0).setParameter("y1",y1)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList();
    }
    public Set<String> occupiedCoordinates(Long world) {
        Set<String> used=new HashSet<>();
        for(PlayerCity c:occupants(PlayerCity.class,world,0,SIZE-1,0,SIZE-1)){
            int span=citySpan(c),x=anchor(c.getX(),span),y=anchor(c.getY(),span);
            for(int yy=y;yy<y+span;yy++)for(int xx=x;xx<x+span;xx++)used.add(xx+","+yy);
        }
        occupants(NpcCity.class,world,0,SIZE-1,0,SIZE-1).forEach(c->used.add(c.getX()+","+c.getY()));
        occupants(Bandit.class,world,0,SIZE-1,0,SIZE-1).forEach(c->used.add(c.getX()+","+c.getY()));
        occupants(WildTile.class,world,0,SIZE-1,0,SIZE-1).forEach(c->used.add(c.getX()+","+c.getY()));
        return used;
    }
    public String siteReason(Long world, String mask,int x,int y,Long consumedWild,boolean requireCoast) {
        if(x<0||y<0||x+1>=SIZE||y+1>=SIZE)return "城市需要完整的 2×2 地块，不能超出地图";
        for(int yy=y;yy<y+2;yy++)for(int xx=x;xx<x+2;xx++)if(sea(mask,xx,yy))return "城市四格占地必须全部位于陆地";
        if(requireCoast&&!coastal(mask,x,y,2))return "请选择至少一侧临海的 2×2 陆地";
        for(PlayerCity c:occupants(PlayerCity.class,world,Math.max(0,x-1),x+2,Math.max(0,y-1),y+2)){
            int span=citySpan(c), cx=anchor(c.getX(),span),cy=anchor(c.getY(),span);
            if(cx<x+2&&cx+span>x&&cy<y+2&&cy+span>y)return "该范围与现有城市占地重叠";
        }
        if(!occupants(NpcCity.class,world,x,x+1,y,y+1).isEmpty()||!occupants(Bandit.class,world,x,x+1,y,y+1).isEmpty())return "该范围内有日寇据点";
        if(occupants(WildTile.class,world,x,x+1,y,y+1).stream().anyMatch(w->!Objects.equals(w.getId(),consumedWild)))return "该范围内有野地，请选择空地";
        return "";
    }
}
