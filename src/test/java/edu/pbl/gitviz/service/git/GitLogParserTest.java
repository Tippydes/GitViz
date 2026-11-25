package edu.pbl.gitviz.service.git;

import edu.pbl.gitviz.model.AuthorTime;
import edu.pbl.gitviz.model.ChangeType;
import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.FileChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用手工构造的字节测试解析器，覆盖真实 git 难以稳定造出的情况（复制、类型变化、SHA-256、各种时区、损坏的输出）。
 * 与真实 git 输出的对照见 {@link GitServiceTest}。
 */
class GitLogParserTest {

    private static final String RS = "\u001e";
    private static final String US = "\u001f";
    private static final String NUL = "\0";

    private static final String H1 = "1".repeat(40);
    private static final String H2 = "2".repeat(40);
    private static final String H3 = "3".repeat(40);

    private static String header(String hash, String parents, String name, String email,
                                 long authorTime, String zone, String subject) {
        return RS + hash + US + parents + US + name + US + email + US + authorTime
                + US + "2026-11-01 10:00:00 " + zone + US + subject + NUL;
    }

    private static byte[] bytes(String... parts) {
        return String.join("", parts).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesHeaderFields() {
        List<CommitRecord> commits = GitLogParser.parse(bytes(
                header(H1, H2 + " " + H3, "张三", "Zhang@Example.com", 1793516557L, "+0800", "修复空仓库, \"打开\"报错")));

        CommitRecord commit = commits.get(0);
        assertEquals(H1, commit.hash());
        assertEquals(List.of(H2, H3), commit.parentHashes());
        assertTrue(commit.isMerge());
        assertEquals("张三", commit.authorName());
        assertEquals("Zhang@Example.com", commit.authorEmail());
        assertEquals(new AuthorTime(1793516557L, 480), commit.authorTime().orElseThrow());
        assertEquals("修复空仓库, \"打开\"报错", commit.subject());
        assertEquals(List.of(), commit.fileChanges());
    }

    @Test
    void parsesRootCommitWithEmptyEmailAndEmptySubject() {
        CommitRecord commit = GitLogParser.parse(bytes(header(H1, "", "无名", "", 0L, "+0000", ""))).get(0);

        assertTrue(commit.isRoot());
        assertEquals("", commit.authorEmail());
        assertEquals("", commit.subject());
    }

    @Test
    void parsesEveryChangeTypeAndPairsNumstatByPath() {
        String diff = "\n"
                + ":100644 100644 1111111 2222222 R087" + NUL + "旧目录/名字.txt" + NUL + "新目录/名字.txt" + NUL
                + ":100644 100644 1111111 3333333 C075" + NUL + "src.txt" + NUL + "copy.txt" + NUL
                + ":100644 120000 1111111 4444444 T" + NUL + "link" + NUL
                + ":000000 100644 0000000 5555555 A" + NUL + "logo.png" + NUL
                + ":100644 000000 6666666 0000000 D" + NUL + "gone.txt" + NUL
                + ":100644 100755 7777777 7777777 M" + NUL + "run.sh" + NUL
                + "3\t1\t" + NUL + "旧目录/名字.txt" + NUL + "新目录/名字.txt" + NUL
                + "2\t0\t" + NUL + "src.txt" + NUL + "copy.txt" + NUL
                + "1\t1\tlink" + NUL
                + "-\t-\tlogo.png" + NUL
                + "0\t4\tgone.txt" + NUL
                + "0\t0\trun.sh" + NUL;

        CommitRecord commit = GitLogParser.parse(bytes(header(H1, H2, "a", "a@x", 1L, "+0800", "s"), diff)).get(0);

        assertEquals(List.of(
                new FileChange("新目录/名字.txt", "旧目录/名字.txt", ChangeType.R, 3, 1, false),
                new FileChange("copy.txt", "src.txt", ChangeType.C, 2, 0, false),
                new FileChange("link", null, ChangeType.T, 1, 1, false),
                new FileChange("logo.png", null, ChangeType.A, 0, 0, true),
                new FileChange("gone.txt", null, ChangeType.D, 0, 4, false),
                new FileChange("run.sh", null, ChangeType.M, 0, 0, false)), commit.fileChanges());
        assertEquals(6, commit.filesChanged());
        assertEquals(6, commit.additions());
        assertEquals(6, commit.deletions());
    }

    @Test
    void anUnknownAuthorTimeKeepsTheCommitWithItsFilesAndAnEmptyTime() {
        // git 对 1970 年以前的提交输出空的 %at 与 %ai：时间未知，但提交和文件变更照常保留
        byte[] output = bytes(RS + H1 + US + H2 + US + "张三" + US + "zhang@example.com" + US + US + US
                        + "旧提交" + NUL,
                "\n:100644 100644 1111111 2222222 M" + NUL + "a.txt" + NUL + "3\t1\ta.txt" + NUL);

        CommitRecord commit = GitLogParser.parse(output).get(0);

        assertEquals(Optional.empty(), commit.authorTime());
        assertEquals("旧提交", commit.subject());
        assertEquals(List.of(new FileChange("a.txt", null, ChangeType.M, 3, 1, false)), commit.fileChanges());
    }

    @Test
    void aTimeWithoutItsTimezoneIsAFormatError() {
        // %at 有值而 %ai 为空不是 git 的正常输出，不能静默当成未知时间
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parseAuthorTime("1793516557", ""));
    }

