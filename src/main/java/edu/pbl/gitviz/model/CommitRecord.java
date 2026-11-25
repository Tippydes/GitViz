package edu.pbl.gitviz.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 一次提交的元数据与文件变更，不可变（§3.4.7 commits.csv 的一行，外加该提交的文件变更）。
 *
 * <p>界面上的"提交时间"指作者时间 {@code authorTime}（§3.4.7）。
 *
 * @param hash         完整哈希，40 位（SHA-1）或 64 位（SHA-256）小写十六进制
 * @param parentHashes 父提交哈希；根提交为空，两个及以上为合并提交
 * @param authorName   作者名（已应用 .mailmap）
 * @param authorEmail  作者邮箱（已应用 .mailmap，保留原始大小写，可能为空串）
 * @param authorTime   作者时间；git 无法给出时间时为 empty（如 1970 年以前的提交），界面显示为"未知"，
 *                     排序与筛选的规则见 §3.4.7
 * @param subject      提交说明首行，可能为空串
 * @param fileChanges  文件变更；合并提交为相对第一父提交的变更，空提交为空列表
 */
public record CommitRecord(String hash, List<String> parentHashes, String authorName, String authorEmail,
                           Optional<AuthorTime> authorTime, String subject, List<FileChange> fileChanges) {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    public CommitRecord {
        requireHash(hash, "hash");
        parentHashes = List.copyOf(parentHashes);
        parentHashes.forEach(p -> requireHash(p, "parentHash"));
        Objects.requireNonNull(authorName, "authorName");
        Objects.requireNonNull(authorEmail, "authorEmail");
        Objects.requireNonNull(authorTime, "authorTime");
        Objects.requireNonNull(subject, "subject");
        fileChanges = List.copyOf(fileChanges);
    }

    private static void requireHash(String value, String name) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " 不是有效的提交哈希: " + value);
        }
    }

    /** 列表中显示的 7 位短哈希。 */
    public String shortHash() {
        return hash.substring(0, 7);
    }

    public boolean isMerge() {
        return parentHashes.size() >= 2;
    }

    public boolean isRoot() {
        return parentHashes.isEmpty();
    }

    /** 变更文件数（含二进制文件）。 */
    public int filesChanged() {
        return fileChanges.size();
    }

    /** 新增行数，不含二进制文件。 */
    public int additions() {
        int sum = 0;
        for (FileChange change : fileChanges) {
            sum += change.additions();
        }
        return sum;
    }

    /** 删除行数，不含二进制文件。 */
    public int deletions() {
        int sum = 0;
        for (FileChange change : fileChanges) {
            sum += change.deletions();
        }
        return sum;
    }
}
