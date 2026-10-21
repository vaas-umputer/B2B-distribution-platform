package com.imperial.invoice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findAllByOrderByCreatedAtDesc();
    Optional<Invoice> findByOrderId(UUID orderId);
}
