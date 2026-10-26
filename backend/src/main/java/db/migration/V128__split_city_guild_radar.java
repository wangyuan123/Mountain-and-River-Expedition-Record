package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.ArrayList;

public class V128__split_city_guild_radar extends BaseJavaMigration {
    private record CityRadar(long playerId, int citySlot, int level) {}

    /** 仅按当前城主的已完成雷达补发联防建筑；满额旧城兼容保留，不改动开关、施工与军情历史。 */
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var cities = new ArrayList<CityRadar>();
        try (var query = connection.prepareStatement("""
                SELECT radar.player_id, radar.city_slot, LEAST(10, MAX(radar.level)) AS radar_level
                FROM buildings radar
                JOIN player_cities city ON city.owner_id = radar.player_id AND city.city_slot = radar.city_slot
                WHERE radar.type = 'radar' AND radar.level > 0
                  AND NOT EXISTS (
                    SELECT 1 FROM buildings existing
                    WHERE existing.player_id = radar.player_id AND existing.city_slot = radar.city_slot
                      AND existing.type = 'guild_radar'
                  )
                GROUP BY radar.player_id, radar.city_slot
                """); var rows = query.executeQuery()) {
            while (rows.next()) {
                cities.add(new CityRadar(rows.getLong("player_id"), rows.getInt("city_slot"), rows.getInt("radar_level")));
            }
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO buildings (player_id, city_slot, type, level, slot, version)
                VALUES (?, ?, 'guild_radar', ?, 0, 0)
                """)) {
            for (var city : cities) {
                insert.setLong(1, city.playerId());
                insert.setInt(2, city.citySlot());
                insert.setInt(3, city.level());
                insert.addBatch();
            }
            if (!cities.isEmpty()) insert.executeBatch();
        }
    }
}
