-- 直线距离保留小数，行军时间与油耗不按格数提前取整。
ALTER TABLE marches MODIFY COLUMN distance DOUBLE;
