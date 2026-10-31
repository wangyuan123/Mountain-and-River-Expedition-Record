package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 实存城市与已接受的移交共用军衔名额；写入口由调用方持有账号锁。 */
@Service
@RequiredArgsConstructor
public class CityCapacityService {
    private final PlayerCityRepository cities;
    private final CitySurrenderOfferRepository offers;
    private final PlayerRepository players;

    /** 兑现某份预留时只排除该份有效预留，其余待移交城市继续占名额。 */
    public boolean hasRoom(Long owner, Long redeemingOffer) {
        int cap = MilitaryRankDef.getCityCap(players.findCurrentMilitaryRankForUpdate(owner).orElseThrow());
        // 账号锁只串行化写入，不会更新事务已建立的 MySQL 快照；两类名额都必须当前读。
        int occupied = cities.findCurrentSlotsForUpdate(owner).size();
        var reservations = offers.findCurrentReservationsForUpdate(owner);
        long reserved = reservations.size() - (reservations.contains(redeemingOffer) ? 1 : 0);
        return occupied + reserved < cap;
    }
}
