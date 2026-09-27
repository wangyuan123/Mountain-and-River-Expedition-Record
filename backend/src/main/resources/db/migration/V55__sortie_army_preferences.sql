-- 老玩家默认自动按迎战上限选兵，不改动已有驻军和战术指令。
ALTER TABLE players ADD COLUMN sortie_army TEXT NULL;
