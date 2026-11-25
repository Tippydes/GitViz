package edu.pbl.gitviz.service.git;

import edu.pbl.gitviz.model.AuthorTime;
import edu.pbl.gitviz.model.ChangeType;
import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.FileChange;
import edu.pbl.gitviz.model.HeadInfo;
import edu.pbl.gitviz.testutil.TestRepo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 git 现场造的仓库验证读取与解析。
 */
class GitServiceTest {

    @TempDir
    static Path sharedTemp;

    @TempDir
    Path temp;

    private static TestRepo scenario;
    private static String root;
    private static String second;
    private static String renamed;
    private static String empty;
    private static String feature;
    private static String mainSide;
    private static String merge;
    private static Map<String, CommitRecord> commits;
    private static List<CommitRecord> logOrder;

    /**
     * 多位作者、中文提交说明（含逗号和双引号）、根提交、二进制文件、中文文件名、重命名、空提交、合并提交。
     */
    @BeforeAll
    static void buildScenario() throws GitException {
        scenario = TestRepo.init(sharedTemp);
        root = scenario.author("张三", "zhang@example.com").at("2026-10-01T09:00:00+08:00")
                .write("README.md", "# 项目\n第一行\n")
                .commit("初始化项目, \"第一版\"");
        second = scenario.author("Li Si", "li@example.com").at("2026-10-02T10:30:00-05:30")
                .write("README.md", "# 项目\n第一行\n第二行\n")
                .writeBytes("assets/logo.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 1, 2, 3})
                .write("文档/说明 文件.txt", "中文内容\n第二行\n")
                .commit("添加图片和中文文档");
        renamed = scenario.author("张三", "zhang@example.com").at("2026-10-03T11:00:00+08:00")
                .rename("README.md", "docs/README.md")
                .commit("移动 README 到 docs");
        empty = scenario.at("2026-10-04T12:00:00+08:00").commitEmpty("空提交：只记录一个里程碑");
        feature = scenario.branch("feature").checkout("feature")
                .author("Li Si", "li@example.com").at("2026-10-05T09:00:00+08:00")
                .write("feature.txt", "功能 1\n功能 2\n")
                .commit("feature: 新功能");
        mainSide = scenario.checkout("main")
                .author("张三", "zhang@example.com").at("2026-10-06T09:00:00+08:00")
                .write("main.txt", "主干\n")
                .commit("主干上的改动");
        merge = scenario.at("2026-10-07T09:00:00+08:00").merge("feature", "Merge branch 'feature'");

        logOrder = scenario.gitService().readLog(scenario.root());
        commits = logOrder.stream().collect(Collectors.toMap(CommitRecord::hash, Function.identity()));
    }

    @Test
    void readsEveryCommitInGitLogOrder() {
        assertEquals(List.of(merge, mainSide, feature, empty, renamed, second, root),
                logOrder.stream().map(CommitRecord::hash).toList());
    }

    @Test
    void parsesTheRootCommit() {
        CommitRecord commit = commits.get(root);

        assertTrue(commit.isRoot());
        assertEquals("初始化项目, \"第一版\"", commit.subject());
        assertEquals("张三", commit.authorName());
        assertEquals("zhang@example.com", commit.authorEmail());
        assertEquals(new AuthorTime(OffsetDateTime.parse("2026-10-01T09:00:00+08:00").toEpochSecond(), 480),
                commit.authorTime().orElseThrow());
        assertEquals(List.of(new FileChange("README.md", null, ChangeType.A, 2, 0, false)), commit.fileChanges());
    }

    @Test
    void parsesModifiedBinaryAndChineseNamedFilesInOneCommit() {
        CommitRecord commit = commits.get(second);

        assertEquals("Li Si", commit.authorName());
        assertEquals(new AuthorTime(OffsetDateTime.parse("2026-10-02T10:30:00-05:30").toEpochSecond(), -330),
                commit.authorTime().orElseThrow());
        assertEquals(List.of(root), commit.parentHashes());
        assertEquals(List.of(
                new FileChange("README.md", null, ChangeType.M, 1, 0, false),
                new FileChange("assets/logo.png", null, ChangeType.A, 0, 0, true),
                new FileChange("文档/说明 文件.txt", null, ChangeType.A, 2, 0, false)), commit.fileChanges());
        assertEquals(3, commit.filesChanged());
        assertEquals(3, commit.additions(), "二进制文件不计入增删行");
    }

    @Test
    void parsesARename() {
        assertEquals(List.of(new FileChange("docs/README.md", "README.md", ChangeType.R, 0, 0, false)),
                commits.get(renamed).fileChanges());
    }

    @Test
    void parsesAnEmptyCommit() {
        CommitRecord commit = commits.get(empty);

        assertEquals("空提交：只记录一个里程碑", commit.subject());
        assertEquals(List.of(), commit.fileChanges());
    }

    @Test
    void mergeCommitChangesAreRelativeToTheFirstParent() {
        CommitRecord commit = commits.get(merge);

        assertTrue(commit.isMerge());
        assertEquals(List.of(mainSide, feature), commit.parentHashes());
        assertEquals(List.of(new FileChange("feature.txt", null, ChangeType.A, 2, 0, false)), commit.fileChanges());
    }

    @Test
    void readsHeadOnABranch() throws GitException {
        HeadInfo head = scenario.gitService().readHead(scenario.root());

        assertEquals(merge, head.hash());
        assertEquals("refs/heads/main", head.ref());
        assertEquals("main", head.branchName());
        assertFalse(head.detached());
    }

    @Test
    void readsADetachedHead() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String first = repo.write("a.txt", "1\n").commit("第一次");
        repo.write("a.txt", "2\n").commit("第二次");
        repo.detach(first);

        HeadInfo head = repo.gitService().readHead(repo.root());

        assertEquals(first, head.hash());
        assertNull(head.ref());
        assertTrue(head.detached());
        assertNull(head.branchName());
    }

