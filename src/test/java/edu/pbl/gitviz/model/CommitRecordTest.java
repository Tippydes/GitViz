package edu.pbl.gitviz.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitRecordTest {

    private static final String H1 = "a".repeat(40);
    private static final String H2 = "b".repeat(40);
    private static final String H3 = "c".repeat(40);

    private static CommitRecord commit(List<String> parents, List<FileChange> changes) {
        return new CommitRecord(H1, parents, "作者", "a@example.com", 1L, 480, "说明", changes);
    }

    @Test
    void lineCountsExcludeBinaryFilesButFileCountIncludesThem() {
        CommitRecord commit = commit(List.of(H2), List.of(
                new FileChange("a.txt", null, ChangeType.M, 8, 3, false),
                new FileChange("b.txt", null, ChangeType.A, 4, 0, false),
                new FileChange("logo.png", null, ChangeType.A, 0, 0, true)));

        assertEquals(3, commit.filesChanged());
        assertEquals(12, commit.additions());
        assertEquals(3, commit.deletions());
    }

    @Test
    void identifiesRootAndMergeCommits() {
        assertTrue(commit(List.of(), List.of()).isRoot());
        assertFalse(commit(List.of(H2), List.of()).isMerge());
        assertTrue(commit(List.of(H2, H3), List.of()).isMerge());
    }

    @Test
    void shortHashIsTheFirstSevenCharacters() {
        assertEquals("aaaaaaa", commit(List.of(), List.of()).shortHash());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommitRecord("ABC", List.of(), "n", "e", 1L, 0, "s", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommitRecord(H1, List.of(), "n", "e", 1L, 900, "s", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("a.txt", "old.txt", ChangeType.M, 1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("new.txt", null, ChangeType.R, 1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new FileChange("logo.png", null, ChangeType.A, 3, 0, true));
    }
}
