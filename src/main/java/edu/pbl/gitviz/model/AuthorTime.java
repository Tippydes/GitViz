package edu.pbl.gitviz.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 作者时间：Unix 秒加上作者当时的时区偏移（§3.4.7 的 author_time 与 tz_offset）。
 *
 * <p>{@code epochSecond} 可以为负，表示 1970 年以前。git 无法给出时间时整项缺失，见
 * {@link CommitRecord#authorTime()}。
 *
 * @param epochSecond     Unix 秒
 * @param tzOffsetMinutes 时区偏移，单位分钟，如 +08:00 为 480；取值范围 −720～840（§3.4.7）
 */
public record AuthorTime(long epochSecond, int tzOffsetMinutes) {

    /** §3.4.7 tz_offset 的取值范围下限，单位分钟。 */
    public static final int MIN_TZ_OFFSET = -720;
    /** §3.4.7 tz_offset 的取值范围上限，单位分钟。 */
    public static final int MAX_TZ_OFFSET = 840;

    /**
     * 能安全换算成日期时间的范围：LocalDateTime 能表示的范围两端各缩进两天，留出时区换算的余量，
     * 保证界面上格式化时不会因为日期越界而抛出异常。
     */
    private static final long MIN_EPOCH_SECOND = LocalDateTime.MIN.plusDays(2).toEpochSecond(ZoneOffset.UTC);
    private static final long MAX_EPOCH_SECOND = LocalDateTime.MAX.minusDays(2).toEpochSecond(ZoneOffset.UTC);

    public AuthorTime {
        if (!isDisplayable(epochSecond)) {
            throw new IllegalArgumentException("时间超出可以显示的范围: " + epochSecond);
        }
        if (tzOffsetMinutes < MIN_TZ_OFFSET || tzOffsetMinutes > MAX_TZ_OFFSET) {
            throw new IllegalArgumentException("时区偏移超出范围: " + tzOffsetMinutes);
        }
    }

    /** 这个时刻能否安全地换算成日期时间（见 {@link AuthorTime} 的范围说明）。 */
    public static boolean isDisplayable(long epochSecond) {
        return epochSecond >= MIN_EPOCH_SECOND && epochSecond <= MAX_EPOCH_SECOND;
    }

    public Instant instant() {
        return Instant.ofEpochSecond(epochSecond);
    }

    public ZoneOffset zoneOffset() {
        return ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60);
    }
}
