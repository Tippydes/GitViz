package edu.pbl.gitviz.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorTimeTest {

    @Test
    void acceptsEpochZeroAndTimesBeforeTheEpoch() {
        assertEquals(0L, new AuthorTime(0L, 0).epochSecond());
        assertEquals(-315619200L, new AuthorTime(-315619200L, 480).epochSecond(), "1960 年");
        assertEquals(-1L, new AuthorTime(-1L, 480).epochSecond(), "1969-12-31 23:59:59");
    }

    @Test
    void instantsAndOffsetsRoundTripBeforeTheEpoch() {
        AuthorTime time = new AuthorTime(-315619200L, -330);

        // -315619200 是 1960-01-01T00:00:00Z；偏移只影响显示，不影响时刻本身
        assertEquals(Instant.parse("1960-01-01T00:00:00Z"), time.instant());
        assertEquals("-05:30", time.zoneOffset().getId());
    }

    @Test
    void isDisplayableMatchesTheRangeTheConstructorAccepts() {
        assertEquals(true, AuthorTime.isDisplayable(0L));
        assertEquals(false, AuthorTime.isDisplayable(Long.MIN_VALUE));
        assertEquals(false, AuthorTime.isDisplayable(Long.MAX_VALUE));
    }

    @Test
    void rejectsTimesThatCannotBeFormatted() {
        assertThrows(IllegalArgumentException.class, () -> new AuthorTime(Long.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new AuthorTime(Long.MAX_VALUE, 0));
    }

    @Test
    void rejectsOffsetsOutsideTheDesignRange() {
        assertThrows(IllegalArgumentException.class, () -> new AuthorTime(0L, -721));
        assertThrows(IllegalArgumentException.class, () -> new AuthorTime(0L, 841));
    }
}
