package edu.pbl.gitviz.service.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行 git 子进程的唯一入口。
 *
 * <ul>
 *   <li>只接受 {@link GitCommand}，因此只能执行只读白名单内的子命令；</li>
 *   <li>用参数列表调用，仓库路径通过 {@code -C} 作为独立参数传入，从不拼接成命令行字符串；</li>
 *   <li>每条命令都带 {@code -c i18n.logOutputEncoding=UTF-8}，输出一律按 UTF-8 解码；</li>
 *   <li>单条命令超过超时时间（默认 60 秒）即终止整棵进程树，并以 {@link GitException.Kind#TIMED_OUT} 失败。</li>
 * </ul>
 */
public final class GitRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 每条命令都固定下来的配置项。它们覆盖用户、系统和仓库里会改变输出的设置，这些设置都在
     * {@code GitServiceTest.personalGitConfigCannotChangeWhatIsRead} 中逐项验证过：
     * <ul>
     *   <li>{@code diff.relative}：在子目录里运行时会丢掉子目录外的文件，路径也会改成相对子目录；</li>
     *   <li>{@code diff.algorithm}：不同算法给出的增删行数不同（实测 +2/−2 与 +15/−15）；</li>
     *   <li>{@code diff.ignoreSubmodules}：会让子模块指针的变化消失；</li>
     *   <li>{@code diff.renameLimit}：固定为默认的 1000，不让个人设置改变重命名检测的范围；</li>
     *   <li>{@code core.attributesFile}、{@code mailmap.file}、{@code mailmap.blob}：空值表示不读取个人文件，
     *       仓库自己的 .mailmap 仍然生效（设计 §3.4.7）。</li>
     * </ul>
     */
    static final List<String> PINNED_CONFIG = List.of(
            "i18n.logOutputEncoding=UTF-8",
            "diff.relative=false",
            "diff.algorithm=myers",
            "diff.ignoreSubmodules=none",
            "diff.renameLimit=1000",
            "core.attributesFile=",
            "mailmap.file=",
            "mailmap.blob=");

    private static final int STDERR_LIMIT_BYTES = 256 * 1024;

    /**
     * 从父进程继承下来、会让 git 改去读写另一个仓库或额外运行程序的环境变量。
     * 移除后，{@code -C} 指定的目录就是被读取的仓库。
     */
    private static final List<String> REMOVED_ENVIRONMENT = List.of(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_NAMESPACE",
            "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_EXTERNAL_DIFF",
            // 通过环境变量注入的配置：不移除的话，上面 PINNED_CONFIG 之外的设置仍然会生效
            "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT", "GIT_ATTR_SOURCE");

    /**
     * {@code GIT_OPTIONAL_LOCKS=0}：读命令不顺手刷新并写回索引，保证被分析的仓库保持原样；
     * {@code GIT_TERMINAL_PROMPT=0}：任何情况下都不等待终端输入。
     */
    private static final Map<String, String> FIXED_ENVIRONMENT = Map.of(
            "GIT_OPTIONAL_LOCKS", "0",
            "GIT_TERMINAL_PROMPT", "0",
            // refs/replace 会让 git log 显示被替换后的提交，不是仓库里实际记录的历史（实测）
            "GIT_NO_REPLACE_OBJECTS", "1");

    private static final ScheduledThreadPoolExecutor WATCHDOG = createWatchdog();

    private final String executable;
    private final Duration timeout;
    private final Map<String, String> environment;

    /** 从 PATH 查找 git，超时 60 秒。 */
    public GitRunner() {
        this("git", DEFAULT_TIMEOUT, Map.of());
    }

    /**
     * @param executable  git 可执行文件：{@code "git"} 表示从 PATH 查找，也可以是绝对路径
     * @param timeout     单条命令的超时时间
     * @param environment 额外的环境变量（测试用它隔离开发者本机的 Git 配置）
     */
    public GitRunner(String executable, Duration timeout, Map<String, String> environment) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.environment = Map.copyOf(environment);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正: " + timeout);
        }
    }

    /** 读取标准输出的方式；在调用线程上执行，可以边读边解析。 */
    @FunctionalInterface
    public interface StdoutReader<T> {
        T read(InputStream stdout) throws IOException;
    }

    /** 把标准输出整体读成 UTF-8 文本。 */
    public GitResult<String> runText(Path workDir, GitCommand command) throws GitException {
        return runText(workDir, command, new CancellationToken());
    }

    /** 把标准输出整体读成 UTF-8 文本，可取消。 */
    public GitResult<String> runText(Path workDir, GitCommand command, CancellationToken token)
            throws GitException {
        return run(workDir, command, in -> new String(in.readAllBytes(), StandardCharsets.UTF_8), token);
    }

    /** 执行命令，不可取消。 */
    public <T> GitResult<T> run(Path workDir, GitCommand command, StdoutReader<T> reader) throws GitException {
        return run(workDir, command, reader, new CancellationToken());
    }

    /**
     * 执行命令。读取器抛出的异常（包括解析失败）会终止进程并转成 {@link GitException}。
     *
     * <p>{@code token} 被取消时，正在运行的进程会被立即结束（连同它的子进程），本次调用以
     * {@link GitException.Kind#CANCELLED} 失败。取消发生在进程结束之后则不影响已经得到的结果。
     *
     * @param workDir 作为 {@code -C} 传入的目录；为 null 时不传（如 {@code git --version}）
     * @throws GitException 无法启动、超时、取消、输出无法读取；非零退出码不抛出，见 {@link GitResult#requireSuccess()}
     */
    public <T> GitResult<T> run(Path workDir, GitCommand command, StdoutReader<T> reader,
                                CancellationToken token) throws GitException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(token, "token");
        List<String> commandLine = commandLine(workDir, command);
        if (token.isCancelled()) {
            throw GitException.cancelled(commandLine);
        }

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        Map<String, String> env = builder.environment();
        REMOVED_ENVIRONMENT.forEach(env::remove);
        env.putAll(FIXED_ENVIRONMENT);
        env.putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw GitException.startFailed(commandLine, e);
        }
        // 取消时直接结束进程树，不依赖"写管道失败"这种偶然的结束方式
        token.onCancel(() -> destroyTree(process));

        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            if (process.isAlive()) {
                timedOut.set(true);
                destroyTree(process);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        StderrCollector stderr = StderrCollector.start(process.getErrorStream());

        try {
            closeQuietly(process.getOutputStream());
            T value = null;
            Exception readFailure = null;
            boolean killedAfterReadFailure = false;
            try (InputStream stdout = process.getInputStream()) {
                value = reader.read(stdout);
                stdout.transferTo(OutputStream.nullOutputStream());
            } catch (IOException | RuntimeException e) {
                readFailure = e;
                if (process.isAlive()) {
                    killedAfterReadFailure = true;
                    destroyTree(process);
                }
            }

            int exitCode = process.waitFor();
            String errorText = stderr.awaitText();
            if (token.isCancelled()) {
                throw GitException.cancelled(commandLine);
            }
            if (timedOut.get()) {
                throw GitException.timedOut(commandLine, timeout, errorText);
            }
            if (readFailure != null) {
                // git 自己先失败了，输出不完整是结果而不是原因
                if (!killedAfterReadFailure && exitCode != 0) {
                    throw GitException.nonZeroExit(commandLine, exitCode, errorText);
                }
                throw GitException.outputUnreadable(commandLine, readFailure, errorText);
            }
            return new GitResult<>(commandLine, exitCode, value, errorText);
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            throw GitException.cancelled(commandLine);
        } finally {
            watchdog.cancel(false);
            if (process.isAlive()) {
                destroyTree(process);
            }
        }
    }

    List<String> commandLine(Path workDir, GitCommand command) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(executable);
        if (workDir != null) {
            commandLine.add("-C");
            commandLine.add(workDir.toString());
        }
        for (String pin : PINNED_CONFIG) {
            commandLine.add("-c");
            commandLine.add(pin);
        }
        commandLine.addAll(command.toArgumentList());
        return commandLine;
    }

    /** 先结束子孙进程再结束 git 本身，避免残留进程继续占着输出管道。已经结束的进程什么也不做。 */
    private static void destroyTree(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 进程已经退出时关闭 stdin 可能失败，不影响结果
        }
    }

    private static ScheduledThreadPoolExecutor createWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "gitviz-git-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** 在独立线程中读完 stderr，防止输出塞满管道让 git 阻塞；只保留前 256 KB。 */
    private static final class StderrCollector implements Runnable {

        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;
        private Thread thread;

        private StderrCollector(InputStream stream) {
            this.stream = stream;
        }

        static StderrCollector start(InputStream stream) {
            StderrCollector collector = new StderrCollector(stream);
            collector.thread = new Thread(collector, "gitviz-git-stderr");
            collector.thread.setDaemon(true);
            collector.thread.start();
            return collector;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (stream) {
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    synchronized (this) {
                        int room = STDERR_LIMIT_BYTES - buffer.size();
                        if (room > 0) {
                            buffer.write(chunk, 0, Math.min(n, room));
                        }
                        if (n > room) {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程被终止时管道会断开，已读到的内容仍然有效
            }
        }

        String awaitText() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            synchronized (this) {
                String text = buffer.toString(StandardCharsets.UTF_8);
                return truncated ? text + "\n……（输出过长，已截断）" : text;
            }
        }
    }
}
