package edu.pbl.gitviz.model;

/**
 * 时间显示方式（§3.4.2 settings.json 的 timeZoneMode）。
 */
public enum TimeZoneMode {
    /** 按提交者当时的时区显示，与命令行 {@code git log} 的输出一致（默认）。 */
    COMMIT,
    /** 统一换算到本机时区显示。 */
    LOCAL
}
