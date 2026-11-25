package edu.pbl.gitviz.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 假的 git 可执行文件，用来模拟真实 git 难以稳定复现的情况（卡住不退出、版本过低等）。
 * Windows 上生成 .cmd，其他系统生成可执行的 sh 脚本。
 */
public final class FakeGit {

    private FakeGit() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /** 启动后什么也不输出，睡 {@code seconds} 秒再退出（期间会产生一个子进程）。 */
    public static Path sleeping(Path dir, int seconds) {
        return isWindows()
                ? script(dir, "fake-git-sleep.cmd", "@echo off\r\nping -n " + (seconds + 1) + " 127.0.0.1 > nul\r\n")
                : script(dir, "fake-git-sleep.sh", "#!/bin/sh\nsleep " + seconds + "\n");
    }

    private static Path script(Path dir, String name, String content) {
        try {
            Path file = dir.resolve(name);
            Files.writeString(file, content, StandardCharsets.US_ASCII);
            if (!file.toFile().setExecutable(true)) {
                throw new IOException("无法设为可执行：" + file);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
