package edu.pbl.gitviz.service.git;

import edu.pbl.gitviz.model.ChangeType;
import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.model.FileChange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 解析 §3.4.7 那条 {@code git log … --raw --numstat -M -z} 的输出。
 *
 * <p>每个提交是一条记录，以 0x1E 开头：
 * <pre>
 * 0x1E %H 0x1F %P 0x1F %aN 0x1F %aE 0x1F %at 0x1F %ai 0x1F %s NUL
 * [ \n  raw 条目…  numstat 条目… ]          ← 空提交没有这一段
 *
 * raw：     ":100644 100644 abc def M" NUL path NUL
 *           ":100644 100644 abc def R100" NUL old NUL new NUL      （C 同理）
 * numstat： "12\t3\tpath" NUL
 *           "12\t3\t" NUL old NUL new NUL                          （重命名 / 复制）
 *           "-\t-\tpath" NUL                                       （二进制）
 * </pre>
 * raw 提供变更类型和路径，numstat 提供增删行数，两者按新路径配对。
 *
 * <p>流式解析：每读完一条记录就交给回调，内存中只保留当前记录的字节。
 */
public final class GitLogParser {

    /** 提交头的格式，字段顺序与 {@link #parseRecord} 一致。 */
    public static final String FORMAT = "--format=%x1e%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%ai%x1f%s";

    private static final byte RECORD_SEPARATOR = 0x1e;
    private static final byte UNIT_SEPARATOR = 0x1f;
    private static final byte NUL = 0;
    /** 提交说明之前的字段数：%H %P %aN %aE %at %ai。 */
    private static final int HEADER_FIELDS = 6;

    private GitLogParser() {
    }

    /** 解析完整的输出。 */
    public static List<CommitRecord> parse(byte[] output) {
        List<CommitRecord> commits = new ArrayList<>();
        try {
            parse(new ByteArrayInputStream(output), commits::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return commits;
    }

    /**
     * 边读边解析，每解析出一个提交就调用一次 {@code sink}（顺序与 git 输出一致）。
     *
     * @return 解析出的提交数
     * @throws GitOutputParseException 输出格式不符合预期
     */
    public static int parse(InputStream in, Consumer<CommitRecord> sink) throws IOException {
        RecordBuffer record = new RecordBuffer();
        boolean inRecord = false;
        int count = 0;
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (chunk[i] != RECORD_SEPARATOR) {
                    continue;
                }
                record.append(chunk, start, i - start);
                if (inRecord) {
                    sink.accept(parseRecord(record.bytes(), 0, record.length()));
                    count++;
                } else if (record.length() != 0) {
                    throw new GitOutputParseException("git log 的输出没有以记录分隔符开头");
                }
                inRecord = true;
                record.clear();
                start = i + 1;
            }
            record.append(chunk, start, n - start);
        }
        if (inRecord) {
            sink.accept(parseRecord(record.bytes(), 0, record.length()));
            count++;
        } else if (record.length() != 0) {
            throw new GitOutputParseException("git log 的输出中没有找到记录分隔符");
        }
        return count;
    }

