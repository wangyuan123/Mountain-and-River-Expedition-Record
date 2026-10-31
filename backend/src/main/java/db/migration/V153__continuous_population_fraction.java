package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.util.ArrayList;

/** 旧逃亡余量是待扣人数；转为连续人口的小数时保留同一精确人口，不增减整个人。 */
public class V153__continuous_population_fraction extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        for (String table : new String[]{"players", "player_cities"}) {
            String owner = table.equals("players") ? "id" : "owner_id";
            String slot = table.equals("players") ? "0" : "e.city_slot";
            record Change(long id, int population, double remainder) {}
            var changes = new ArrayList<Change>();
            try (var q = c.createStatement(); var r = q.executeQuery("SELECT e.id,e.civilian_population,e.population_growth_remainder,e.morale,"
                    + "(SELECT COALESCE(SUM(b.level),0)*1200 FROM buildings b WHERE b.player_id=e." + owner
                    + " AND b.city_slot=" + slot + " AND b.type='house') AS capacity FROM " + table + " e WHERE e.population_growth_remainder > 0")) {
                while (r.next()) {
                    int cap = r.getInt("capacity");
                    int effective = cap <= 0 ? 0 : Math.max(10, (int) Math.round(cap * Math.min(1, r.getInt("morale") / 70.0)));
                    double remainder = r.getDouble("population_growth_remainder");
                    int population = r.getInt("civilian_population");
                    if (population > effective && remainder < 1) changes.add(new Change(r.getLong("id"), population - 1, 1 - remainder));
                }
            }
            try (var q = c.prepareStatement("UPDATE " + table + " SET civilian_population=?,population_growth_remainder=? WHERE id=?")) {
                for (Change change : changes) {
                    q.setInt(1, change.population()); q.setDouble(2, change.remainder()); q.setLong(3, change.id()); q.addBatch();
                }
                q.executeBatch();
            }
        }
    }
}
