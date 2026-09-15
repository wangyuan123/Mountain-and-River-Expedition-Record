package com.wargame.model.entity;

/** Local economy; the legacy player columns remain authoritative for the main city. */
public interface CityEconomy {
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
    Integer getCityPosX();
    void setCityPosX(Integer value);
    Integer getCityPosY();
    void setCityPosY(Integer value);
}
