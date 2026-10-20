package com.imperial.order.service;

public record StockReservationResult(boolean success, String rejectionReason) {
    public static StockReservationResult ok() {
        return new StockReservationResult(true, null);
    }

    public static StockReservationResult failed(String reason) {
        return new StockReservationResult(false, reason);
    }
}
