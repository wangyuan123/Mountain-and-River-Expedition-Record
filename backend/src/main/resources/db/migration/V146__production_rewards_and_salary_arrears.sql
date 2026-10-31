-- 历史生产订单已在征召时领取声望，不能在完工时重复发放。
ALTER TABLE army_production_queue ADD COLUMN prestige_reward INT NOT NULL DEFAULT 0;

-- 按城持久化欠薪游戏时间与已处罚次数，不追溯没有可靠记录的历史欠薪。
ALTER TABLE resources ADD COLUMN salary_arrears_started_at BIGINT NULL;
ALTER TABLE resources ADD COLUMN salary_arrears_penalties BIGINT NOT NULL DEFAULT 0;
