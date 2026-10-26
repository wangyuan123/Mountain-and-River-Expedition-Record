package com.wargame;

import com.wargame.service.RadarGeometry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RadarGeometryTest {
    @Test void finiteSegmentsTangentsEndpointsAndDegeneratePoints() {
        assertTrue(RadarGeometry.intersects(List.of(new double[]{-20, 0}, new double[]{20, 0}), 0, 0, 10));
        assertTrue(RadarGeometry.intersects(List.of(new double[]{-20, 10}, new double[]{20, 10}), 0, 0, 10));
        assertTrue(RadarGeometry.intersects(List.of(new double[]{10, 0}), 0, 0, 10));
        assertFalse(RadarGeometry.intersects(List.of(new double[]{11, 0}, new double[]{20, 0}), 0, 0, 10));
        assertFalse(RadarGeometry.intersects(List.of(new double[]{-20, 10.00001}, new double[]{20, 10.00001}), 0, 0, 10));
        assertFalse(RadarGeometry.intersects(List.of(new double[]{11, 0}, new double[]{11, 0}), 0, 0, 10));
    }

    @Test void polylineAndCoordinatesHaveNoWorldEdgeWrapping() {
        assertTrue(RadarGeometry.intersects(List.of(new double[]{-20, 20}, new double[]{0, 0}, new double[]{20, 20}), 0, 0, 10));
        assertFalse(RadarGeometry.intersects(List.of(new double[]{-20, 0}, new double[]{-20, 20}, new double[]{20, 20}, new double[]{20, 0}), 0, 0, 10));
        assertFalse(RadarGeometry.intersects(List.of(new double[]{799, 0}, new double[]{799, 10}), 0, 0, 10));
    }
}
