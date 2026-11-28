package eu.kalafatic.dcode.tests.repositories;

import eu.kalafatic.dcode.repositories.git.JGitEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies per-file change classification and diff generation for added, modified,
 * deleted and untracked files.
 */
class JGitChangesTest {

    @TempDir
    Path temp;

    @Test
    void classifiesAndDiffsWorkingTreeChanges() throws Exception {
        Path repo = temp.resolve("repo");
        JGitEngine engine = new JGitEngine();
        engine.init(repo);
        Files.writeString(repo.resolve("keep.txt"), "line1\nline2\n");
        Files.writeString(repo.resolve("delete.txt"), "bye\n");
        engine.commit(repo, "seed", "T", "t@example.org");

        // Modify, add (staged), delete, and leave an untracked file.
        Files.writeString(repo.resolve("keep.txt"), "line1\nline2-CHANGED\n");
        Files.writeString(repo.resolve("added.txt"), "new file\n");
        Files.delete(repo.resolve("delete.txt"));
        Files.writeString(repo.resolve("untracked.txt"), "untracked content\n");
        // Stage the new file so it is classified ADDED (not UNTRACKED).
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo.toFile())) {
            git.add().addFilepattern("added.txt").call();
        }

        var changes = engine.changes(repo);
        assertEquals(4, changes.size(), changes.toString());

        var modified = find(changes, "keep.txt");
        assertEquals("MODIFIED", modified.status());
        assertTrue(modified.diff().contains("+line2-CHANGED"), modified.diff());

        var added = find(changes, "added.txt");
        assertEquals("ADDED", added.status());

        var deleted = find(changes, "delete.txt");
        assertEquals("DELETED", deleted.status());

        var untracked = find(changes, "untracked.txt");
        assertEquals("UNTRACKED", untracked.status());
    }

    private eu.kalafatic.dcode.repositories.git.GitEngine.Change find(
            java.util.List<eu.kalafatic.dcode.repositories.git.GitEngine.Change> changes,
            String path) {
        return changes.stream().filter(c -> c.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + path + " in " + changes));
    }
}
