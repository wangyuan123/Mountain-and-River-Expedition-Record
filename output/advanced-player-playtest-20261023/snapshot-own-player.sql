SELECT id,display_name,city_pos_x,city_pos_y,military_rank FROM players WHERE id=177;
SELECT id,type,level,city_slot,slot,version FROM buildings WHERE player_id=177;
SELECT id,type,level,version FROM technologies WHERE player_id=177;
SELECT id,type,count,city_slot,version FROM army_units WHERE player_id=177;
SELECT id,food,steel,oil,rare,gold,diamond,city_slot FROM resources WHERE player_id=177;
SELECT item_key,count FROM player_items WHERE player_id=177;
