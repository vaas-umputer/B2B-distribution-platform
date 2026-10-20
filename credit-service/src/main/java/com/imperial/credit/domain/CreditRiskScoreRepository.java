package com.imperial.credit.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CreditRiskScoreRepository extends JpaRepository<CreditRiskScore, UUID> {
}
