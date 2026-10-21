package com.wargame.service;

import com.wargame.model.entity.March;
import com.wargame.util.JsonUtil;
import java.util.*;
import java.util.function.Function;

/** 服务端按敌军剩余真实路线选择交火点，预览与正式派遣共用，不接受客户端指定交火位置。 */
public final class InterceptionPlanner {
    public static final long PREPARATION_MS = 5_000L;
    public static final String VERSION = "interception-ambush-v1-20261021";
    public record Point(double x, double y) {}
    public record Plan(Point point, double enemyProgress, long deployAt, long readyAt, long clashAt,
                       MarchRouteService.Route route, double distance) {
        public boolean prepared() { return readyAt <= clashAt; }
        public Map<String, Object> preview(long now) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", route.mode()); out.put("points", List.of(route.points().get(0), List.of(point.x, point.y))); out.put("distance", distance);
            out.put("seconds", Math.max(1, (deployAt - now + 999) / 1000));
            out.put("deployAt", deployAt); out.put("readyAt", readyAt); out.put("clashAt", clashAt);
            out.put("plannedAt", now);
            out.put("opening", prepared() ? "ambush" : "urgent"); out.put("preparationMs", PREPARATION_MS);
            out.put("reservedLoad", route.reservedLoad()); out.put("cargoLimit", route.cargoLimit());
            out.put("targetX", Math.round(point.x)); out.put("targetY", Math.round(point.y));
            return out;
        }
    }

    private InterceptionPlanner() {}

    /** 先寻找可提前五秒部署的点，失败再找普通迎击点；候选点永不包含城市终点。 */
    public static Plan plan(March enemy, int x, int y, double gridsPerSecond, long now,
                            Function<Point, MarchRouteService.Route> routeValidator) {
        long start = Objects.requireNonNullElse(enemy.getStartAt(), now - 30_000L);
        long arrival = Objects.requireNonNullElse(enemy.getArriveAt(), now);
        if (arrival <= now || start >= arrival || gridsPerSecond <= 0)
            throw new IllegalArgumentException("敌军即将抵城，无法完成出城拦截");
        List<Point> path = path(enemy);
        double progress = Math.max(0, Math.min(1, (double) (now - start) / (arrival - start)));
        String routeError = null;
        // 距离减去可用时间在每段直线上是凸函数；补充连续可达区间的边界，不能因采样漏掉最后几毫秒的有效迎击。
        for (long preparation : new long[]{PREPARATION_MS, 0}) {
            SortedSet<Double> candidates = new TreeSet<>();
            double end = 1 - 1.0 / (arrival - start);
            for (int i = 1; i < 256; i++) candidates.add(progress + (1 - progress) * i / 256.0);
            candidates.add(end);
            double length = 0;
            for (int i = 1; i < path.size(); i++) length += Math.hypot(path.get(i).x - path.get(i - 1).x, path.get(i).y - path.get(i - 1).y);
            double traversed = 0;
            for (int i = 1; i < path.size() && length > 0; i++) {
                double segment = Math.hypot(path.get(i).x - path.get(i - 1).x, path.get(i).y - path.get(i - 1).y);
                double lo = Math.max(progress, traversed / length), hi = Math.min(end, (traversed + segment) / length);
                traversed += segment;
                if (hi <= lo) continue;
                double left = lo, right = hi;
                for (int step = 0; step < 64; step++) {
                    double a = left + (right - left) / 3, b = right - (right - left) / 3;
                    if (gap(path, a, x, y, gridsPerSecond, start, arrival, now, preparation)
                            <= gap(path, b, x, y, gridsPerSecond, start, arrival, now, preparation)) right = b;
                    else left = a;
                }
                double minimum = (left + right) / 2;
                if (gap(path, minimum, x, y, gridsPerSecond, start, arrival, now, preparation) > 0) continue;
                left = lo; right = minimum;
                for (int step = 0; step < 64; step++) {
                    double middle = (left + right) / 2;
                    if (gap(path, middle, x, y, gridsPerSecond, start, arrival, now, preparation) <= 0) right = middle;
                    else left = middle;
                }
                candidates.add(right);
                candidates.add(Math.min(hi, right + 2.0 / (arrival - start)));
                candidates.add(minimum);
                candidates.add(hi);
            }
            for (double fraction : candidates) {
                if (fraction <= progress || fraction >= 1) continue;
                Point point = at(path, fraction);
                double distance = Math.hypot(point.x - x, point.y - y);
                if (distance < 1e-8) continue;
                long deploy = now + Math.max(1_000L, (long) Math.ceil(distance / gridsPerSecond * 1000));
                long clash = crossingAt(enemy, fraction);
                if (deploy + preparation > clash || clash >= arrival) continue;
                try {
                    var route = routeValidator.apply(point);
                    return new Plan(point, fraction, deploy, deploy + PREPARATION_MS, clash, route, distance);
                } catch (IllegalArgumentException invalidRoute) { routeError = invalidRoute.getMessage(); }
            }
        }
        throw new IllegalArgumentException(routeError != null ? routeError : "敌军即将抵城，无法完成出城拦截");
    }

    private static double gap(List<Point> path, double fraction, int x, int y, double speed,
                              long start, long arrival, long now, long preparation) {
        Point p = at(path, fraction);
        return Math.max(1_000, Math.hypot(p.x - x, p.y - y) / speed * 1000)
                + preparation - (start + (arrival - start) * fraction - now);
    }

    public static long crossingAt(March enemy, double fraction) {
        return enemy.getStartAt() + Math.round((enemy.getArriveAt() - enemy.getStartAt()) * fraction);
    }

    /** 快照只包含路线和端点；加减速可改变时间轴，改道则不能命中旧设伏位置。 */
    public static String signature(March march) { return JsonUtil.toJson(path(march)); }

    public static List<Point> path(March march) {
        List<Point> result = new ArrayList<>();
        var tree = JsonUtil.parseTree(march.getRouteData());
        if (tree != null && tree.isArray()) for (var node : tree) {
            if (node.isArray() && node.size() >= 2 && node.get(0).isNumber() && node.get(1).isNumber())
                result.add(new Point(node.get(0).asDouble(), node.get(1).asDouble()));
            else if (node.has("x") && node.has("y")) result.add(new Point(node.get("x").asDouble(), node.get("y").asDouble()));
        }
        if (result.size() < 2) return List.of(new Point(Objects.requireNonNullElse(march.getFromX(), 0), Objects.requireNonNullElse(march.getFromY(), 0)),
                new Point(Objects.requireNonNullElse(march.getTargetX(), 0), Objects.requireNonNullElse(march.getTargetY(), 0)));
        // 同一几何路线但端点改变也必须失效，不能把城市迁移视作仍可设伏。
        result.set(0, new Point(Objects.requireNonNullElse(march.getFromX(), 0), Objects.requireNonNullElse(march.getFromY(), 0)));
        result.set(result.size() - 1, new Point(Objects.requireNonNullElse(march.getTargetX(), 0), Objects.requireNonNullElse(march.getTargetY(), 0)));
        return result;
    }

    public static Point at(List<Point> path, double fraction) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += Math.hypot(path.get(i).x - path.get(i - 1).x, path.get(i).y - path.get(i - 1).y);
        double remaining = length * fraction;
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            double segment = Math.hypot(b.x - a.x, b.y - a.y);
            if (remaining <= segment && segment > 0) return new Point(a.x + (b.x - a.x) * remaining / segment, a.y + (b.y - a.y) * remaining / segment);
            remaining -= segment;
        }
        return path.get(path.size() - 1);
    }
}