    @Test
    void aFileNamedHeadDoesNotConfuseRevisionParsing() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String commit = repo.write("HEAD", "一个名叫 HEAD 的文件\n").commit("添加 HEAD 文件");

        assertEquals(commit, repo.gitService().readHead(repo.root()).hash());
        assertEquals(1, repo.gitService().readLog(repo.root()).size());
    }

    @Test
    void appliesMailmapToAuthorNameAndEmail() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        repo.author("zhang-old", "zhang.old@example.com").write("a.txt", "a\n").commit("旧邮箱的提交");
        repo.author("张三", "zhang@example.com")
                .write(".mailmap", "张三 <zhang@example.com> <zhang.old@example.com>\n")
                .commit("添加 .mailmap");

        CommitRecord oldCommit = repo.gitService().readLog(repo.root()).get(1);

        assertEquals("张三", oldCommit.authorName());
        assertEquals("zhang@example.com", oldCommit.authorEmail());
    }

    /**
     * 用户个人的 Git 配置不能改变读取结果。每个泄漏项单独测一次，用的都是会真正改变 git 输出的配置
     * （histogram 会改变行数，relative 只在子目录里生效，等等），并且同时从仓库根目录和子目录读取：
     * 两者必须与干净环境下的结果完全相同。
     */
    @Test
    void personalGitConfigCannotChangeWhatIsRead() throws GitException, IOException {
        TestRepo repo = TestRepo.init(temp);
        Path attributes = repo.globalConfig().getParent().resolve("attributes");
        Path userMailmap = repo.globalConfig().getParent().resolve("usermailmap");
        Files.writeString(attributes, "*.txt -diff\n", StandardCharsets.UTF_8);
        Files.writeString(userMailmap, "Alice Wang <zhang@example.com>\n", StandardCharsets.UTF_8);

        String[] base = {"z", "z", "z", "x = 1;", "", "}", "x = 1;", "}", "y", "  return;", "y", "z", "y", "y", "y",
                "x = 1;", "", "", "  return;", "}", "  return;", "z", "{", "}", "", "  return;", "y", "x = 1;", "}", ""};
        String[] changed = {"z", "z", "x = 1;", "", "}", "x = 1;", "}", "{", "y", "  return;", "y", "z", "y", "y", "y",
                "x = 1;", "", "", "  return;", "}", "  return;", "z", "w", "{", "", "  return;", "y", "x = 1;", "}", ""};
        // 子模块指针只存在于索引里：不能用 commit()（它会 add -A 删掉工作区里没有的 gitlink），
        // 所以普通文件显式暂存，指针直接写入索引，再 commitStaged
        repo.author("张三", "zhang@example.com").write("a.txt", String.join("\n", base))
                .write("sub/in.txt", "in\n").write("top.txt", "t\n");
        repo.git("add", "a.txt", "sub/in.txt", "top.txt");
        repo.git("update-index", "--add", "--cacheinfo", "160000," + "1".repeat(40) + ",libx");
        repo.commitStaged("初始化");
        repo.write("a.txt", String.join("\n", changed)).write("sub/in.txt", "in 2\n").write("top.txt", "t 2\n");
        repo.git("add", "a.txt", "sub/in.txt", "top.txt");
        repo.git("update-index", "--cacheinfo", "160000," + "2".repeat(40) + ",libx");
        repo.commitStaged("改动：算法、子模块、子目录");

        Path rootPath = repo.root();
        Path subPath = repo.root().resolve("sub");
        List<CommitRecord> clean = repo.gitService().readLog(rootPath);
        assertTrue(clean.get(clean.size() - 1).filesChanged() > 0, "根提交的文件变更不能因配置消失");
        assertEquals(clean, repo.gitService().readLog(subPath), "从子目录读取应与从仓库根目录读取完全一致");

        Map<String, String> leaks = new LinkedHashMap<>();
        leaks.put("diff.relative", "[diff]\n\trelative = true\n");
        leaks.put("diff.algorithm", "[diff]\n\talgorithm = histogram\n");
        leaks.put("diff.ignoreSubmodules", "[diff]\n\tignoreSubmodules = all\n");
        leaks.put("core.attributesFile", "[core]\n\tattributesFile = " + attributes.toString().replace('\\', '/') + "\n");
        leaks.put("mailmap.file", "[mailmap]\n\tfile = " + userMailmap.toString().replace('\\', '/') + "\n");
        leaks.put("其它配置（log/diff/core/i18n）",
                "[log]\n\tshowRoot = false\n[diff]\n\trenames = false\n[core]\n\tquotePath = true\n"
                        + "[i18n]\n\tlogOutputEncoding = GBK\n");
        for (Map.Entry<String, String> leak : leaks.entrySet()) {
            Files.writeString(repo.globalConfig(), leak.getValue(), StandardCharsets.UTF_8);
            assertEquals(clean, repo.gitService().readLog(rootPath), leak.getKey() + " 改变了从仓库根目录的读取结果");
            assertEquals(clean, repo.gitService().readLog(subPath), leak.getKey() + " 改变了从子目录的读取结果");
        }
    }

    @Test
    void renamesStayRenamesInACommitWithMoreFilesThanTheRenameLimit() throws GitException {
        // 审查中怀疑：文件数超过 diff.renameLimit（默认 1000）时重命名会退化成"删除 + 新增"，增删行数偏大。
        // 实测（git 2.56）：我们使用的 -M 在 1200 个文件时仍然识别全部重命名；只有显式的 -C 才会跳过检测。
        // 这里验证用户看到的结果：仍然是 1200 个重命名，每个 +1 −1，合计 +1200 −1200。
        TestRepo repo = TestRepo.init(temp);
        String body = "共同的一行\n".repeat(20);
        for (int i = 0; i < 1200; i++) {
            repo.write("old/f" + i + ".txt", "内容 " + i + "\n" + body);
        }
        repo.commit("很多文件");
        for (int i = 0; i < 1200; i++) {
            repo.rename("old/f" + i + ".txt", "new/f" + i + ".txt");
            repo.write("new/f" + i + ".txt", "改动 " + i + "\n" + body);
        }
        repo.commit("整体搬家并修改");

        GitService.LogResult result = repo.gitService().readLogWithWarnings(repo.root(), "HEAD",
                commit -> { }, new CancellationToken());

        CommitRecord moved = result.commits().get(0);
        long renames = moved.fileChanges().stream().filter(c -> c.changeType() == ChangeType.R).count();
        assertEquals(1200, renames, "每个文件都应当是重命名");
        assertEquals(1200, moved.additions());
        assertEquals(1200, moved.deletions());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void noWarningWhenRenameDetectionRanNormally() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        repo.write("a.txt", "一\n二\n三\n").commit("初始");
        repo.rename("a.txt", "b.txt").write("b.txt", "一\n二\n三\n四\n").commit("改名并修改");

        GitService.LogResult result = repo.gitService().readLogWithWarnings(repo.root(), "HEAD",
                commit -> { }, new CancellationToken());

        assertEquals(List.of(), result.warnings());
        assertEquals(List.of(new FileChange("b.txt", "a.txt", ChangeType.R, 1, 0, false)),
                result.commits().get(0).fileChanges());
    }

    @Test
    void translatesGitWarningsAndKeepsEachOnceAndUnknownOnesVerbatim() {
        String stderr = "warning: exhaustive rename detection was skipped due to too many files.\n"
                + "warning: you may want to set your diff.renameLimit variable to at least 1200 and retry the command.\n"
                + "warning: exhaustive rename detection was skipped due to too many files.\n"
                + "warning: something new\n";

        List<String> warnings = GitService.warningsFrom(stderr);

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("重命名检测"));
        assertEquals("git 提示：something new", warnings.get(1));
        assertEquals(List.of(), GitService.warningsFrom(""));
    }

    @Test
    void readingAnEmptyRepositoryFailsWithTheGitError() {
        TestRepo repo = TestRepo.init(temp);

        GitException error = assertThrows(GitException.class, () -> repo.gitService().readLog(repo.root()));

        assertEquals(GitException.Kind.NON_ZERO_EXIT, error.kind());
        assertFalse(error.stderr().isBlank());
    }

    @Test
    void readsTheRequestedSnapshotEvenAfterHeadMovesOn() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String first = repo.write("a.txt", "1\n").commit("第一次");
        GitService git = repo.gitService();
        repo.write("a.txt", "2\n").commit("第二次：HEAD 已经前进");

        List<String> pinned = git.readLog(repo.root(), first, commit -> { }, new CancellationToken()).stream()
                .map(CommitRecord::hash).toList();
        List<String> latest = git.readLog(repo.root()).stream().map(CommitRecord::hash).toList();

        assertEquals(List.of(first), pinned, "按哈希读取，只包含读取那一刻的历史");
        assertEquals(2, latest.size(), "按 HEAD 读取，包含全部提交");
    }
}
