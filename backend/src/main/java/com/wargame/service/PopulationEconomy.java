package com.wargame.service;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Resources;
import java.util.List;

/** 人口、税收和工资沿同一连续时间轴积分，不能用区间末人口追溯整段税收。 */
public final class PopulationEconomy {
    private PopulationEconomy() {}
    public record Result(double population, double gold) {}

    /**
     * 自适应四阶积分；正常段最多五秒，容量、民心转向等边界自动细分。
     * 小数随城市库存持久化；误差控制在远小于一人的范围，不按调用次数取整。
     */
    public static Result settle(double population, double gold, double morale, double resentment, int tax,
                                double resentmentRate, int cap, double taxFactor, double salary, long goldCap,
                                long from, long through, Resources stock, List<Officer> officers) {
        if (through <= from) return new Result(population, gold);
        double hours = (through - from) / 3600000.0, cursor = 0, step = Math.min(hours, 5.0 / 3600);
        long billed = from;
        while (cursor < hours - 1e-13) {
            step = Math.min(step, hours - cursor);
            double[] start = {population, gold};
            double[] whole = rk(start, cursor, step, morale, resentment, tax, resentmentRate, cap, taxFactor, salary, goldCap);
            double[] half = rk(start, cursor, step / 2, morale, resentment, tax, resentmentRate, cap, taxFactor, salary, goldCap);
            half = rk(half, cursor + step / 2, step / 2, morale, resentment, tax, resentmentRate, cap, taxFactor, salary, goldCap);
            double error = Math.max(Math.abs(whole[0] - half[0]), Math.abs(whole[1] - half[1]));
            if (error > 1e-7 && step > 1e-10) { step /= 2; continue; }
            double end = cursor + step;
            double[] sentiment = TickService.settleSentiment(morale, resentment, tax, resentmentRate, end);
            double limit = effectiveCap(cap, sentiment[0]);
            population = Math.max(0, half[0]);
            if (Math.abs(population - limit) < 1e-6) population = limit;
            long at = end >= hours - 1e-13 ? through : from + Math.round(end * 3600000);
            // 每段只兑现尚未处理的欠薪时长；负余额用于判断欠薪，不带入下一段形成隐形债务。
            OfficerSalaryArrears.settle(stock, officers, at, at - billed, gold, half[1], salary > 0);
            billed = at;
            gold = Math.max(0, half[1]);
            if (start[1] <= goldCap && gold > goldCap) gold = goldCap;
            if (Math.abs(gold - goldCap) < 1e-6) gold = goldCap;
            cursor = end;
            if (error < 1e-9) step = Math.min(5.0 / 3600, step * 2);
        }
        return new Result(population, gold);
    }

    private static double[] rk(double[] state, double t, double h, double morale, double resentment, int tax,
                                double rate, int cap, double factor, double salary, long goldCap) {
        double[] a = derivative(state, t, morale, resentment, tax, rate, cap, factor, salary, goldCap);
        double[] b = derivative(plus(state, a, h / 2), t + h / 2, morale, resentment, tax, rate, cap, factor, salary, goldCap);
        double[] c = derivative(plus(state, b, h / 2), t + h / 2, morale, resentment, tax, rate, cap, factor, salary, goldCap);
        double[] d = derivative(plus(state, c, h), t + h, morale, resentment, tax, rate, cap, factor, salary, goldCap);
        return new double[]{state[0] + h * (a[0] + 2*b[0] + 2*c[0] + d[0]) / 6,
                state[1] + h * (a[1] + 2*b[1] + 2*c[1] + d[1]) / 6};
    }

    private static double[] plus(double[] state, double[] rate, double hours) {
        return new double[]{state[0] + rate[0] * hours, state[1] + rate[1] * hours};
    }

    private static double[] derivative(double[] state, double t, double morale, double resentment, int tax,
                                        double rate, int cap, double factor, double salary, long goldCap) {
        double[] s = TickService.settleSentiment(morale, resentment, tax, rate, t);
        double limit = effectiveCap(cap, s[0]);
        double growth = cap * .03 * Math.max(.2, s[0] / 70);
        double difference = limit - state[0];
        double populationRate;
        if (Math.abs(difference) <= 1e-6) {
            double target = Math.max(0, Math.min(100, 100 - tax - s[1]));
            double r = s[1] <= 0 && rate < 0 || s[1] >= 100 && rate > 0 ? 0 : rate;
            double moraleRate = Math.abs(target - s[0]) < 1e-7 ? target > 0 && target < 100 ? -r : 0
                    : Math.copySign(10, target - s[0]);
            double capRate = s[0] < 70 && cap * s[0] / 70 > 10 ? cap * moraleRate / 70 : 0;
            // 人口处于移动容量边界时随边界走，避免增长和逃亡在边界来回震荡。
            populationRate = Math.min(growth, Math.max(-2, capRate));
        } else populationRate = difference > 0 ? growth : -Math.max(2, -difference * .1);
        if (state[0] <= 0 && populationRate < 0) populationRate = 0;
        double income = Math.max(0, state[0]) * factor;
        double goldRate = state[1] > goldCap + 1e-7 ? -salary
                : state[1] >= goldCap - 1e-7 ? Math.min(0, income - salary) : income - salary;
        return new double[]{populationRate, goldRate};
    }

    public static double effectiveCap(int cap, double morale) {
        return cap <= 0 ? 0 : Math.max(10, cap * Math.min(1, Math.max(0, morale) / 70));
    }
}
