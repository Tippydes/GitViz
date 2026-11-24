package edu.pbl.gitviz.testutil;

import edu.pbl.gitviz.service.git.GitRunner;
import edu.pbl.gitviz.service.git.GitService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 在临时目录里用 git 命令现场造一个测试仓库。
 *
 * <p>与开发者本机无关：不读系统级和用户级 Git 配置（{@code GIT_CONFIG_NOSYSTEM}、{@code GIT_CONFIG_GLOBAL}）。
 * 作者写进 {@code .git/gitviz-test-identity}，提交说明经 {@code -F} 文件传入，时间通过环境变量指定。
 *
 * <p><b>进程的参数和环境变量只放 ASCII。</b>JDK 17 的 {@link ProcessBuilder} 在 Linux 上按默认字符集
 * （测试 JVM 里是 GBK）编码参数和环境变量，中文会变成乱码（CI 上实测过）。中文数据只走 UTF-8 文件。
 *
 * <pre>{@code
 * TestRepo repo = TestRepo.init(tempDir);
 * String root = repo.author("张三", "zhang@example.com").at("2026-11-01T10:00:00+08:00")
 *         .write("a.txt", "hello\n")
 *         .commit("初始化");
 * repo.branch("feature").checkout("feature").write("f.txt", "f\n").commit("功能");
 * String merge = repo.checkout("main").merge("feature", "合并 feature");
 * }</pre>
 *
 * <p>没有用 {@link #at} 指定时间时，每次提交比上一次晚一小时，从 2026-11-01 10:00 (+08:00) 开始。
 */
public final class TestRepo {

    private static final OffsetDateTime DEFAULT_START = OffsetDateTime.parse("2026-11-01T10:00:00+08:00");

    /** 作者信息所在的文件，位于 {@code .git} 目录下，由 {@code .git/config} 的 include 引入。 */
    static final String IDENTITY_FILE = "gitviz-test-identity";

    private final Path root;
    private final Path workDir;
    private final Path globalConfig;
    private String authorName = "测试者";
    private String authorEmail = "tester@example.com";
    private OffsetDateTime nextTime = DEFAULT_START;
    private int messageCounter;

    private TestRepo(Path workDir) {
        this.workDir = workDir;
        this.root = workDir.resolve("repo");
        this.globalConfig = workDir.resolve("global.gitconfig");
    }

    /**
     * 在 {@code tempDir/repo} 新建仓库，默认分支 main。{@code tempDir} 里还会放隔离用的全局配置和提交说明文件。
     */
    public static TestRepo init(Path tempDir) {
        TestRepo repo = new TestRepo(tempDir);
        try {
            Files.createDirectories(repo.root);
            Files.writeString(repo.globalConfig, "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        repo.git("init", "-q", "-b", "main");
        repo.git("config", "core.autocrlf", "false");
        repo.git("config", "commit.gpgsign", "false");
        repo.git("config", "include.path", IDENTITY_FILE);
        return repo;
    }

    /** 仓库根目录。 */
    public Path root() {
        return root;
    }

    /** 隔离用的"用户级"Git 配置文件，测试可以往里写配置来模拟用户的个人设置。 */
    public Path globalConfig() {
        return globalConfig;
    }

    /** 让被测的 GitRunner 也不读开发者本机 Git 配置的环境变量。 */
    public Map<String, String> isolatedEnvironment() {
        return Map.of(
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", globalConfig.toString());
    }

    /** 使用隔离环境的 GitService，超时 60 秒。 */
    public GitService gitService() {
        return new GitService(new GitRunner("git", GitRunner.DEFAULT_TIMEOUT, isolatedEnvironment()));
    }

    /** 之后的提交使用这个作者和提交者。名字可以含中文。 */
    public TestRepo author(String name, String email) {
        this.authorName = name;
        this.authorEmail = email;
        return this;
    }

    /** 下一次提交的作者时间和提交者时间，ISO-8601 带时区，如 {@code 2026-11-01T15:02:37+08:00}。 */
    public TestRepo at(String isoOffsetDateTime) {
        this.nextTime = OffsetDateTime.parse(isoOffsetDateTime);
        return this;
    }

    public TestRepo write(String relativePath, String content) {
        return writeBytes(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    public TestRepo writeBytes(String relativePath, byte[] content) {
        try {
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** 在文件系统里改名，不经过 git 命令行参数；下一次 {@link #commit} 时 git 会识别为重命名（-M）。 */
    public TestRepo rename(String from, String to) {
        try {
            Path target = root.resolve(to);
            Files.createDirectories(target.getParent());
            Files.move(root.resolve(from), target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** 在文件系统里删除文件；下一次 {@link #commit} 时记为删除。 */
    public TestRepo remove(String relativePath) {
        try {
            Files.delete(root.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** 暂存工作区的全部改动并提交，返回新提交的完整哈希。 */
    public String commit(String message) {
        git("add", "-A");
        return commitWith(message);
    }

    /** 不改任何文件的空提交。 */
    public String commitEmpty(String message) {
        return commitWith(message, "--allow-empty");
    }

    public TestRepo branch(String name) {
        git("branch", name);
        return this;
    }

    public TestRepo checkout(String name) {
        git("checkout", "-q", name);
        return this;
    }

    /** 检出某个提交，进入分离头指针状态。 */
    public TestRepo detach(String revision) {
        git("checkout", "-q", "--detach", revision);
        return this;
    }

    /** {@code --no-ff} 合并，总是生成合并提交，返回它的哈希。 */
    public String merge(String branch, String message) {
        writeIdentity();
        git("merge", "-q", "--no-ff", "--no-edit", "-F", messageFile(message).toString(), branch);
        return advance(head());
    }

    public String head() {
        return git("rev-parse", "HEAD").strip();
    }

    /** 在仓库里运行任意 git 命令（测试夹具不受只读白名单限制），返回标准输出。 */
    public String git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        builder.environment().putAll(environment());
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readUtf8(process.getErrorStream()));
            String stdout = readUtf8(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("测试夹具中的 git 命令失败（退出码 " + exitCode + "）："
                        + String.join(" ", command) + "\n" + stderr.join());
            }
            return stdout;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String commitWith(String message, String... extraArgs) {
        writeIdentity();
        List<String> args = new ArrayList<>(List.of("commit", "-q", "--allow-empty-message", "--cleanup=verbatim",
                "-F", messageFile(message).toString()));
        args.addAll(List.of(extraArgs));
        git(args.toArray(String[]::new));
        return advance(head());
    }

    /**
     * 把当前作者写进 {@code .git/gitviz-test-identity}（UTF-8）。作者名含中文时只能走文件：
     * 环境变量和命令行参数会按默认字符集编码，CI 上会变成乱码。每次提交前调用。
     */
    private void writeIdentity() {
        String content = "[user]\n\tname = " + quoted(authorName) + "\n\temail = " + quoted(authorEmail) + "\n";
        try {
            Files.writeString(root.resolve(".git").resolve(IDENTITY_FILE), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** git 配置值的双引号写法；转义反斜杠和双引号。 */
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String advance(String hash) {
        nextTime = nextTime.plus(Duration.ofHours(1));
        return hash;
    }

    private Path messageFile(String message) {
        Path file = workDir.resolve("message-" + (++messageCounter) + ".txt");
        try {
            Files.writeString(file, message, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** 只有时间走环境变量（ASCII）。作者不在这里，见 {@link #writeIdentity()}。 */
    private Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(isolatedEnvironment());
        String time = nextTime.toString();
        env.put("GIT_AUTHOR_DATE", time);
        env.put("GIT_COMMITTER_DATE", time);
        return env;
    }

    private static String readUtf8(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
