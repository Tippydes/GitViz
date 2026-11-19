package edu.pbl.gitviz.service.git;

import java.util.List;

/**
 * 一次 git 调用的结果。非零退出码不一定是错误（如 {@code merge-base --is-ancestor} 用退出码 1 表示"不是祖先"），
 * 由调用方决定是否 {@link #requireSuccess()}。
 *
 * @param commandLine 实际执行的完整命令
 * @param exitCode    退出码
 * @param stdout      标准输出经读取器处理后的结果
 * @param stderr      标准错误（UTF-8 解码，过长时截断）
 */
public record GitResult<T>(List<String> commandLine, int exitCode, T stdout, String stderr) {

    public GitResult {
        commandLine = List.copyOf(commandLine);
    }

    public boolean succeeded() {
        return exitCode == 0;
    }

    /** 退出码非零时抛出 {@link GitException}，保留命令、退出码和 stderr。 */
    public GitResult<T> requireSuccess() throws GitException {
        if (!succeeded()) {
            throw GitException.nonZeroExit(commandLine, exitCode, stderr);
        }
        return this;
    }
}
