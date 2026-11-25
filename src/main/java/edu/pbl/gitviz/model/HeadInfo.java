package edu.pbl.gitviz.model;

import java.util.Objects;

/**
 * 当前 HEAD 的状态。
 *
 * @param hash 当前 HEAD 指向的提交的完整哈希
 * @param ref  HEAD 所在的引用，如 {@code refs/heads/main}；处于分离头指针状态时为 null
 */
public record HeadInfo(String hash, String ref) {

    private static final String BRANCH_PREFIX = "refs/heads/";

    public HeadInfo {
        Objects.requireNonNull(hash, "hash");
    }

    /** HEAD 没有指向任何分支，而是直接指向某个提交。 */
    public boolean detached() {
        return ref == null;
    }

    /** 分支名，如 {@code main}；分离头指针时为 null。 */
    public String branchName() {
        if (ref == null) {
            return null;
        }
        return ref.startsWith(BRANCH_PREFIX) ? ref.substring(BRANCH_PREFIX.length()) : ref;
    }

    public String shortHash() {
        return hash.length() > 7 ? hash.substring(0, 7) : hash;
    }
}
