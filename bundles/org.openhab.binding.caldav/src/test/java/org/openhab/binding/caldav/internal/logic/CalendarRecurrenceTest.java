/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.caldav.internal.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openhab.binding.caldav.internal.model.CalendarEvent;

/**
 * Regression coverage for recurrence semantics and time zones.
 * 
 * @author Andreas Vilippus - Initial contribution
 */
@NonNullByDefault
@Timeout(10)
class CalendarRecurrenceTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final CalendarWindow WINDOW = new CalendarWindow(LocalDate.of(2026, 1, 1).atStartOfDay(BERLIN),
            LocalDate.of(2027, 1, 1).atStartOfDay(BERLIN));

    private List<CalendarEvent> parse(String events) {
        return ICalendarParser.parse("BEGIN:VCALENDAR\nVERSION:2.0\n" + events + "END:VCALENDAR\n", WINDOW, BERLIN,
                false);
    }

    private String event(String properties) {
        return "BEGIN:VEVENT\nUID:one\n" + properties + "END:VEVENT\n";
    }

    @Test
    void monthlyByDayAndUntil() {
        var events = parse(event(
                "DTSTART:20260105T090000Z\nDURATION:PT1H\nRRULE:FREQ=MONTHLY;BYDAY=1MO;UNTIL=20260331T235959Z\n"));
        assertEquals(List.of("2026-01-05", "2026-02-02", "2026-03-02"),
                events.stream().map(e -> e.start().toLocalDate().toString()).toList());
    }

    @Test
    void yearlyRecurrence() {
        assertEquals(1, parse(event("DTSTART:20200105T090000Z\nDURATION:PT1H\nRRULE:FREQ=YEARLY\n")).size());
    }

    @Test
    void timezoneRecurrenceKeepsWallClockAcrossDst() {
        var events = parse(event(
                "DTSTART;TZID=Europe/Berlin:20260328T090000\nDTEND;TZID=Europe/Berlin:20260328T100000\nRRULE:FREQ=DAILY;COUNT=3\n"));
        assertEquals(3, events.size());
        assertEquals(List.of(9, 9, 9), events.stream().map(e -> e.start().getHour()).toList());
        assertEquals(3600, events.getFirst().start().getOffset().getTotalSeconds());
        assertEquals(7200, events.getLast().start().getOffset().getTotalSeconds());
    }

    @Test
    void floatingTimeUsesOpenhabZone() {
        var event = parse(event("DTSTART:20260918T090000\nDTEND:20260918T100000\n")).getFirst();
        assertEquals("2026-09-18T07:00:00Z", event.start().toInstant().toString());
    }

    @Test
    void allDayRecurrenceHasExclusiveCalendarEnd() {
        var events = parse(event("DTSTART;VALUE=DATE:20260328\nDTEND;VALUE=DATE:20260330\nRRULE:FREQ=DAILY;COUNT=3\n"));
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(CalendarEvent::allDay));
        assertEquals(LocalDate.of(2026, 3, 31), events.get(1).allDayEnd());
    }

    @Test
    void recurrenceDatesIncludeBaseAndNormalizeExclusions() {
        var events = parse(event(
                "DTSTART:20260918T090000Z\nDURATION:PT1H\nRDATE:20260919T090000Z,20260920T090000Z\nEXDATE:20260919T090000Z\n"));
        assertEquals(2, events.size());
        assertEquals(18, events.getFirst().start().getDayOfMonth());
        assertEquals(20, events.getLast().start().getDayOfMonth());
    }

    @Test
    void movedAndCancelledExceptionsReplaceOriginals() {
        var events = parse(event("DTSTART:20260918T090000Z\nDURATION:PT1H\nRRULE:FREQ=DAILY;COUNT=3\n")
                + event("RECURRENCE-ID:20260919T090000Z\nDTSTART:20260919T120000Z\nDURATION:PT1H\n")
                + event("RECURRENCE-ID:20260920T090000Z\nSTATUS:CANCELLED\n"));
        assertEquals(2, events.size());
        assertTrue(events.stream()
                .anyMatch(e -> "2026-09-19T09:00:00Z".equals(e.recurrenceId()) && e.start().getHour() == 12));
        assertTrue(events.stream().noneMatch(e -> e.start().getDayOfMonth() == 20));
    }

    @Test
    void defaultDurationsAndPointEvents() {
        var events = parse(event("DTSTART;VALUE=DATE:20260918\n"));
        assertEquals(LocalDate.of(2026, 9, 19), events.getFirst().allDayEnd());
        var point = parse(event("DTSTART:20260918T090000Z\n")).getFirst();
        assertEquals(point.start(), point.end());
    }

    @Test
    void rejectsOversizedResources() {
        assertThrows(CalendarLimitException.class,
                () -> ICalendarParser.parse("x".repeat(ICalendarParser.MAX_RESOURCE_SIZE + 1), WINDOW, BERLIN, false));
    }
}
