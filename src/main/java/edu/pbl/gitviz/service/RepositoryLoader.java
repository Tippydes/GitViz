package edu.pbl.gitviz.service;

import edu.pbl.gitviz.model.AuthorTime;
import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.HeadInfo;
import edu.pbl.gitviz.service.git.CancellationToken;
import edu.pbl.gitviz.service.git.GitException;
import edu.pbl.gitviz.service.git.GitService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "打开仓库"流程（§2.3.2）。本 ticket 只做最窄的主线：读 HEAD → 读全部提交 → 排序 → 构建会话。
 * 校验链、进度、缓存、仓库记录由后续 ticket 加入。
 *
 * <p>在后台线程调用；本类不接触任何界面对象。
 */
public final class RepositoryLoader {

    /**
     * 作者时间倒序。时间未知的提交排在最后。时间相同（或都未知）时返回 0，由 {@link List#sort} 的稳定排序
     * 保持 git log 的原有顺序。
     */
    public static final Comparator<CommitRecord> NEWEST_FIRST = (a, b) -> {
        Optional<AuthorTime> ta = a.authorTime();
        Optional<AuthorTime> tb = b.authorTime();
        if (ta.isPresent() && tb.isPresent()) {
            return Long.compare(tb.get().epochSecond(), ta.get().epochSecond());
        }
        if (ta.isPresent()) {
            return -1;
        }
        if (tb.isPresent()) {
            return 1;
        }
        return 0;
    };

    private final GitService git;

    public RepositoryLoader(GitService git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    public RepositorySession load(Path repoPath) throws GitException {
        return load(repoPath, new CancellationToken());
    }

    /**
     * 读取 HEAD，再按 HEAD 的哈希读取提交。两次读取锁定在同一个提交上：读取期间即使有人提交或切换分支，
     * 会话里的 HEAD 与提交列表也一致。
     */
    public RepositorySession load(Path repoPath, CancellationToken token) throws GitException {
        Path root = repoPath.toAbsolutePath().normalize();
        HeadInfo head = git.readHead(root, token);
        List<CommitRecord> commits = new ArrayList<>(
                git.readLog(root, head.hash(), commit -> { }, token));
        commits.sort(NEWEST_FIRST);
        return new RepositorySession(root, head, commits);
    }
}
