package edu.pbl.gitviz.service.git;

import edu.pbl.gitviz.testutil.FakeGit;
import edu.pbl.gitviz.testutil.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRunnerTest {

    @TempDir
    Path temp;

    @Test
    void passesTheRepositoryPathAsASeparateArgumentAndForcesUtf8Output() {
        GitRunner runner = new GitRunner("git", Duration.ofSeconds(5), Map.of());
        Path repo = temp.resolve("含 空格 的 目录");

        List<String> commandLine = runner.commandLine(repo, GitCommand.of("log", "HEAD"));

        assertEquals(List.of("git", "-C", repo.toString(), "-c", "i18n.logOutputEncoding=UTF-8", "log", "HEAD"),
                commandLine);
    }

    @Test
    void omitsTheWorkingDirectoryWhenNoneIsGiven() {
        GitRunner runner = new GitRunner("git", Duration.ofSeconds(5), Map.of());

        assertEquals(List.of("git", "-c", "i18n.logOutputEncoding=UTF-8", "--version"),
                runner.commandLine(null, GitCommand.of("--version")));
    }

    @Test
    void killsTheProcessTreeAndFailsWhenTheTimeoutIsExceeded() {
        Path fakeGit = FakeGit.sleeping(temp, 30);
        GitRunner runner = new GitRunner(fakeGit.toString(), Duration.ofMillis(500), Map.of());

        long start = System.nanoTime();
        GitException error = assertThrows(GitException.class,
                () -> runner.runText(null, GitCommand.of("--version")));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(GitException.Kind.TIMED_OUT, error.kind());
        assertEquals(fakeGit.toString(), error.commandLine().get(0));
        // 子进程（ping / sleep）如果没被一起结束，会一直占着输出管道，读取要等满 30 秒
        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, "应在超时后立即返回，实际用时 " + elapsed);
    }

    @Test
    void reportsAMissingExecutableAsStartFailure() {
        GitRunner runner = new GitRunner(temp.resolve("no-such-git.exe").toString(), Duration.ofSeconds(5), Map.of());

        GitException error = assertThrows(GitException.class,
                () -> runner.runText(null, GitCommand.of("--version")));

        assertEquals(GitException.Kind.START_FAILED, error.kind());
        assertEquals(-1, error.exitCode());
    }

    @Test
    void keepsCommandExitCodeAndStderrWhenGitFails() throws GitException {
        TestRepo repo = TestRepo.init(temp);
        repo.write("a.txt", "a\n").commit("初始化");
        GitRunner runner = new GitRunner("git", GitRunner.DEFAULT_TIMEOUT, repo.isolatedEnvironment());

        GitResult<String> result = runner.runText(repo.root(),
                GitCommand.of("rev-parse", "--verify", "no-such-branch"));

        assertFalse(result.succeeded());
        GitException error = assertThrows(GitException.class, result::requireSuccess);
        assertEquals(GitException.Kind.NON_ZERO_EXIT, error.kind());
        assertNotEquals(0, error.exitCode());
        assertEquals(result.exitCode(), error.exitCode());
        assertTrue(error.stderr().contains("fatal"), error.stderr());
        assertTrue(error.commandLine().contains("rev-parse"));
        assertTrue(error.technicalDetails().contains("rev-parse --verify no-such-branch"), error.technicalDetails());
        assertTrue(error.technicalDetails().contains(Integer.toString(error.exitCode())));
    }

    @Test
    void reportsAReaderFailureAsUnreadableOutput() {
        TestRepo repo = TestRepo.init(temp);
        repo.write("a.txt", "a\n").commit("初始化");
        GitRunner runner = new GitRunner("git", GitRunner.DEFAULT_TIMEOUT, repo.isolatedEnvironment());

        GitException error = assertThrows(GitException.class, () -> runner.run(repo.root(),
                GitCommand.of("rev-parse", "HEAD"), stdout -> {
                    throw new GitOutputParseException("格式不对");
                }));

        assertEquals(GitException.Kind.OUTPUT_UNREADABLE, error.kind());
        assertTrue(error.getCause() instanceof GitOutputParseException);
    }
}
