package com.shop.onlineshopping.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public enum SalesPeriod {
    WEEK,
    MONTH,
    QUARTER,
    YEAR;

    /** Start of this reporting window, relative to now (UTC). */
    public Instant since() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime start = switch (this) {
            case WEEK -> now.minusWeeks(1);
            case MONTH -> now.minusMonths(1);
            case QUARTER -> now.minusMonths(3);
            case YEAR -> now.minusYears(1);
        };
        return start.toInstant();
    }
}
