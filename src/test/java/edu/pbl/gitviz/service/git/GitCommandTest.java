package edu.pbl.gitviz.service.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitCommandTest {

    @Test
    void allowsTheReadOnlyWhitelist() {
        assertDoesNotThrow(() -> GitCommand.of("--version"));
        assertDoesNotThrow(() -> GitCommand.of("rev-parse", "--show-toplevel"));
        assertDoesNotThrow(() -> GitCommand.of("log", "HEAD", "--format=%H"));
        assertDoesNotThrow(() -> GitCommand.of("show", "-s", "HEAD"));
        assertDoesNotThrow(() -> GitCommand.of("merge-base", "--is-ancestor", "a1b2c3d", "HEAD"));
        assertDoesNotThrow(() -> GitCommand.of("rev-list", "--count", "HEAD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "push", "pull", "fetch", "reset", "checkout", "switch", "merge", "rebase",
            "config", "gc", "clean", "branch", "tag", "update-ref", "update-index", "status", "stash", "am"})
    void rejectsSubcommandsOutsideTheWhitelist(String subcommand) {
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of(subcommand));
    }

    @Test
    void mergeBaseIsOnlyAllowedAsIsAncestor() {
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of("merge-base", "a1b2c3d", "HEAD"));
    }

    @Test
    void revListIsOnlyAllowedWithCount() {
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of("rev-list", "HEAD"));
    }

    @Test
    void versionTakesNoArguments() {
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of("--version", "--build-options"));
    }

    @Test
    void rejectsOptionsThatWriteFilesOrRunExternalPrograms() {
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of("log", "HEAD", "--output=out.txt"));
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of("show", "HEAD", "--output", "out.txt"));
        assertThrows(IllegalArgumentException.class, () -> GitCommand.of("show", "HEAD", "--ext-diff"));
    }

    @Test
    void argumentsAfterTheSeparatorArePathsNotOptions() {
        GitCommand command = GitCommand.of("show", "HEAD", "--", "--output=名字奇怪的文件.txt");
        assertEquals(List.of("show", "HEAD", "--", "--output=名字奇怪的文件.txt"), command.toArgumentList());
    }
}
