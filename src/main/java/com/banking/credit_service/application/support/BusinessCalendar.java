package com.banking.credit_service.application.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Translates the UTC clock into business dates. Instants are stored in UTC; the dates of
 * the product report follow the configured business zone.
 */
public final class BusinessCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** Current instant with millisecond precision, the precision kept in PostgreSQL and JSON. */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Instant of a new movement of a product whose last change happened at {@code previous}:
     * now, or one millisecond after {@code previous} when the clock has not moved past it.
     * Movements of one product are posted under its row lock, so their instants strictly
     * increase and the history order (date, then ID) is the posting order, even within one
     * millisecond or if the system clock steps back.
     */
    public Instant nowAfter(Instant previous) {
        Instant now = now();
        Instant next = previous.truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
        return now.isBefore(next) ? next : now;
    }

    /** First instant of a business date. */
    public Instant startOf(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** Business time zone. */
    public ZoneId zone() {
        return zone;
    }
}
