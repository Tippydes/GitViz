package edu.pbl.gitviz.service;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.service.git.GitException;
import edu.pbl.gitviz.service.git.GitRunner;
import edu.pbl.gitviz.service.git.GitService;
import edu.pbl.gitviz.testutil.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryLoaderTest {

    @TempDir
    Path temp;

    @Test
    void sortsByAuthorTimeNewestFirstEvenWhenGitLogOrderDiffers() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String later = repo.at("2026-11-02T10:00:00+08:00").write("a.txt", "1\n").commit("作者时间较晚的父提交");
        String earlier = repo.at("2026-11-01T10:00:00+08:00").write("a.txt", "2\n").commit("作者时间较早的子提交");

        List<String> gitLogOrder = hashes(repo.gitService().readLog(repo.root()));
        List<String> displayOrder = hashes(new RepositoryLoader(repo.gitService()).load(repo.root()).commits());

        assertEquals(List.of(earlier, later), gitLogOrder);
        assertEquals(List.of(later, earlier), displayOrder);
    }

    @Test
    void keepsGitLogOrderForCommitsWithTheSameAuthorTime() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String first = repo.at("2026-11-01T10:00:00+08:00").write("a.txt", "1\n").commit("一");
        String second = repo.at("2026-11-01T10:00:00+08:00").write("a.txt", "2\n").commit("二");
        String third = repo.at("2026-11-01T10:00:00+08:00").write("a.txt", "3\n").commit("三");

        RepositorySession session = new RepositoryLoader(repo.gitService()).load(repo.root());

        assertEquals(List.of(third, second, first), hashes(session.commits()));
    }

    @Test
    void buildsASessionWithHeadAndAHashIndex() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String first = repo.write("a.txt", "1\n").commit("一");
        String second = repo.write("a.txt", "2\n").commit("二");

        RepositorySession session = new RepositoryLoader(repo.gitService()).load(repo.root());

        assertEquals(repo.root().toAbsolutePath().normalize(), session.repoRoot());
        assertEquals("repo", session.displayName());
        assertEquals(second, session.head().hash());
        assertEquals(2, session.commitCount());
        assertSame(session.commits().get(1), session.findCommit(first).orElseThrow());
        assertTrue(session.findCommit("f".repeat(40)).isEmpty());
    }

    @Test
    void openingARepositoryLeavesItExactlyAsItWas() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        repo.write("a.txt", "a\n").write(".gitignore", "*.log\n").commit("初始化");
        repo.write("a.txt", "未提交的改动\n").write("新文件.txt", "未跟踪\n").write("debug.log", "被忽略\n");

        String statusBefore = repo.git("status", "--porcelain", "--ignored");
        Map<String, String> filesBefore = snapshot(repo.root());
        new RepositoryLoader(repo.gitService()).load(repo.root());
        Map<String, String> filesAfter = snapshot(repo.root());
        String statusAfter = repo.git("status", "--porcelain", "--ignored");

        assertEquals(filesBefore, filesAfter, "仓库目录（包括 .git）中的文件不能有任何新增或修改");
        assertEquals(statusBefore, statusAfter);
    }

    @Test
    void theSessionsHeadAndCommitListComeFromTheSameSnapshot() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        String first = repo.write("a.txt", "1\n").commit("第一次");
        RepositoryLoader loader = new RepositoryLoader(repo.gitService());
        RepositorySession session = loader.load(repo.root());
        repo.write("a.txt", "2\n").commit("加载之后又有人提交");

        // 同一次加载内部：HEAD 与列表都来自 readHead 得到的哈希，而不是各自再去读 HEAD
        assertEquals(first, session.head().hash());
        assertEquals(List.of(first), hashes(session.commits()));
    }

    @Test
    void preEpochCommitsHaveUnknownTimesSortLastAndTheWholeRepositoryStillOpens() throws GitException {
        // 实测 git 2.56：所有 1970 年以前的提交都输出空的 %at/%ai，所以两条都是"未知"
        TestRepo repo = TestRepo.init(temp);
        String normal = repo.at("2026-11-01T10:00:00+08:00").write("a.txt", "1\n").commit("正常提交");
        String first = repo.importCommit("张三", "zhang@example.com", -315619200L, "+0800", "1960 年的提交",
                "b.txt", "2\n");
        // HEAD 本身就是 1969 年的提交：打开仓库时 HEAD 的时间未知也不能有问题
        String second = repo.importCommit("李四", "li@example.com", -1L, "+0000", "1969 年的提交",
                "c.txt", "3\n");

        RepositorySession session = new RepositoryLoader(repo.gitService()).load(repo.root());

        // 有时间的排在前面；时间未知的排在最后，保持 git log 的顺序（HEAD 在前）
        assertEquals(List.of(normal, second, first), hashes(session.commits()));
        assertEquals(second, session.head().hash());
        assertEquals(TimeDisplay.UNKNOWN, TimeDisplay.defaults().format(session.commits().get(1)));
        assertEquals(TimeDisplay.UNKNOWN, TimeDisplay.defaults().format(session.commits().get(2)));
        assertEquals(3, session.commitCount(), "时间未知的提交不能让整个仓库打不开");
    }

    @Test
    void openingAFolderThatIsNotARepositoryFailsWithAGitError() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("普通文件夹"));
        TestRepo isolated = TestRepo.init(temp.resolve("isolation"));
        // 不让 git 越过临时目录向上查找：上级目录恰好是某个仓库时，这个文件夹会被当成它的子目录
        Map<String, String> environment = new HashMap<>(isolated.isolatedEnvironment());
        environment.put("GIT_CEILING_DIRECTORIES", temp.toString());
        GitService git = new GitService(new GitRunner("git", GitRunner.DEFAULT_TIMEOUT, environment));

        GitException error = assertThrows(GitException.class, () -> new RepositoryLoader(git).load(folder));

        assertEquals(GitException.Kind.NON_ZERO_EXIT, error.kind());
    }

    private static List<String> hashes(List<CommitRecord> commits) {
        return commits.stream().map(CommitRecord::hash).toList();
    }

    /** 相对路径 → 大小、修改时间、内容摘要，覆盖 .git 在内的全部文件。 */
    private static Map<String, String> snapshot(Path root) {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                try {
                    files.put(root.relativize(file).toString(), Files.size(file) + " "
                            + Files.getLastModifiedTime(file).toMillis() + " " + sha256(Files.readAllBytes(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
