package edu.pbl.gitviz.service;

import edu.pbl.gitviz.model.AuthorTime;
import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.TimeZoneMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeDisplayTest {

    /** 设计文档 §3.4.7 的例子：a81d04e 的 1793516557 和 480 还原为 2026-11-01 15:02:37 (+08:00)。 */
    private static final CommitRecord A81D04E = commit(Optional.of(new AuthorTime(1793516557L, 480)));

    private static CommitRecord commit(Optional<AuthorTime> authorTime) {
        return new CommitRecord("a81d04e5c2f7b9306e4a1d8c52f07b3e9a6d1c48", List.of(), "李同学", "li@example.com",
                authorTime, "修复空仓库打开时报错的问题", List.of());
    }

    private static CommitRecord at(String isoOffsetDateTime) {
        OffsetDateTime time = OffsetDateTime.parse(isoOffsetDateTime);
        return commit(Optional.of(new AuthorTime(time.toEpochSecond(), time.getOffset().getTotalSeconds() / 60)));
    }

    @Test
    void defaultsToTheCommittersTimezoneAndTheDefaultPattern() {
        TimeDisplay display = TimeDisplay.defaults();

        assertEquals(TimeZoneMode.COMMIT, display.mode());
        assertEquals("yyyy-MM-dd HH:mm", display.pattern());
        assertEquals("2026-11-01 15:02", display.format(A81D04E));
        assertEquals("2026-11-01 15:02 (+08:00)", display.formatWithOffset(A81D04E));
    }

    @Test
    void localModeConvertsToTheMachineTimezone() {
        TimeDisplay display = new TimeDisplay(TimeZoneMode.LOCAL, TimeDisplay.DEFAULT_PATTERN, ZoneId.of("UTC"));

        assertEquals("2026-11-01 07:02", display.format(A81D04E));
        assertEquals("2026-11-01 07:02 (+00:00)", display.formatWithOffset(A81D04E));
    }

    @Test
    void theDisplayDateDependsOnTheMode() {
        CommitRecord lateEvening = at("2026-11-01T23:30:00-05:00");

        TimeDisplay commitZone = new TimeDisplay(TimeZoneMode.COMMIT, TimeDisplay.DEFAULT_PATTERN,
                ZoneId.of("Asia/Shanghai"));
        TimeDisplay localZone = new TimeDisplay(TimeZoneMode.LOCAL, TimeDisplay.DEFAULT_PATTERN,
                ZoneId.of("Asia/Shanghai"));

        assertEquals(Optional.of(LocalDate.of(2026, 11, 1)), commitZone.displayDate(lateEvening));
        assertEquals(Optional.of(LocalDate.of(2026, 11, 2)), localZone.displayDate(lateEvening));
    }

    @Test
    void usesTheConfiguredPattern() {
        TimeDisplay display = new TimeDisplay(TimeZoneMode.COMMIT, "yyyy/MM/dd HH:mm:ss", ZoneId.of("UTC"));

        assertEquals("2026/11/01 15:02:37", display.format(A81D04E));
    }

    @Test
    void formatsTimesBeforeTheEpochInTheCommittersZone() {
        CommitRecord year1960 = commit(Optional.of(new AuthorTime(-315619200L, 480)));
        TimeDisplay display = new TimeDisplay(TimeZoneMode.COMMIT, TimeDisplay.DEFAULT_PATTERN, ZoneId.of("UTC"));

        assertEquals("1960-01-01 08:00", display.format(year1960));
        assertEquals("1960-01-01 08:00 (+08:00)", display.formatWithOffset(year1960));
        assertEquals(Optional.of(LocalDate.of(1960, 1, 1)), display.displayDate(year1960));
    }

    @Test
    void anUnknownTimeShowsAsUnknownAndHasNoDate() {
        CommitRecord unknown = commit(Optional.empty());
        TimeDisplay display = TimeDisplay.defaults();

        assertEquals(TimeDisplay.UNKNOWN, display.format(unknown));
        assertEquals(TimeDisplay.UNKNOWN, display.formatWithOffset(unknown));
        assertEquals(Optional.empty(), display.displayDate(unknown));
        assertEquals(Optional.empty(), display.toDisplayTime(unknown));
    }
}
