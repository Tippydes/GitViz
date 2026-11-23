package edu.pbl.gitviz.service.git;

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
        assertEquals(OffsetDateTime.parse("2026-10-01T09:00:00+08:00").toEpochSecond(), commit.authorTime());
        assertEquals(480, commit.tzOffsetMinutes());
        assertEquals(List.of(new FileChange("README.md", null, ChangeType.A, 2, 0, false)), commit.fileChanges());
    }

    @Test
    void parsesModifiedBinaryAndChineseNamedFilesInOneCommit() {
        CommitRecord commit = commits.get(second);

        assertEquals("Li Si", commit.authorName());
        assertEquals(-330, commit.tzOffsetMinutes());
        assertEquals(OffsetDateTime.parse("2026-10-02T10:30:00-05:30").toEpochSecond(), commit.authorTime());
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

    @Test
    void theUsersPersonalGitConfigDoesNotChangeTheResult() throws GitException, IOException {
        TestRepo repo = TestRepo.init(temp);
        repo.author("张三", "zhang@example.com").write("说明.txt", "一\n二\n").commit("初始化");
        repo.writeBytes("bin.dat", new byte[]{0, 1, 2, 3}).commit("二进制");
        repo.rename("说明.txt", "文档/说明.txt").commit("重命名");
        repo.branch("side").checkout("side").write("side.txt", "s\n").commit("分支");
        repo.checkout("main").write("main.txt", "m\n").commit("主干");
        repo.merge("side", "合并");
        List<CommitRecord> clean = repo.gitService().readLog(repo.root());

        Files.writeString(repo.globalConfig(), String.join("\n",
                "[color]", "  ui = always",
                "[log]", "  showSignature = true", "  showRoot = false", "  decorate = full", "  abbrevCommit = true",
                "[diff]", "  noprefix = true", "  mnemonicPrefix = true", "  renames = false", "  relative = true",
                "  external = no-such-diff-tool",
                "[core]", "  quotePath = true",
                "[i18n]", "  logOutputEncoding = GBK",
                "[format]", "  pretty = oneline", ""), StandardCharsets.UTF_8);
        List<CommitRecord> withPersonalConfig = repo.gitService().readLog(repo.root());

        assertEquals(clean, withPersonalConfig);
        assertEquals(1, withPersonalConfig.get(withPersonalConfig.size() - 1).filesChanged(),
                "log.showRoot=false 时根提交的文件变更也要读出来");
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