    @Test
    void parsesNegativeEpochSecondsDefensively() {
        // 当前 git 不会为负的时间输出数字（见上面的 anUnknownAuthorTime...），这里只保证解析器本身能处理
        assertEquals(Optional.of(new AuthorTime(-1L, 0)),
                GitLogParser.parseAuthorTime("-1", "1969-12-31 23:59:59 +0000"));
    }

    @Test
    void aTimeOutsideTheDisplayableRangeIsUnknownNotAFailure() {
        // 超出范围的时间不能让整个仓库打不开，按未知处理
        assertEquals(Optional.empty(), GitLogParser.parseAuthorTime(Long.toString(Long.MAX_VALUE), "x +0000"));
    }

    @Test
    void acceptsSha256Hashes() {
        String a = "a".repeat(64);
        String b = "b".repeat(64);

        CommitRecord commit = GitLogParser.parse(bytes(header(a, b, "n", "e", 1L, "+0800", "s"))).get(0);

        assertEquals(a, commit.hash());
        assertEquals(List.of(b), commit.parentHashes());
    }

    @ParameterizedTest
    @CsvSource({"+0800, 480", "-0530, -330", "+0000, 0", "-1200, -720", "+1400, 840", "+0545, 345"})
    void convertsTheTimezoneOfAiToMinutes(String zone, int minutes) {
        assertEquals(minutes, GitLogParser.tzOffsetMinutes("2026-11-01 10:00:00 " + zone));
    }

    @Test
    void parsesSeveralRecordsInOrder() {
        List<CommitRecord> commits = GitLogParser.parse(bytes(
                header(H1, H2, "a", "a@x", 3L, "+0800", "third"),
                "\n:000000 100644 0000000 1111111 A" + NUL + "c.txt" + NUL + "1\t0\tc.txt" + NUL,
                header(H2, H3, "a", "a@x", 2L, "+0800", "second"),
                header(H3, "", "a", "a@x", 1L, "+0800", "first"),
                "\n:000000 100644 0000000 1111111 A" + NUL + "a.txt" + NUL + "2\t0\ta.txt" + NUL));

        assertEquals(List.of("third", "second", "first"), commits.stream().map(CommitRecord::subject).toList());
        assertEquals(1, commits.get(0).filesChanged());
        assertEquals(0, commits.get(1).filesChanged());
        assertEquals(2, commits.get(2).additions());
    }

    @Test
    void streamingParseHandlesRecordsSplitAcrossReads() throws IOException {
        byte[] output = bytes(
                header(H1, H2, "张三", "z@x", 2L, "+0800", "第二次"),
                "\n:100644 100644 1111111 2222222 R100" + NUL + "旧.txt" + NUL + "新.txt" + NUL
                        + "0\t0\t" + NUL + "旧.txt" + NUL + "新.txt" + NUL,
                header(H2, "", "李四", "l@x", 1L, "-0530", "第一次"));
        List<CommitRecord> streamed = new ArrayList<>();

        int count = GitLogParser.parse(new OneByteAtATime(output), streamed::add);

        assertEquals(2, count);
        assertEquals(GitLogParser.parse(output), streamed);
    }

