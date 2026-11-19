package edu.pbl.gitviz.service;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.HeadInfo;
import edu.pbl.gitviz.service.git.GitException;
import edu.pbl.gitviz.service.git.GitService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * "打开仓库"流程（§2.3.2）。本 ticket 只做最窄的主线：读 HEAD → 读全部提交 → 排序 → 构建会话。
 * 校验链、进度、缓存、仓库记录由后续 ticket 加入。
 *
 * <p>在后台线程调用；本类不接触任何界面对象。
 */
public final class RepositoryLoader {

    /** 作者时间倒序；时间相同时保持 git log 的原有顺序（{@link List#sort} 是稳定排序）。 */
    public static final Comparator<CommitRecord> NEWEST_FIRST =
            Comparator.comparingLong(CommitRecord::authorTime).reversed();

    private final GitService git;

    public RepositoryLoader(GitService git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    public RepositorySession load(Path repoPath) throws GitException {
        Path root = repoPath.toAbsolutePath().normalize();
        HeadInfo head = git.readHead(root);
        List<CommitRecord> commits = new ArrayList<>(git.readLog(root));
        commits.sort(NEWEST_FIRST);
        return new RepositorySession(root, head, commits);
    }
}
