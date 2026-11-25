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

    /**
     * 只提交索引里已经暂存的内容，不做 {@code git add -A}。
     * 子模块指针（gitlink）在工作区里没有对应目录，{@code add -A} 会把它从索引里删掉，所以需要这个方法。
     */
    public String commitStaged(String message) {
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

    /**
     * 用 {@code git fast-import} 写入一条作者时间由调用方指定的提交，可以是 1970 年以前。
     * {@code git commit} 拒绝 1970 年以前的日期，所以用这个方法造"时间未知"的历史。
     *
     * <p>作者名和提交说明经标准输入以 UTF-8 字节传入，不走进程参数，因此不受平台默认字符集影响。
     * 提交写到当前分支（如果 HEAD 已经有提交，新提交接在它后面）。
     *
     * @param epochSeconds 作者和提交者时间，Unix 秒，可为负
     * @param timezone     形如 {@code +0800} 的时区
     */
    public String importCommit(String name, String email, long epochSeconds, String timezone, String subject,
                               String relativePath, String content) {
        String parent = headOrNull();
        byte[] stream = fastImportStream(name, email, epochSeconds, timezone, subject, parent, relativePath, content);
        ProcessBuilder builder = new ProcessBuilder("git", "fast-import", "--quiet").directory(root.toFile());
        builder.environment().putAll(isolatedEnvironment());
        try {
            Process process = builder.start();
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readUtf8(process.getErrorStream()));
            try (var in = process.getOutputStream()) {
                in.write(stream);
            }
            readUtf8(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("fast-import 失败（退出码 " + exitCode + "）：" + stderr.join());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        git("reset", "-q", "--hard", "main");
        return head();
    }

    /** fast-import 的数据流。长度必须是字节数，所以用 UTF-8 字节计算。parent 为空时写根提交。 */
    private static byte[] fastImportStream(String name, String email, long epochSeconds, String timezone,
                                           String subject, String parent, String relativePath, String content) {
        byte[] message = subject.getBytes(StandardCharsets.UTF_8);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] identity = (name + " <" + email + "> " + epochSeconds + " " + timezone).getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes("commit refs/heads/main\nmark :1\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(("author ").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(identity);
        out.writeBytes("\ncommitter ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(identity);
        out.writeBytes("\n".getBytes(StandardCharsets.US_ASCII));
        writeData(out, message);
        if (parent != null) {
            out.writeBytes(("from " + parent + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes(("M 100644 inline " + relativePath + "\n").getBytes(StandardCharsets.UTF_8));
        writeData(out, body);
        return out.toByteArray();
    }

    /** 当前 HEAD；仓库还没有提交时为 null。 */
    private String headOrNull() {
        try {
            return head();
        } catch (IllegalStateException noCommitsYet) {
            return null;
        }
    }

    private static void writeData(java.io.ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(("data " + bytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(bytes);
        out.writeBytes("\n".getBytes(StandardCharsets.US_ASCII));
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
