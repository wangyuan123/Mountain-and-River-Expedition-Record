package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.wargame.service.WorldTerrainService.*;

@Service
@RequiredArgsConstructor
public class MarchRouteService {
    private final WorldTerrainService terrain;
    private final CityScope scope;
    private final com.wargame.repository.BuildingRepository buildings;
    public record Route(String mode, List<List<Integer>> points, int distance, int reservedLoad, int cargoLimit) {}

    @Transactional
    public Route plan(Long playerId,Object target,Map<String,Integer> army,boolean transfer) {
        String mask=terrain.ensure();
        CityEconomy economy=scope.economy(playerId);
        int sx=economy.getCityPosX(),sy=economy.getCityPosY();
        int tx,ty,span=1;
        if(target instanceof PlayerCity c){tx=c.getX();ty=c.getY();span=terrain.citySpan(c);}
        else if(target instanceof NpcCity c){tx=c.getX();ty=c.getY();}
        else if(target instanceof Bandit c){tx=c.getX();ty=c.getY();}
        else if(target instanceof WildTile c){tx=c.getX();ty=c.getY();}
        else throw new IllegalArgumentException("无效行军目标");
        if(!inside(sx,sy)||!inside(tx,ty))throw new IllegalArgumentException("出征坐标超出地图");
        boolean navy=false,land=false;long population=0,capacity=(long)army.getOrDefault("transport",0)*80;
        for(var entry:army.entrySet()){
            var u=GameData.UNITS.get(entry.getKey());if(u==null||entry.getValue()<=0)continue;
            if("sea".equals(u.branch()))navy=true;
            if("land".equals(u.branch())){land=true;population+=(long)u.pop()*entry.getValue();}
        }
        int source=sy*SIZE+sx,dest=ty*SIZE+tx;
        if(navy){
            var city=scope.selected(playerId);
            List<Integer> starts=shores(mask,sx,sy,2),ends=shores(mask,tx,ty,span);
            boolean legacy=false;
            if(city.isPresent()&&!city.get().isLegacyNaval()&&!hasPort(playerId,scope.slot(playerId)))throw new IllegalArgumentException("请先在出发城市建设港口");
            if(transfer&&target instanceof PlayerCity c&&!c.isLegacyNaval()&&!hasPort(c.getOwnerId(),c.getCitySlot()))throw new IllegalArgumentException("目标城市需要先建设港口才能接收舰队");
            if(starts.isEmpty()&&city.map(PlayerCity::isLegacyNaval).orElse(false)){starts=List.of(nearestSea(mask,sx,sy));legacy=true;}
            if(ends.isEmpty()&&transfer&&target instanceof PlayerCity c&&c.isLegacyNaval()){ends=List.of(nearestSea(mask,tx,ty));legacy=true;}
            if(starts.isEmpty())throw new IllegalArgumentException("舰队需要从沿海城市出发，请先调遣至港口城市");
            if(ends.isEmpty())throw new IllegalArgumentException("舰队只能前往临海目标，内陆目标请使用陆军或空军");
            requireLift(population,capacity);
            List<Integer> path=path(mask,starts,ends,true);
            if(path.isEmpty())throw new IllegalArgumentException("两地之间没有连通海域");
            path.add(0,source);path.add(dest);
            return route(legacy?"sea_supply":"sea",path,(int)population,(int)Math.min(Integer.MAX_VALUE,capacity-population));
        }
        if(land){
            List<Integer> path=path(mask,List.of(source),List.of(dest),false);
            if(!path.isEmpty())return route("land",path,0,Integer.MAX_VALUE);
            requireLift(population,capacity);
            return route("airlift",List.of(source,dest),(int)population,(int)Math.min(Integer.MAX_VALUE,capacity-population));
        }
        return route("air",List.of(source,dest),0,Integer.MAX_VALUE);
    }
    private boolean hasPort(Long owner,Integer slot){return buildings.findByPlayerIdAndCitySlotAndType(owner,slot,"port").stream().anyMatch(b->b.getLevel()!=null&&b.getLevel()>0);}
    private static void requireLift(long population,long capacity){
        if(population>capacity)throw new IllegalArgumentException("跨海陆军需要运输机：所需运力 "+population+"，当前 "+capacity+"（每架80）");
    }
    private static int nearestSea(String mask,int x,int y){
        int found=-1,best=Integer.MAX_VALUE;
        for(int i=0;i<mask.length();i++)if(mask.charAt(i)=='1'){
            int d=Math.abs(i%SIZE-x)+Math.abs(i/SIZE-y);if(d<best){best=d;found=i;}
        }
        if(found<0)throw new IllegalArgumentException("当前地图没有可通航海域");return found;
    }
    public static List<Integer> path(String mask,List<Integer> starts,List<Integer> ends,boolean water){
        int[] parent=new int[SIZE*SIZE];Arrays.fill(parent,-2);boolean[] goal=new boolean[parent.length];
        ends.forEach(n->goal[n]=true);ArrayDeque<Integer> queue=new ArrayDeque<>();
        for(int n:starts){parent[n]=-1;queue.add(n);}
        int found=-1;
        while(!queue.isEmpty()){
            int p=queue.remove();if(goal[p]){found=p;break;}
            for(int n:neighbors(p))if(parent[n]==-2&&(mask.charAt(n)=='1')==water){parent[n]=p;queue.add(n);}
        }
        List<Integer> result=new ArrayList<>();if(found<0)return result;
        for(int p=found;p!=-1;p=parent[p])result.add(p);Collections.reverse(result);return result;
    }
    private static Route route(String mode,List<Integer> path,int reserved,int cargo){
        List<List<Integer>> points=new ArrayList<>();int distance=0;
        for(int i=0;i<path.size();i++){
            int p=path.get(i),x=p%SIZE,y=p/SIZE;
            if(i>0){int prev=path.get(i-1);distance+=Math.abs(x-prev%SIZE)+Math.abs(y-prev/SIZE);}
            // Keep endpoints and turns only; one tile at every turn preserves the authoritative route.
            if(i>0&&i<path.size()-1){int a=path.get(i-1),b=path.get(i+1);if((a%SIZE==x&&b%SIZE==x)||(a/SIZE==y&&b/SIZE==y))continue;}
            points.add(List.of(x,y));
        }
        return new Route(mode,points,distance,reserved,cargo);
    }
}
