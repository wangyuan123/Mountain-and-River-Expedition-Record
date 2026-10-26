package com.wargame.service;

import java.util.List;

public final class RadarGeometry {
    private RadarGeometry() {}

    /** 有限折线与闭圆相交；相切及重合端点有效，不检查部队当前位置。 */
    public static boolean intersects(List<double[]> route, double centerX, double centerY, double radius) {
        if (route == null || route.isEmpty() || !Double.isFinite(radius) || radius <= 0) return false;
        double squaredRadius = radius * radius;
        for (int index = 0; index < route.size(); index++) {
            double[] start = route.get(index);
            double[] end = route.get(Math.min(index + 1, route.size() - 1));
            if (start.length < 2 || end.length < 2) return false;
            double deltaX = end[0] - start[0], deltaY = end[1] - start[1];
            double squaredLength = deltaX * deltaX + deltaY * deltaY;
            double progress = squaredLength == 0 ? 0 : Math.max(0, Math.min(1,
                    ((centerX - start[0]) * deltaX + (centerY - start[1]) * deltaY) / squaredLength));
            double distanceX = start[0] + progress * deltaX - centerX;
            double distanceY = start[1] + progress * deltaY - centerY;
            if (distanceX * distanceX + distanceY * distanceY <= squaredRadius) return true;
        }
        return false;
    }
}