    static CommitRecord parseRecord(byte[] b, int from, int to) {
        int[] separators = new int[HEADER_FIELDS];
        int pos = from;
        for (int k = 0; k < HEADER_FIELDS; k++) {
            int s = indexOf(b, UNIT_SEPARATOR, pos, to);
            if (s < 0) {
                throw new GitOutputParseException("提交头字段不完整：" + preview(b, from, to));
            }
            separators[k] = s;
            pos = s + 1;
        }
        String hash = text(b, from, separators[0]);
        try {
            List<String> parents = parents(text(b, separators[0] + 1, separators[1]));
            String authorName = text(b, separators[1] + 1, separators[2]);
            String authorEmail = text(b, separators[2] + 1, separators[3]);
            long authorTime = Long.parseLong(text(b, separators[3] + 1, separators[4]));
            int tzOffset = tzOffsetMinutes(text(b, separators[4] + 1, separators[5]));

            int subjectStart = separators[5] + 1;
            int subjectEnd = indexOf(b, NUL, subjectStart, to);
            int diffStart;
            if (subjectEnd < 0) {
                subjectEnd = to;
                diffStart = to;
            } else {
                diffStart = subjectEnd + 1;
            }
            String subject = text(b, subjectStart, subjectEnd);
            if (diffStart < to && b[diffStart] == '\n') {
                diffStart++;
            }
            List<FileChange> changes = parseChanges(b, diffStart, to);
            return new CommitRecord(hash, parents, authorName, authorEmail, authorTime, tzOffset, subject, changes);
        } catch (GitOutputParseException e) {
            throw new GitOutputParseException("提交 " + hash + "：" + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new GitOutputParseException("提交 " + hash + " 的字段无效：" + e.getMessage(), e);
        }
    }

    private static List<String> parents(String field) {
        return field.isEmpty() ? List.of() : Arrays.asList(field.split(" "));
    }

    /** {@code %ai} 形如 {@code 2026-11-02 10:00:00 -0530}，只取末尾的时区，换算为分钟。 */
    static int tzOffsetMinutes(String isoLikeDate) {
        String zone = isoLikeDate.substring(isoLikeDate.lastIndexOf(' ') + 1);
        if (zone.length() != 5 || (zone.charAt(0) != '+' && zone.charAt(0) != '-')) {
            throw new GitOutputParseException("无法识别的时区：" + isoLikeDate);
        }
        int hours = Integer.parseInt(zone.substring(1, 3));
        int minutes = Integer.parseInt(zone.substring(3, 5));
        int total = hours * 60 + minutes;
        return zone.charAt(0) == '-' ? -total : total;
    }

    private static List<FileChange> parseChanges(byte[] b, int from, int to) {
        List<String> tokens = nulTerminatedTokens(b, from, to);
        List<RawEntry> raw = new ArrayList<>();
        Map<String, NumstatEntry> numstat = new HashMap<>();
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i++);
            if (token.startsWith(":")) {
                if (token.startsWith("::")) {
                    throw new GitOutputParseException("不支持组合差异格式：" + token);
                }
                ChangeType type = ChangeType.fromRawStatus(token.substring(token.lastIndexOf(' ') + 1));
                String oldPath = null;
                if (type.hasOldPath()) {
                    oldPath = next(tokens, i++);
                }
                String path = next(tokens, i++);
                raw.add(new RawEntry(type, path, oldPath));
            } else {
                int tab1 = token.indexOf('\t');
                int tab2 = tab1 < 0 ? -1 : token.indexOf('\t', tab1 + 1);
                if (tab2 < 0) {
                    throw new GitOutputParseException("无法识别的 numstat 条目：" + token);
                }
                String added = token.substring(0, tab1);
                String deleted = token.substring(tab1 + 1, tab2);
                String path = token.substring(tab2 + 1);
                if (path.isEmpty()) {          // 重命名 / 复制：后面跟着旧路径和新路径
                    next(tokens, i++);
                    path = next(tokens, i++);
                }
                numstat.putIfAbsent(path, NumstatEntry.of(added, deleted));
            }
        }
        if (raw.isEmpty() && !numstat.isEmpty()) {
            throw new GitOutputParseException("只有 numstat 没有 raw 条目");
        }
        List<FileChange> changes = new ArrayList<>(raw.size());
        for (RawEntry entry : raw) {
            NumstatEntry stat = numstat.getOrDefault(entry.path(), NumstatEntry.NONE);
            changes.add(new FileChange(entry.path(), entry.oldPath(), entry.type(),
                    stat.additions(), stat.deletions(), stat.binary()));
        }
        return changes;
    }

    private static String next(List<String> tokens, int index) {
        if (index >= tokens.size()) {
            throw new GitOutputParseException("文件变更条目缺少路径");
        }
        return tokens.get(index);
    }

    /** 按 NUL 切分；最后一段必须以 NUL 结尾，否则说明输出被截断。 */
    private static List<String> nulTerminatedTokens(byte[] b, int from, int to) {
        List<String> tokens = new ArrayList<>();
        int start = from;
        for (int i = from; i < to; i++) {
            if (b[i] == NUL) {
                tokens.add(text(b, start, i));
                start = i + 1;
            }
        }
        if (start < to) {
            throw new GitOutputParseException("输出被截断：" + preview(b, start, to));
        }
        return tokens;
    }

    private static int indexOf(byte[] b, byte value, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static String text(byte[] b, int from, int to) {
        return new String(b, from, to - from, StandardCharsets.UTF_8);
    }

    private static String preview(byte[] b, int from, int to) {
        String text = text(b, from, Math.min(to, from + 80));
        return text.replace('\u001f', '|').replace('\0', '␀');
    }

    private record RawEntry(ChangeType type, String path, String oldPath) {
    }

    private record NumstatEntry(int additions, int deletions, boolean binary) {

        static final NumstatEntry NONE = new NumstatEntry(0, 0, false);

        static NumstatEntry of(String added, String deleted) {
            if (added.equals("-") && deleted.equals("-")) {
                return new NumstatEntry(0, 0, true);
            }
            try {
                return new NumstatEntry(Integer.parseInt(added), Integer.parseInt(deleted), false);
            } catch (NumberFormatException e) {
                throw new GitOutputParseException("无法识别的增删行数：" + added + " " + deleted, e);
            }
        }
    }

    /** 可增长的字节缓冲，复用同一块数组，避免每条记录都复制。 */
    private static final class RecordBuffer {

        private byte[] bytes = new byte[8192];
        private int length;

        void append(byte[] src, int offset, int count) {
            if (count <= 0) {
                return;
            }
            if (length + count > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + count));
            }
            System.arraycopy(src, offset, bytes, length, count);
            length += count;
        }

        byte[] bytes() {
            return bytes;
        }

        int length() {
            return length;
        }

        void clear() {
            length = 0;
        }
    }
}
