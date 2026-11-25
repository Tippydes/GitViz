package edu.pbl.gitviz.model;

import java.util.Objects;

/**
 * 一次提交中一个文件的变更（§3.4.8 file_changes.csv 的一行，不含 commit_hash）。
 *
 * @param path       变更后的路径，相对仓库根目录、以 / 分隔；删除时为原路径
 * @param oldPath    变更前的路径，仅重命名和复制时非 null
 * @param changeType 变更类型
 * @param additions  新增行数，二进制文件为 0
 * @param deletions  删除行数，二进制文件为 0
 * @param binary     是否二进制（numstat 输出 {@code -}）
 */
public record FileChange(String path, String oldPath, ChangeType changeType,
                         int additions, int deletions, boolean binary) {

    public FileChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(changeType, "changeType");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (changeType.hasOldPath() != (oldPath != null)) {
            throw new IllegalArgumentException("只有重命名和复制才有 oldPath: " + changeType + " " + path);
        }
        if (additions < 0 || deletions < 0) {
            throw new IllegalArgumentException("增删行数不能为负: " + path);
        }
        if (binary && (additions != 0 || deletions != 0)) {
            throw new IllegalArgumentException("二进制文件的增删行必须为 0: " + path);
        }
    }
}
