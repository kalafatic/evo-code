package eu.kalafatic.dcode.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a unified diff into {@link DiffHunk} records. Used by change collection (to
 * populate hunks from JGit output) and by change-set persistence (to reconstruct hunks
 * from the stored per-file diff).
 */
public final class DiffHunks {

    private DiffHunks() {
    }

    /** Extracts the hunks of a per-file unified diff. */
    public static List<DiffHunk> parse(String unifiedDiff) {
        List<DiffHunk> hunks = new ArrayList<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return hunks;
        }
        String[] lines = unifiedDiff.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("@@")) {
                int[] range = newRange(line);
                int start = range[0];
                int count = range[1];
                StringBuilder content = new StringBuilder(line);
                int j = i + 1;
                int collected = 0;
                while (j < lines.length && !lines[j].startsWith("@@")
                        && !lines[j].startsWith("diff --git")) {
                    content.append('\n').append(lines[j]);
                    collected++;
                    j++;
                }
                int lineCount = count > 0 ? count : Math.max(collected, 1);
                hunks.add(new DiffHunk(start, lineCount, content.toString()));
                i = j;
            } else {
                i++;
            }
        }
        return hunks;
    }

    /** Extracts the new-file {@code [start, count]} from an {@code @@} header. */
    private static int[] newRange(String header) {
        int plus = header.indexOf('+', header.indexOf('@', 1));
        if (plus < 0) {
            return new int[]{1, 0};
        }
        int cursor = plus + 1;
        int start = 0;
        while (cursor < header.length() && Character.isDigit(header.charAt(cursor))) {
            start = start * 10 + (header.charAt(cursor) - '0');
            cursor++;
        }
        int count = 0;
        if (cursor < header.length() && header.charAt(cursor) == ',') {
            cursor++;
            while (cursor < header.length() && Character.isDigit(header.charAt(cursor))) {
                count = count * 10 + (header.charAt(cursor) - '0');
                cursor++;
            }
        } else {
            count = 1;
        }
        return new int[]{start == 0 ? 1 : start, count};
    }
}
