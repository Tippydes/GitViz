package edu.pbl.gitviz.service.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条经过只读白名单校验的 git 命令（§1.2"只读与安全"）。
 *
 * <p>{@link GitRunner} 只接受这个类型，所以程序在代码层面无法执行白名单之外的 git 子命令。
 * 白名单：{@code --version}、{@code rev-parse}、{@code log}、{@code show}、
 * {@code merge-base --is-ancestor}、{@code rev-list --count}。
 */
public final class GitCommand {

    private static final Set<String> READ_ONLY_SUBCOMMANDS =
            Set.of("--version", "rev-parse", "log", "show", "merge-base", "rev-list");

    private final String subcommand;
    private final List<String> args;

    private GitCommand(String subcommand, List<String> args) {
        this.subcommand = subcommand;
        this.args = args;
    }

    public static GitCommand of(String subcommand, String... args) {
        return of(subcommand, List.of(args));
    }

    /**
     * @throws IllegalArgumentException 子命令不在白名单内，或参数会让只读命令写文件、运行外部程序
     */
    public static GitCommand of(String subcommand, List<String> args) {
        Objects.requireNonNull(subcommand, "subcommand");
        List<String> copy = List.copyOf(args);
        if (!READ_ONLY_SUBCOMMANDS.contains(subcommand)) {
            throw new IllegalArgumentException("git " + subcommand + " 不在只读白名单内");
        }
        List<String> options = optionsBeforeSeparator(copy);
        switch (subcommand) {
            case "--version" -> {
                if (!copy.isEmpty()) {
                    throw new IllegalArgumentException("git --version 不接受参数");
                }
            }
            case "merge-base" -> {
                if (!options.contains("--is-ancestor")) {
                    throw new IllegalArgumentException("merge-base 只允许 --is-ancestor 形式");
                }
            }
            case "rev-list" -> {
                if (!options.contains("--count")) {
                    throw new IllegalArgumentException("rev-list 只允许 --count 形式");
                }
            }
            default -> {
                // rev-parse、log、show：只做下面的通用检查
            }
        }
        for (String option : options) {
            if (option.equals("--output") || option.startsWith("--output=")) {
                throw new IllegalArgumentException("--output 会写文件，不允许使用");
            }
            if (option.equals("--ext-diff")) {
                throw new IllegalArgumentException("--ext-diff 会运行外部程序，不允许使用");
            }
        }
        return new GitCommand(subcommand, copy);
    }

    /** {@code --} 之后是路径，不会被 git 当作选项。 */
    private static List<String> optionsBeforeSeparator(List<String> args) {
        int separator = args.indexOf("--");
        return separator < 0 ? args : args.subList(0, separator);
    }

    public String subcommand() {
        return subcommand;
    }

    public List<String> args() {
        return args;
    }

    /** 子命令及其参数，不含 git 可执行文件和全局选项。 */
    List<String> toArgumentList() {
        List<String> list = new ArrayList<>(args.size() + 1);
        list.add(subcommand);
        list.addAll(args);
        return list;
    }

    @Override
    public String toString() {
        return "git " + String.join(" ", toArgumentList());
    }
}