    @Test
    void handlesTwentyThousandCommits() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 20_000; i++) {
            String hash = String.format("%040x", i + 1);
            output.writeBytes(bytes(header(hash, "", "作者" + (i % 7), "a@x", i, "+0800", "提交 " + i),
                    "\n:100644 100644 1111111 2222222 M" + NUL + "src/文件" + i + ".txt" + NUL
                            + "3\t2\tsrc/文件" + i + ".txt" + NUL));
        }

        int count = GitLogParser.parse(new ByteArrayInputStream(output.toByteArray()), commit -> { });

        assertEquals(20_000, count);
    }

    @Test
    void emptyOutputHasNoCommits() {
        assertEquals(List.of(), GitLogParser.parse(new byte[0]));
    }

    @Test
    void rawEntryWithoutNumstatIsAParseErrorNotZeroLines() {
        // git 会为每个变更文件给出增删行数；缺失说明输出不完整，不能静默当成 0 行
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parse(bytes(
                header(H1, "", "a", "a@x", 1L, "+0800", "s"),
                "\n:160000 160000 1111111 2222222 M" + NUL + "submodule" + NUL)));
    }

    @Test
    void invalidUtf8PathsThatDecodeToTheSameTextKeepTheirOwnCounts() {
        // 两个不同的非法字节序列按 UTF-8 解码都变成 U+FFFD：按字节配对，各自拿到自己的增删行数
        byte[] first = {(byte) 0xfe, (byte) 0xff, '.', 't', 'x', 't'};
        byte[] second = {(byte) 0xff, (byte) 0xfe, '.', 't', 'x', 't'};
        byte[] output = concat(
                bytes(header(H1, "", "a", "a@x", 1L, "+0800", "s"),
                        "\n:100644 100644 1111111 2222222 M" + NUL),
                first, bytes(NUL),
                bytes(":100644 100644 1111111 3333333 M" + NUL),
                second, bytes(NUL),
                bytes("8\t0\t"), first, bytes(NUL),
                bytes("2\t0\t"), second, bytes(NUL));

        List<FileChange> changes = GitLogParser.parse(output).get(0).fileChanges();

        assertEquals(2, changes.size());
        assertEquals(8, changes.get(0).additions(), "第一个文件应得到自己的 +8");
        assertEquals(2, changes.get(1).additions(), "第二个文件应得到自己的 +2，而不是被覆盖");
    }

    @Test
    void theSameFileListedTwiceInNumstatIsAParseError() {
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parse(bytes(
                header(H1, "", "a", "a@x", 1L, "+0800", "s"),
                "\n:100644 100644 1111111 2222222 M" + NUL + "a.txt" + NUL
                        + "1\t0\ta.txt" + NUL + "2\t0\ta.txt" + NUL)));
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    void rejectsMissingHeaderFields() {
        assertThrows(GitOutputParseException.class,
                () -> GitLogParser.parse(bytes(RS + H1 + US + H2 + US + "only three fields" + NUL)));
    }

    @Test
    void rejectsTruncatedOutput() {
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parse(bytes(
                header(H1, "", "a", "a@x", 1L, "+0800", "s"),
                "\n:100644 100644 1111111 2222222 M" + NUL + "path-without-terminator")));
    }

    @Test
    void rejectsUnknownChangeStatus() {
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parse(bytes(
                header(H1, "", "a", "a@x", 1L, "+0800", "s"),
                "\n:100644 100644 1111111 2222222 X" + NUL + "a.txt" + NUL + "1\t0\ta.txt" + NUL)));
    }

    @Test
    void rejectsMalformedNumstat() {
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parse(bytes(
                header(H1, "", "a", "a@x", 1L, "+0800", "s"),
                "\n:100644 100644 1111111 2222222 M" + NUL + "a.txt" + NUL + "x\ty\ta.txt" + NUL)));
    }

    @Test
    void rejectsOutputThatDoesNotStartWithARecord() {
        assertThrows(GitOutputParseException.class, () -> GitLogParser.parse(bytes(
                "warning: something\n", header(H1, "", "a", "a@x", 1L, "+0800", "s"))));
    }

    @Test
    void rejectsAnInvalidHash() {
        assertThrows(GitOutputParseException.class,
                () -> GitLogParser.parse(bytes(header("not-a-hash", "", "a", "a@x", 1L, "+0800", "s"))));
    }

    /** 每次 read 只返回一个字节，用来检验跨读取边界的记录拼接。 */
    private static final class OneByteAtATime extends InputStream {

        private final byte[] data;
        private int position;

        OneByteAtATime(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            buffer[offset] = data[position++];
            return 1;
        }
    }
}
