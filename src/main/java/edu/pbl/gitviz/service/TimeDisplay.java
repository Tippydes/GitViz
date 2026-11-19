package edu.pbl.gitviz.service;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.TimeZoneMode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * 全程序唯一的时间显示入口：所有提交时间的显示、按日分组、日期筛选都经过这里。
 *
 * <p>默认按提交者时区显示、格式 {@code yyyy-MM-dd HH:mm}（§3.4.2 的默认值）。
 * 设置页修改时间格式或时区时，只需替换成新的实例。不可变、线程安全。
 */
public final class TimeDisplay {

    public static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm";

    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ROOT);

    private final TimeZoneMode mode;
    private final String pattern;
    private final ZoneId localZone;
    private final DateTimeFormatter formatter;

    /**
     * @param localZone 本机时区，{@link TimeZoneMode#LOCAL} 时使用
     */
    public TimeDisplay(TimeZoneMode mode, String pattern, ZoneId localZone) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.localZone = Objects.requireNonNull(localZone, "localZone");
        this.formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
    }

    /** 默认：提交者时区，{@code yyyy-MM-dd HH:mm}。 */
    public static TimeDisplay defaults() {
        return new TimeDisplay(TimeZoneMode.COMMIT, DEFAULT_PATTERN, ZoneId.systemDefault());
    }

    public TimeZoneMode mode() {
        return mode;
    }

    public String pattern() {
        return pattern;
    }

    /** 换算到显示时区后的时间。 */
    public ZonedDateTime toDisplayTime(long epochSecond, int tzOffsetMinutes) {
        ZoneId zone = mode == TimeZoneMode.COMMIT
                ? ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60)
                : localZone;
        return Instant.ofEpochSecond(epochSecond).atZone(zone);
    }

    public ZonedDateTime toDisplayTime(CommitRecord commit) {
        return toDisplayTime(commit.authorTime(), commit.tzOffsetMinutes());
    }

    /** 列表中显示的提交时间，如 {@code 2026-11-01 15:02}。 */
    public String format(CommitRecord commit) {
        return toDisplayTime(commit).format(formatter);
    }

    /** 带时区的提交时间，如 {@code 2026-11-01 15:02 (+08:00)}，用于摘要区和详情页。 */
    public String formatWithOffset(CommitRecord commit) {
        ZonedDateTime time = toDisplayTime(commit);
        return time.format(formatter) + " (" + time.format(OFFSET) + ")";
    }

    /** 提交在显示时区下属于哪一天，供按日筛选和分组。 */
    public LocalDate displayDate(CommitRecord commit) {
        return toDisplayTime(commit).toLocalDate();
    }
}
