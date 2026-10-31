package com.wargame;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Resources;
import com.wargame.service.OfficerSalaryArrears;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OfficerSalaryArrearsTest {
    private static final long START = 1_000_000L;

    private Officer officer() {
        var officer = new Officer();
        officer.setLoyalty(80);
        return officer;
    }

    @Test
    void gameTimeBoundaryAndRepeatedSettlementDoNotDependOnTickCount() {
        var stock = new Resources();
        var officer = officer();
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 59_999, 59_999, 0, -1, true);
        assertEquals(START, stock.getSalaryArrearsStartedAt());
        assertEquals(80, officer.getLoyalty());
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 60_000, 1, 0, -1, true);
        assertEquals(78, officer.getLoyalty());
        assertEquals(1, stock.getSalaryArrearsPenalties());
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 60_000, 0, 0, 0, true);
        assertEquals(START, stock.getSalaryArrearsStartedAt());
        assertEquals(78, officer.getLoyalty());
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 65_000, 5_000, 0, -1, true);
        assertEquals(76, officer.getLoyalty());
        assertEquals(2, stock.getSalaryArrearsPenalties());
    }

    @Test
    void salaryStartsAfterOpeningGoldRunsOutRatherThanAtLogin() {
        var stock = new Resources();
        var officer = officer();
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 60_000, 60_000, 30, -30, true);
        assertEquals(START + 30_000, stock.getSalaryArrearsStartedAt());
        assertEquals(80, officer.getLoyalty());
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 90_000, 30_000, 0, -30, true);
        assertEquals(78, officer.getLoyalty());
    }

    @Test
    void oneLongSettlementAndManyShortSettlementsApplyTheSamePenalty() {
        var once = new Resources();
        var frequent = new Resources();
        var onceOfficer = officer();
        var frequentOfficer = officer();
        OfficerSalaryArrears.settle(once, List.of(onceOfficer), START + 120_000, 120_000, 0, -120, true);
        for (int i = 1; i <= 24; i++) {
            OfficerSalaryArrears.settle(frequent, List.of(frequentOfficer), START + i * 5_000L, 5_000, 0, -5, true);
        }
        assertEquals(54, onceOfficer.getLoyalty());
        assertEquals(onceOfficer.getLoyalty(), frequentOfficer.getLoyalty());
        assertEquals(once.getSalaryArrearsPenalties(), frequent.getSalaryArrearsPenalties());
    }

    @Test
    void recruitedOfficerUsesOwnGracePeriodAcrossUnevenSettlements() {
        var once = new Resources();
        var frequent = new Resources();
        var onceOfficer = officer();
        var frequentOfficer = officer();
        long recruited = START + 61_500;
        onceOfficer.setRecruitAt(recruited);
        frequentOfficer.setRecruitAt(recruited);
        for (Resources stock : List.of(once, frequent)) {
            stock.setSalaryArrearsStartedAt(START);
            stock.setSalaryArrearsPenalties(1);
        }
        OfficerSalaryArrears.settle(once, List.of(onceOfficer), START + 130_000, 70_000, 0, -1, true);
        OfficerSalaryArrears.settle(frequent, List.of(frequentOfficer), START + 129_999, 69_999, 0, -1, true);
        assertEquals(76, frequentOfficer.getLoyalty());
        OfficerSalaryArrears.settle(frequent, List.of(frequentOfficer), START + 130_000, 1, 0, -1, true);
        assertEquals(onceOfficer.getLoyalty(), frequentOfficer.getLoyalty());
    }

    @Test
    void personalGraceCanExpireBetweenCityPenaltyBoundaries() {
        var stock = new Resources();
        var officer = officer();
        officer.setRecruitAt(START + 61_500);
        stock.setSalaryArrearsStartedAt(START);
        stock.setSalaryArrearsPenalties(13);
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 121_500, 1_500, 0, -1, true);
        assertEquals(78, officer.getLoyalty());
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 121_500, 0, 0, 0, true);
        assertEquals(78, officer.getLoyalty());
    }

    @Test
    void fullPaymentOrNoSalariesClearsArrearsAndStartsANewGracePeriod() {
        var stock = new Resources();
        var officer = officer();
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 60_000, 60_000, 0, -60, true);
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 65_000, 5_000, 5, 0, true);
        assertNull(stock.getSalaryArrearsStartedAt());
        assertEquals(0, stock.getSalaryArrearsPenalties());
        OfficerSalaryArrears.settle(stock, List.of(officer), START + 70_000, 5_000, 0, -5, true);
        assertEquals(START + 65_000, stock.getSalaryArrearsStartedAt());
        assertEquals(78, officer.getLoyalty());
        OfficerSalaryArrears.settle(stock, List.of(), START + 75_000, 5_000, 0, 0, false);
        assertNull(stock.getSalaryArrearsStartedAt());
    }

    @Test
    void citiesSettleIndependentlyAndLongArrearsNeverMakeLoyaltyNegative() {
        var first = new Resources();
        var second = new Resources();
        var officer = officer();
        OfficerSalaryArrears.settle(first, List.of(officer), START + 3_600_000, 3_600_000, 0, -100, true);
        assertEquals(0, officer.getLoyalty());
        assertNotNull(first.getSalaryArrearsStartedAt());
        assertNull(second.getSalaryArrearsStartedAt());
    }
}
