package edu.pbl.gitviz.service;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.HeadInfo;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个已打开仓库的会话（§2.3.3）：每打开一次仓库创建一个，所有子页共享同一份数据。
 *
 * <p>不可变：在后台线程构建完成后交给 JavaFX 应用线程使用，不需要同步。
 * 后续 ticket 会在这里加入仓库记录、书签、筛选方案和缓存状态。
 */
public final class RepositorySession {

    private final Path repoRoot;
    private final HeadInfo head;
    private final List<CommitRecord> commits;
    private final Map<String, CommitRecord> commitsByHash;
    private final List<String> warnings;

    /**
     * @param commits 已按显示顺序（作者时间倒序）排好的提交
     */
    public RepositorySession(Path repoRoot, HeadInfo head, List<CommitRecord> commits) {
        this(repoRoot, head, commits, List.of());
    }

    /**
     * @param warnings 读取过程中 git 给出的提示，面向用户（例如重命名检测被跳过）
     */
    public RepositorySession(Path repoRoot, HeadInfo head, List<CommitRecord> commits, List<String> warnings) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.head = Objects.requireNonNull(head, "head");
        this.commits = List.copyOf(commits);
        Map<String, CommitRecord> index = new LinkedHashMap<>();
        for (CommitRecord commit : this.commits) {
            index.put(commit.hash(), commit);
        }
        this.commitsByHash = index;
        this.warnings = List.copyOf(warnings);
    }

    /** 读取过程中的提示，没有时为空列表。 */
    public List<String> warnings() {
        return warnings;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public HeadInfo head() {
        return head;
    }

    /** 全部提交，作者时间倒序。 */
    public List<CommitRecord> commits() {
        return commits;
    }

    public int commitCount() {
        return commits.size();
    }

    public Optional<CommitRecord> findCommit(String hash) {
        return Optional.ofNullable(commitsByHash.get(hash));
    }

    /** 仓库文件夹名，用于窗口标题。 */
    public String displayName() {
        Path name = repoRoot.getFileName();
        return name == null ? repoRoot.toString() : name.toString();
    }
}
