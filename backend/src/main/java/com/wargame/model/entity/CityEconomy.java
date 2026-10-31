package com.wargame.model.entity;

/** Local economy; the legacy player columns remain authoritative for the main city. */
public interface CityEconomy {
    /** 安抚、税率等即时变化保留小数；达到上限或清零时同步清除越界余量。 */
    default void adjustSentiment(double moraleDelta, double resentmentDelta) {
        double morale = Math.max(0, Math.min(100, (getMorale() == null ? 70 : getMorale()) + getMoraleAccrual() + moraleDelta));
        double resentment = Math.max(0, Math.min(100, (getResentment() == null ? 0 : getResentment()) + getResentmentAccrual() + resentmentDelta));
        setMorale((int) Math.floor(morale)); setMoraleAccrual(morale - Math.floor(morale));
        setResentment((int) Math.floor(resentment)); setResentmentAccrual(resentment - Math.floor(resentment));
    }

    double getMoraleAccrual();
    void setMoraleAccrual(double value);
    double getResentmentAccrual();
    void setResentmentAccrual(double value);
    Integer getTax();
    void setTax(Integer value);
    Integer getMorale();
    void setMorale(Integer value);
    Integer getResentment();
    void setResentment(Integer value);
    Long getLastAppeaseAt();
    void setLastAppeaseAt(Long value);
    Integer getCivilianPopulation();
    void setCivilianPopulation(Integer value);
    Double getPopulationGrowthRemainder();
    void setPopulationGrowthRemainder(Double value);
    Long getLastTick();
    void setLastTick(Long value);
    String getCityName();
    void setCityName(String value);
    long getCityNameRenamedAt();
    void setCityNameRenamedAt(long value);
    Integer getCityPosX();
    void setCityPosX(Integer value);
    Integer getCityPosY();
    void setCityPosY(Integer value);
}
