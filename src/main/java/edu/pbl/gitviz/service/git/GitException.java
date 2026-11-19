package edu.pbl.gitviz.service.git;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * git 命令失败。保留执行的命令、退出码和 stderr，供错误分类与"技术详情"使用（§2.2.6 表 3）。
 */
public final class GitException extends Exception {

    private static final long serialVersionUID = 1L;
    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\\s\"']");

    /** 失败的方式。 */
    public enum Kind {
        /** git 可执行文件无法启动（未安装、路径错误、没有权限）。 */
        START_FAILED,
        /** 超过超时时间，进程已被终止。 */
        TIMED_OUT,
        /** git 以非零退出码结束。 */
        NON_ZERO_EXIT,
        /** 读取或解析 git 的输出失败。 */
        OUTPUT_UNREADABLE,
        /** 调用线程被中断（例如用户取消），进程已被终止。 */
        CANCELLED
    }

    private final Kind kind;
    private final String[] commandLine;
    private final int exitCode;
    private final String stderr;

    private GitException(Kind kind, String message, List<String> commandLine, int exitCode, String stderr,
                         Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.commandLine = commandLine.toArray(String[]::new);
        this.exitCode = exitCode;
        this.stderr = stderr == null ? "" : stderr;
    }

    static GitException startFailed(List<String> commandLine, Throwable cause) {
        return new GitException(Kind.START_FAILED,
                "无法运行 git。请确认已经安装 Git，并且在命令行中输入 git --version 能看到版本号。",
                commandLine, -1, "", cause);
    }

    static GitException timedOut(List<String> commandLine, Duration timeout, String stderr) {
        return new GitException(Kind.TIMED_OUT,
                "git 命令运行超过 " + timeout.toSeconds() + " 秒仍未结束，已被停止。仓库可能非常大，或者磁盘响应很慢。",
                commandLine, -1, stderr, null);
    }

    static GitException nonZeroExit(List<String> commandLine, int exitCode, String stderr) {
        return new GitException(Kind.NON_ZERO_EXIT,
                "git 命令执行失败（退出码 " + exitCode + "）。请确认路径是一个已有提交的 Git 仓库，具体原因见技术详情。",
                commandLine, exitCode, stderr, null);
    }

    static GitException outputUnreadable(List<String> commandLine, Throwable cause, String stderr) {
        return new GitException(Kind.OUTPUT_UNREADABLE,
                "无法读取 git 的输出。可能是 Git 版本过旧（需要 2.31 及以上），或者输出格式与预期不符。",
                commandLine, -1, stderr, cause);
    }

    static GitException cancelled(List<String> commandLine) {
        return new GitException(Kind.CANCELLED, "读取已取消。", commandLine, -1, "", null);
    }

    public Kind kind() {
        return kind;
    }

    /** 实际执行的完整命令，第一项是 git 可执行文件。 */
    public List<String> commandLine() {
        return List.of(commandLine);
    }

    /** git 的退出码；进程没有正常结束时为 -1。 */
    public int exitCode() {
        return exitCode;
    }

    public String stderr() {
        return stderr;
    }

    /** 面向初学者的中文说明（发生了什么、怎么办）。 */
    public String userMessage() {
        return getMessage();
    }

    /** 可复制的技术详情：命令、退出码、git 原始输出。 */
    public String technicalDetails() {
        StringBuilder text = new StringBuilder();
        text.append("命令：").append(commandLineText()).append('\n');
        text.append("退出码：").append(exitCode < 0 ? "无" : Integer.toString(exitCode)).append('\n');
        if (getCause() != null) {
            text.append("原因：").append(getCause()).append('\n');
        }
        text.append('\n').append("git 输出：").append('\n');
        text.append(stderr.isBlank() ? "（无）" : stderr.strip());
        return text.toString();
    }

    /** 便于阅读和复制的命令行，含空格或引号的参数加上双引号。 */
    public String commandLineText() {
        return Arrays.stream(commandLine)
                .map(arg -> NEEDS_QUOTES.matcher(arg).find() || arg.isEmpty()
                        ? '"' + arg.replace("\"", "\\\"") + '"'
                        : arg)
                .collect(Collectors.joining(" "));
    }
}
