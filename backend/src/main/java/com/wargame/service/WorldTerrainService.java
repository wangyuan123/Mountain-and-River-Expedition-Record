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
    public static final int TERRAIN_VERSION = 7;
    private static final int[][] ISLANDS = {
            // 大型岛屿 (2座，原4座减半)
            {182, 78, 5, 7},   // 中央大洋巨岛
            {176, 142, 4, 6},  // 东南大岛

            // 中型岛屿 (4座，原7座减半)
            {193, 16, 3, 4},   // 东北大洋中型岛
            {154, 110, 3, 4},  // 中央内湾中型岛
            {188, 61, 3, 4},   // 东部大洋中型岛
            {174, 161, 4, 4},  // 东南大洋中型岛

            // 小型岛屿 (3座，原6座减半)
            {192, 34, 2, 4},   // 东北列岛小岛
            {164, 75, 2, 4},   // 中央海湾西侧小岛
            {152, 192, 3, 3},  // 南部海湾主岛

            // 微型岛礁 (1座，原3座减半)
            {172, 194, 2, 2}   // 南部港外礁
    };
    private final WorldMapRepository worlds;
    private final PlayerCityRepository cities;
    private final NpcCityRepository npcs;
    private final BanditRepository bandits;
    private final WildTileRepository wilds;
    private final PlayerRepository players;
    private final jakarta.persistence.EntityManager entityManager;

    /** 四大战场共用全局坐标；SIZE 仍是既有海陆地形存档的单象限尺寸。 */
    public static boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < WorldConfig.WORLD_SIZE && y < WorldConfig.WORLD_SIZE; }
    public static int anchor(int n, int span) { return Math.min(n, WorldConfig.WORLD_SIZE - span); }
    public static boolean sea(String mask, int x, int y) {
        if (mask == null || mask.isEmpty() || x < 0 || y < 0) return false;
        int base = (int) Math.round(Math.sqrt(mask.length()));
        if (base <= 0) return false;
        if (x < base && y < base) {
            return mask.charAt(y * base + x) == '1';
        }
        int lx = (x >= base && x < base * 2) ? (base * 2 - 1 - x) : x;
        int ly = (y >= base && y < base * 2) ? (base * 2 - 1 - y) : y;
        if (lx < 0 || ly < 0 || lx >= base || ly >= base) return false;
        return mask.charAt(ly * base + lx) == '1';
    }
    /** Generate the stable world mask with a coastal mainland and varied offshore islands. */
    public static String generate() {
        char[] mask = new char[SIZE*SIZE];
        for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
            boolean water = baseWater(x, y);
            if (island(x, y)) water = false;
            mask[y*SIZE+x] = water ? '1' : '0';
        }
        return new String(mask);
    }
    private static boolean baseWater(int x, int y) {
        // 海岸公式使用旧版 200 格参考坐标，按活动战区尺寸等比例生成。
        double px = x * 200.0 / SIZE, py = y * 200.0 / SIZE;
        double coast = 162 + 12*Math.sin(py*.065) + 6*Math.sin(py*.19) - 28*Math.exp(-Math.pow((py-105)/27.0,2));
        return px > coast || (px > 95 && py > 186 + 6*Math.sin(px*.07));
    }
    private static boolean island(int x, int y) {
        double px = x * 200.0 / SIZE;
        double py = y * 200.0 / SIZE;
        for (int[] island : ISLANDS) {
            double cx = island[0], cy = island[1];
            double rx = island[2], ry = island[3];
            // 快速包围盒初筛，加速大地图生成与判定
            if (Math.abs(px - cx) > rx * 2.2 || Math.abs(py - cy) > ry * 2.2) continue;
            double dx = (px - cx) / rx;
            double dy = (py - cy) / ry;
            // 领域扭曲 (Domain Warping): 基于坐标赋予自然起伏与不规则海岬、海湾
            double w1 = 0.20 * Math.sin(py * 0.75 + cx * 0.6) + 0.10 * Math.cos(px * 1.2 + cy * 0.4);
            double w2 = 0.20 * Math.cos(px * 0.75 + cy * 0.6) + 0.10 * Math.sin(py * 1.2 + cx * 0.4);
            double wx = dx + w1;
            double wy = dy + w2;
            double angle = Math.atan2(wy, wx);
            // 多阶谐波扰动: 结合低频不对称与中高频海岸锯齿凹凸，使边缘呈现自然不规则形态
            double edge = 1.0 + 0.18 * Math.sin(angle * 3.0 + cx * 0.5)
                    + 0.14 * Math.cos(angle * 5.0 + cy * 0.7)
                    + 0.08 * Math.sin(angle * 7.0 + (cx + cy) * 0.3)
                    + 0.05 * Math.sin(angle * 11.0 + (cx - cy) * 0.5);
            if (wx * wx + wy * wy < edge * edge) return true;
        }
        return false;
    }
    private static String mergeIslands(String terrain) {
        char[] mask = terrain.toCharArray();
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            if (mask[y * SIZE + x] == '1' && island(x, y)) mask[y * SIZE + x] = '0';
        }
        return new String(mask);
    }
    /** Deterministic offshore cells for island content; never includes mainland or protected legacy land. */
    public static List<List<Integer>> islandCells(String mask) {
        List<List<Integer>> islands = new ArrayList<>();
        for (int[] island : ISLANDS) {
            List<Integer> cells = new ArrayList<>();
            int rrx = (int) Math.ceil(island[2] * 2.2);
            int rry = (int) Math.ceil(island[3] * 2.2);
            int minY = Math.max(0, (island[1] - rry) * (SIZE / 200));
            int maxY = Math.min(SIZE - 1, (island[1] + rry) * (SIZE / 200));
            int minX = Math.max(0, (island[0] - rrx) * (SIZE / 200));
            int maxX = Math.min(SIZE - 1, (island[0] + rrx) * (SIZE / 200));
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (baseWater(x, y) && island(x, y) && !sea(mask, x, y)) cells.add(y * SIZE + x);
                }
            }
            islands.add(cells);
        }
        return islands;
    }
    private void protect(char[] mask, int x, int y, int span) {
        x=anchor(x,span); y=anchor(y,span);
        for(int yy=y-1;yy<y+span+1;yy++) for(int xx=x-1;xx<x+span;xx++)
            if(inside(xx,yy)) {
                int mx = xx < SIZE ? xx : WorldConfig.WORLD_SIZE - 1 - xx;
                int my = yy < SIZE ? yy : WorldConfig.WORLD_SIZE - 1 - yy;
                mask[my*SIZE+mx]='0';
            }
    }
    public WorldMap lockWorld() {
        Long id=worlds.findFirstByOrderByIdAsc().map(WorldMap::getId).orElseThrow(()->new IllegalArgumentException("世界尚未初始化"));
        return worlds.lockById(id).orElseThrow();
    }
    /** 统一世界展示尺寸和活动地形尺寸，兼容旧地图且保留已有坐标。 */
    @Transactional
    public String ensure() {
        WorldMap world=worlds.findFirstByOrderByIdAsc().orElseThrow(()->new IllegalArgumentException("世界尚未初始化"));
        if(world.getSize()!=null && world.getSize()==WorldConfig.WORLD_SIZE && world.getTerrainData()!=null && world.getTerrainData().length()==SIZE*SIZE && world.getTerrainVersion() != null && world.getTerrainVersion() >= TERRAIN_VERSION) return world.getTerrainData();
        world=worlds.lockById(world.getId()).orElseThrow();
        if(world.getSize()!=null && world.getSize()==WorldConfig.WORLD_SIZE && world.getTerrainData()!=null && world.getTerrainData().length()==SIZE*SIZE && world.getTerrainVersion() != null && world.getTerrainVersion() >= TERRAIN_VERSION) return world.getTerrainData();
        String stored = world.getTerrainData();
        char[] mask;
        if (stored == null) mask = generate().toCharArray();
        else if (stored.length() == SIZE * SIZE) {
            // 已扩容且版本有效的地形保持原样，不能用旧海岸规则覆盖。
            if (world.getTerrainVersion() != null && world.getTerrainVersion() >= TERRAIN_VERSION) {
                world.setSize(WorldConfig.WORLD_SIZE);
                worlds.saveAndFlush(world);
                return stored;
            }
            mask = new char[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if (baseWater(x, y)) {
                        mask[y * SIZE + x] = island(x, y) ? '0' : '1';
                    } else {
                        mask[y * SIZE + x] = stored.charAt(y * SIZE + x);
                    }
                }
            }
        } else {
            int oldSize = (int) Math.sqrt(stored.length());
            if (oldSize != 200 || oldSize * oldSize != stored.length())
                throw new IllegalArgumentException("不支持的地形尺寸");
            // 旧战区逐行保留在原坐标，新增区域补地形，不移动城市或野地。
            mask = generate().toCharArray();
            for (int y = 0; y < oldSize; y++) stored.getChars(y * oldSize, (y + 1) * oldSize, mask, y * SIZE);
        }
        Long id=world.getId();
        cities.findByWorldId(id).forEach(c->protect(mask,c.getX(),c.getY(),citySpan(c)));
        players.findAll().forEach(p->{if(p.getCityPosX()!=null&&p.getCityPosY()!=null)protect(mask,p.getCityPosX(),p.getCityPosY(),2);});
        wilds.findByWorldId(id).forEach(c->{
            if (Boolean.TRUE.equals(c.getOccupied()) || c.getOccupiedBy() != null) {
                protect(mask,c.getX(),c.getY(),1);
            }
        });
        // Keep only sea connected to the world border; old assets may cut off small pools.
        boolean[] visited=new boolean[mask.length]; ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int i=0;i<mask.length;i++) if(mask[i]=='1'&&(i%SIZE==0||i%SIZE==SIZE-1||i/SIZE==0||i/SIZE==SIZE-1)){visited[i]=true;q.add(i);}
        while(!q.isEmpty()){int p=q.remove();for(int n:neighbors(p))if(!visited[n]&&mask[n]=='1'){visited[n]=true;q.add(n);}}
        for(int i=0;i<mask.length;i++)if(mask[i]=='1'&&!visited[i])mask[i]='0';
        world.setSize(WorldConfig.WORLD_SIZE); world.setTerrainData(new String(mask)); world.setTerrainVersion(TERRAIN_VERSION); worlds.saveAndFlush(world); return world.getTerrainData();
    }
    /** Read-only callers never initialize terrain during state assembly. */
    public String current() { return worlds.findFirstByOrderByIdAsc().map(WorldMap::getTerrainData).orElse(null); }
    @Transactional
    public Map<String,Object> descriptor() {
        String data=ensure();
        int size = data != null && !data.isEmpty() ? (int) Math.round(Math.sqrt(data.length())) : SIZE;
        return Map.of("version",TERRAIN_VERSION,"size",size,"cells",data,"seaCells",data.chars().filter(c->c=='1').count());
    }
    public static List<Integer> neighbors(int p) {
        return neighbors(p,SIZE);
    }
    /** 邻接格使用当前索引宽度，防止跨战场坐标被编码为下一行。 */
    public static List<Integer> neighbors(int p,int size) {
        List<Integer> out=new ArrayList<>(4);int x=p%size,y=p/size;
        if(x>0)out.add(p-1);if(x<size-1)out.add(p+1);if(y>0)out.add(p-size);if(y<size-1)out.add(p+size);return out;
    }
    public static List<Integer> shores(String mask,int x,int y,int span) {
        int size = (int) Math.sqrt(mask.length());
        List<Integer> out=new ArrayList<>(); x=Math.min(x,size-span);y=Math.min(y,size-span);
        for(int i=0;i<span;i++){
            if(x+i>=0&&x+i<size&&y>0&&sea(mask,x+i,y-1))out.add((y-1)*size+x+i);
            if(x+i>=0&&x+i<size&&y+span<size&&sea(mask,x+i,y+span))out.add((y+span)*size+x+i);
            if(y+i>=0&&y+i<size&&x>0&&sea(mask,x-1,y+i))out.add((y+i)*size+x-1);
            if(y+i>=0&&y+i<size&&x+span<size&&sea(mask,x+span,y+i))out.add((y+i)*size+x+span);
        }return out;
    }
    /** 沿海判定直接采样镜像坐标，不为每个城市复制整张地形数组。 */
    public static boolean coastal(String mask,int x,int y,int span) {
        if(mask==null)return false;
        x=anchor(x,span);y=anchor(y,span);
        for(int i=0;i<span;i++) {
            if(inside(x+i,y-1)&&sea(mask,x+i,y-1) || inside(x+i,y+span)&&sea(mask,x+i,y+span)
                    || inside(x-1,y+i)&&sea(mask,x-1,y+i) || inside(x+span,y+i)&&sea(mask,x+span,y+i))return true;
        }
        return false;
    }

    /** 寻路使用完整四战场索引；存档仍保留原地形，镜像扩展不迁移既有坐标。 */
    public static String playableMask(String mask) {
        if(mask.length()==WorldConfig.WORLD_SIZE*WorldConfig.WORLD_SIZE)return mask;
        char[] expanded=new char[WorldConfig.WORLD_SIZE*WorldConfig.WORLD_SIZE];
        for(int y=0;y<WorldConfig.WORLD_SIZE;y++)for(int x=0;x<WorldConfig.WORLD_SIZE;x++)
            expanded[y*WorldConfig.WORLD_SIZE+x]=sea(mask,x,y)?'1':'0';
        return new String(expanded);
    }
    /** 判定指定坐标是否属于海洋中的岛屿陆地。 */
    public static boolean isIsland(String mask, int x, int y) {
        if (mask == null || sea(mask, x, y)) return false;
        int base = (int) Math.round(Math.sqrt(mask.length()));
        if (base <= 0) return false;
        int lx = (x >= base && x < base * 2) ? (base * 2 - 1 - x) : x;
        int ly = (y >= base && y < base * 2) ? (base * 2 - 1 - y) : y;
        if (lx < 0 || ly < 0 || lx >= base || ly >= base) return false;
        return baseWater(lx, ly);
    }

    public static final List<String> ISLAND_RESOURCE_TYPES = List.of("oil", "ironworks", "grainfield", "rarefactory");

    /** 适配海岛野地类型生成：支持深林（forest）野地与各类资源野地。 */
    public static String islandWildType(int x, int y) {
        int seed = Math.floorMod(x * 31 + y * 17, 5);
        if (seed == 0) return "forest";
        return ISLAND_RESOURCE_TYPES.get((seed - 1) % ISLAND_RESOURCE_TYPES.size());
    }

    /**
     * Returns the founding terrain family for a 2x2 footprint.  The world
     * currently stores an authoritative land/sea mask; the visual terrain is
     * generated from the same coordinates, so this small deterministic
     * classifier keeps founding rules stable between requests.
     */
    public static String foundingTerrain(String mask,int x,int y) {
        if (isIsland(mask, x, y)) return "海岛";
        if (coastal(mask,x,y,2)) return "海岸";
        int n = Math.floorMod(x * 31 + y * 17, 11);
        return n < 2 ? "丘陵" : "平原";
    }
    public int citySpan(PlayerCity c) { return c.getOwnerId()!=null&&players.existsById(c.getOwnerId())?2:1; }
    public boolean coastal(PlayerCity c) {
        String mask = current();
        return isIsland(mask, c.getX(), c.getY()) || coastal(mask, c.getX(), c.getY(), citySpan(c));
    }
    public boolean canUsePort(PlayerCity c) { return c.isLegacyNaval()||coastal(c); }
    // Locking/current reads are essential under MySQL REPEATABLE READ: authentication
    // may have opened a snapshot before this request waited for the world placement lock.
    private <T> List<T> occupants(Class<T> type,Long world,int x0,int x1,int y0,int y1) {
        return entityManager.createQuery("select t from "+type.getSimpleName()+" t where t.worldId=:w and t.x between :x0 and :x1 and t.y between :y0 and :y1",type)
                .setParameter("w",world).setParameter("x0",x0).setParameter("x1",x1).setParameter("y0",y0).setParameter("y1",y1)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList();
    }
    /** 目标完整占地必须处于开放边界内、同类地形且无占用。 */
    public static boolean vacantFootprint(String mask, int size, boolean water, Set<String> used, int x, int y, int span) {
        if (x < 0 || y < 0 || x + span > size || y + span > size) return false;
        for (int dy = 0; dy < span; dy++) for (int dx = 0; dx < span; dx++)
            if (used.contains((x + dx) + "," + (y + dy)) || sea(mask, x + dx, y + dy) != water) return false;
        return true;
    }

    /** 预留全部格子，防止同批新增或迁移目标重叠。 */
    public static void reserveFootprint(Set<String> used, int x, int y, int span) {
        for (int dy = 0; dy < span; dy++) for (int dx = 0; dx < span; dx++)
            used.add((x + dx) + "," + (y + dy));
    }

    public Set<String> occupiedCoordinates(Long world) {
        Set<String> used=new HashSet<>();
        for(PlayerCity c:occupants(PlayerCity.class,world,0,WorldConfig.WORLD_SIZE-1,0,WorldConfig.WORLD_SIZE-1)){
            int span=citySpan(c),x=anchor(c.getX(),span),y=anchor(c.getY(),span);
            for(int yy=y;yy<y+span;yy++)for(int xx=x;xx<x+span;xx++)used.add(xx+","+yy);
        }
        occupants(NpcCity.class,world,0,WorldConfig.WORLD_SIZE-1,0,WorldConfig.WORLD_SIZE-1).forEach(c->reserveFootprint(used,c.getX(),c.getY(),1));
        occupants(Bandit.class,world,0,WorldConfig.WORLD_SIZE-1,0,WorldConfig.WORLD_SIZE-1).forEach(c->reserveFootprint(used,c.getX(),c.getY(),1));
        occupants(WildTile.class,world,0,WorldConfig.WORLD_SIZE-1,0,WorldConfig.WORLD_SIZE-1).forEach(c->used.add(c.getX()+","+c.getY()));
        return used;
    }
    /**
     * 校验新城的四格陆地及占用规则，内陆和沿海均可建城。
     * consumedWild 是建城时可替换的已占领野地 ID；普通空地传 null。
     * 返回空字符串表示选址通过，否则返回具体原因。
     */
    public String siteReason(Long world, String mask,int x,int y,Long consumedWild) {
        if(x<0||y<0||x>=WorldConfig.WORLD_SIZE-1||y>=WorldConfig.WORLD_SIZE-1)return "城市需要完整的 2×2 地块，不能超出地图";
        for(int yy=y;yy<y+2;yy++)for(int xx=x;xx<x+2;xx++)if(sea(mask,xx,yy))return "城市四格占地必须全部位于陆地";
        for(PlayerCity c:occupants(PlayerCity.class,world,Math.max(0,x-1),x+2,Math.max(0,y-1),y+2)){
            int span=citySpan(c), cx=anchor(c.getX(),span),cy=anchor(c.getY(),span);
            if(cx<x+2&&cx+span>x&&cy<y+2&&cy+span>y)return "该范围与现有城市占地重叠";
        }
        if(!occupants(NpcCity.class,world,x,x+1,y,y+1).isEmpty()||!occupants(Bandit.class,world,x,x+1,y,y+1).isEmpty())return "该范围内有日寇据点";
        if(occupants(WildTile.class,world,x,x+1,y,y+1).stream().anyMatch(w->!Objects.equals(w.getId(),consumedWild)))return "该范围内有野地，请选择空地";
        return "";
    }
}
