package com.imperial.invoice.domain;

import org.junit.jupiter.api.Test;

import static com.imperial.invoice.domain.InvoiceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceTransitionsTest {

    @Test
    void issuedCanMoveToPaidOrOverdue() {
        assertThat(InvoiceTransitions.isAllowed(ISSUED, PAID)).isTrue();
        assertThat(InvoiceTransitions.isAllowed(ISSUED, OVERDUE)).isTrue();
    }

    @Test
    void overdueCanOnlyMoveToPaid() {
        assertThat(InvoiceTransitions.isAllowed(OVERDUE, PAID)).isTrue();
        assertThat(InvoiceTransitions.isAllowed(OVERDUE, OVERDUE)).isFalse();
        assertThat(InvoiceTransitions.isAllowed(OVERDUE, ISSUED)).isFalse();
    }

    @Test
    void anyStateCanMoveToClosed() {
        assertThat(InvoiceTransitions.isAllowed(ISSUED, CLOSED)).isTrue();
        assertThat(InvoiceTransitions.isAllowed(OVERDUE, CLOSED)).isTrue();
        assertThat(InvoiceTransitions.isAllowed(PAID, CLOSED)).isTrue();
    }

    @Test
    void paidIsTerminalExceptForClosed() {
        assertThat(InvoiceTransitions.isAllowed(PAID, PAID)).isFalse();
        assertThat(InvoiceTransitions.isAllowed(PAID, OVERDUE)).isFalse();
        assertThat(InvoiceTransitions.isAllowed(PAID, ISSUED)).isFalse();
    }

    @Test
    void issuedCannotGoBackwardsOrSkipToItself() {
        assertThat(InvoiceTransitions.isAllowed(ISSUED, ISSUED)).isFalse();
    }
}
