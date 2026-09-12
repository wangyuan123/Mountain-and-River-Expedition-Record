CREATE INDEX idx_players_last_tick_id ON players(last_tick, id);
CREATE INDEX idx_players_city_coordinates ON players(city_pos_x, city_pos_y);
CREATE INDEX idx_npc_world_coordinates ON npc_cities(world_id, x, y);
CREATE INDEX idx_player_city_world_coordinates ON player_cities(world_id, x, y);
CREATE INDEX idx_bandit_world_coordinates ON bandits(world_id, x, y);
CREATE INDEX idx_wild_world_coordinates ON wild_tiles(world_id, x, y);
CREATE INDEX idx_wild_owner ON wild_tiles(occupied_by);
