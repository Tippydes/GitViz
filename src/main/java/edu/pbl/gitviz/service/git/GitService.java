package edu.pbl.gitviz.service.git;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.HeadInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 读取仓库信息的只读服务。所有 git 调用都经过 {@link GitRunner}。
 */
public final class GitService {

    /**
     * §3.4.7 的 git log 选项。在设计文档的基础上加了几项，让输出不受用户 Git 配置影响：
     * {@code --root}（{@code log.showRoot=false} 会隐藏根提交的文件变更）、
     * {@code --no-textconv}（textconv 会运行外部程序并改变增删行统计）、
     * {@code --no-ext-diff}（外部差异程序），{@code --no-color}、{@code --no-show-signature}（签名校验信息会混进输出）。
     * 上面的 {@link GitRunner#PINNED_CONFIG} 与 {@code GIT_NO_REPLACE_OBJECTS} 也固定在每条命令上。
     */
    static final List<String> LOG_OPTIONS = List.of(
            GitLogParser.FORMAT,
            "--raw", "--numstat", "-M", "-z", "--diff-merges=first-parent",
            "--root", "--no-textconv", "--no-ext-diff", "--no-color", "--no-show-signature");

    private final GitRunner runner;

    /** 从 PATH 查找 git，超时 60 秒。 */
    public GitService() {
        this(new GitRunner());
    }

    public GitService(GitRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** 当前 HEAD 的完整哈希，以及它所在的分支（分离头指针时 ref 为 null）。 */
    public HeadInfo readHead(Path repo) throws GitException {
        return readHead(repo, new CancellationToken());
    }

    /** 同 {@link #readHead(Path)}，可取消。 */
    public HeadInfo readHead(Path repo, CancellationToken token) throws GitException {
        // 末尾的 "--" 防止工作区里恰好有名为 HEAD 的文件时出现歧义；rev-parse 会把它原样输出成第三行
        GitResult<String> result = runner.runText(repo,
                GitCommand.of("rev-parse", "HEAD", "--symbolic-full-name", "HEAD", "--"), token).requireSuccess();
        String[] lines = result.stdout().strip().split("\\R");
        if (lines.length < 2) {
            throw GitException.outputUnreadable(result.commandLine(),
                    new GitOutputParseException("rev-parse 输出不完整：" + result.stdout()), result.stderr());
        }
        String hash = lines[0].strip();
        String ref = lines[1].strip();
        return new HeadInfo(hash, ref.equals("HEAD") ? null : ref);
    }

    /** 从 HEAD 读取全部提交，顺序与 git log 一致。 */
    public List<CommitRecord> readLog(Path repo) throws GitException {
        return readLog(repo, "HEAD", commit -> { }, new CancellationToken());
    }

    /**
     * 读取 {@code revisions} 可达的提交。传入具体哈希可以锁定一份快照：即使之后 HEAD 前进，结果也不变。
     * 增量构建时为 {@code 旧head..HEAD}。
     *
     * @param onCommit 每解析出一个提交就在调用线程上回调一次，供显示进度
     * @param token    取消后本次读取以 {@link GitException.Kind#CANCELLED} 失败
     */
    public List<CommitRecord> readLog(Path repo, String revisions, Consumer<CommitRecord> onCommit,
                                      CancellationToken token) throws GitException {
        return readLogWithWarnings(repo, revisions, onCommit, token).commits();
    }

    /** 读取结果，连同 git 在读取过程中给出的提示（例如重命名检测因文件过多被跳过）。 */
    public record LogResult(List<CommitRecord> commits, List<String> warnings) {

        public LogResult {
            commits = List.copyOf(commits);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * 同 {@link #readLog}，但保留 git 的提示。提示写在 stderr 里：不翻译的话，大提交的重命名被跳过时
     * 界面上的增删行数会偏大，用户却没有任何说明。
     */
    public LogResult readLogWithWarnings(Path repo, String revisions, Consumer<CommitRecord> onCommit,
                                         CancellationToken token) throws GitException {
        Objects.requireNonNull(revisions, "revisions");
        if (revisions.isEmpty() || revisions.startsWith("-")) {
            throw new IllegalArgumentException("无效的版本范围: " + revisions);
        }
        List<String> args = new ArrayList<>(LOG_OPTIONS.size() + 2);
        args.add(revisions);
        args.addAll(LOG_OPTIONS);
        args.add("--");

        List<CommitRecord> commits = new ArrayList<>();
        GitResult<Integer> result = runner.run(repo, GitCommand.of("log", args),
                stdout -> GitLogParser.parse(stdout, commit -> {
                    commits.add(commit);
                    onCommit.accept(commit);
                }), token).requireSuccess();
        return new LogResult(commits, warningsFrom(result.stderr()));
    }

    /**
     * 把 git 的提示翻译成面向用户的中文。每个提交都可能重复输出同一条提示，所以只保留一次；
     * 没有识别的提示原样保留，前面标明来源。
     */
    static List<String> warningsFrom(String stderr) {
        Set<String> warnings = new LinkedHashSet<>();
        for (String line : stderr.split("\\R")) {
            String text = line.strip().replaceFirst("^warning:\\s*", "");
            if (text.isEmpty()) {
                continue;
            }
            if (text.contains("rename detection was skipped")) {
                warnings.add("文件太多，git 跳过了重命名检测：重命名的文件会显示为“删除 + 新增”，增删行数可能偏大。");
            } else if (!text.contains("diff.renameLimit")) { // 同一件事的第二行，已由上面的提示说明
                warnings.add("git 提示：" + text);
            }
        }
        return List.copyOf(warnings);
    }
}
