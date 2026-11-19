package edu.pbl.gitviz.service.git;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.HeadInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 读取仓库信息的只读服务。所有 git 调用都经过 {@link GitRunner}。
 */
public final class GitService {

    /**
     * §3.4.7 的 git log 选项。在设计文档的基础上加了几项，让输出不受用户 Git 配置影响：
     * {@code --root}（{@code log.showRoot=false} 会隐藏根提交的文件变更）、
     * {@code --no-textconv}（textconv 会运行外部程序并改变增删行统计）、
     * {@code --no-color}、{@code --no-show-signature}（签名校验信息会混进输出）。
     */
    static final List<String> LOG_OPTIONS = List.of(
            GitLogParser.FORMAT,
            "--raw", "--numstat", "-M", "-z", "--diff-merges=first-parent",
            "--root", "--no-textconv", "--no-color", "--no-show-signature");

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
        // 末尾的 "--" 防止工作区里恰好有名为 HEAD 的文件时出现歧义；rev-parse 会把它原样输出成第三行
        GitResult<String> result = runner.runText(repo,
                GitCommand.of("rev-parse", "HEAD", "--symbolic-full-name", "HEAD", "--")).requireSuccess();
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
        return readLog(repo, "HEAD", commit -> { });
    }

    /**
     * 读取 {@code revisions} 可达的提交（如 {@code HEAD}，增量构建时为 {@code 旧head..HEAD}）。
     *
     * @param onCommit 每解析出一个提交就在调用线程上回调一次，供显示进度
     */
    public List<CommitRecord> readLog(Path repo, String revisions, Consumer<CommitRecord> onCommit)
            throws GitException {
        Objects.requireNonNull(revisions, "revisions");
        if (revisions.isEmpty() || revisions.startsWith("-")) {
            throw new IllegalArgumentException("无效的版本范围: " + revisions);
        }
        List<String> args = new ArrayList<>(LOG_OPTIONS.size() + 2);
        args.add(revisions);
        args.addAll(LOG_OPTIONS);
        args.add("--");

        List<CommitRecord> commits = new ArrayList<>();
        runner.run(repo, GitCommand.of("log", args), stdout -> GitLogParser.parse(stdout, commit -> {
            commits.add(commit);
            onCommit.accept(commit);
        })).requireSuccess();
        return List.copyOf(commits);
    }
}
