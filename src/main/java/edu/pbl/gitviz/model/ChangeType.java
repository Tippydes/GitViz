package edu.pbl.gitviz.model;

/**
 * 文件变更类型（§3.4.8 file_changes.csv 的 change_type）。
 *
 * <p>常量名就是持久化时写入的字母，与 git {@code --raw} 输出的状态字母一致。
 */
public enum ChangeType {
    A("新增"),
    M("修改"),
    D("删除"),
    R("重命名"),
    C("复制"),
    T("类型变化");

    private final String label;

    ChangeType(String label) {
        this.label = label;
    }

    /** 界面上显示的中文名称。 */
    public String label() {
        return label;
    }

    /** 重命名与复制带有旧路径。 */
    public boolean hasOldPath() {
        return this == R || this == C;
    }

    /**
     * 解析 {@code --raw} 的状态字段，如 {@code M}、{@code R100}、{@code C050}；相似度分数不保存。
     *
     * @throws IllegalArgumentException 状态字母不在 A/M/D/R/C/T 之内
     */
    public static ChangeType fromRawStatus(String status) {
        if (status == null || status.isEmpty()) {
            throw new IllegalArgumentException("空的变更状态");
        }
        return switch (status.charAt(0)) {
            case 'A' -> A;
            case 'M' -> M;
            case 'D' -> D;
            case 'R' -> R;
            case 'C' -> C;
            case 'T' -> T;
            default -> throw new IllegalArgumentException("未知的变更状态: " + status);
        };
    }
}
