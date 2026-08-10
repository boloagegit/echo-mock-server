package com.echo.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseBufferBudgetTest {

    @Test
    void reservationsShareOneLimitAndReleaseOnClose() {
        ResponseBufferBudget budget = new ResponseBufferBudget(10);
        var first = budget.openReservation();
        var second = budget.openReservation();

        assertThat(first.tryReserve(6)).isTrue();
        assertThat(second.tryReserve(5)).isFalse();
        assertThat(second.tryReserve(4)).isTrue();
        assertThat(budget.reservedBytes()).isEqualTo(10);

        first.close();
        assertThat(budget.reservedBytes()).isEqualTo(4);
        second.close();
        second.close();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void closedReservationCannotAcquireAgain() {
        ResponseBufferBudget budget = new ResponseBufferBudget(8);
        var reservation = budget.openReservation();
        reservation.close();

        assertThat(reservation.tryReserve(1)).isFalse();
        assertThat(budget.reservedBytes()).isZero();
    }
}
