package com.imperial.credit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyReportView(LocalDate reportMonth, int nearLimitTenantCount, BigDecimal poolUtilizationPercent) {
}
