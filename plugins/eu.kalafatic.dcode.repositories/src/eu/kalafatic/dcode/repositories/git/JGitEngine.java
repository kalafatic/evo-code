package eu.kalafatic.dcode.repositories.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * JGit-based {@link GitEngine} implementation.
 *
 * <p>Works against plain working directories. No dependency on the Eclipse workspace or
 * UI; safe for the headless runtime. Identity is explicit on commit; repositories without
 * user identity configuration still commit correctly.</p>
 */
public final class JGitEngine implements GitEngine {

    @Override
    public RepositoryInfo info(Path repoDir) {
        try (Git git = open(repoDir)) {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            if (branch == null || branch.isEmpty()) {
                branch = repo.exactRef(Constants.HEAD) != null
                        ? "DETACHED" : "";
            }
            Status status = git.status().call();
            String remoteUrl = null;
            var remotes = repo.getRemoteNames();
            if (!remotes.isEmpty()) {
                var config = repo.getConfig().getSubsections("remote");
                if (!config.isEmpty()) {
                    remoteUrl = repo.getConfig().getString("remote", config.iterator().next(), "url");
                }
            }
            return new RepositoryInfo(branch, remoteUrl, status.isClean(), countCommits(repo));
        } catch (Exception e) {
            throw new GitException("Failed to inspect repository " + repoDir, e);
        }
    }

    @Override
    public List<String> branches(Path repoDir) {
        try (Git git = open(repoDir)) {
            List<String> result = new ArrayList<>();
            for (Ref ref : git.branchList().call()) {
                String name = ref.getName();
                if (name.startsWith(Constants.R_HEADS)) {
                    result.add(name.substring(Constants.R_HEADS.length()));
                }
            }
            return result;
        } catch (Exception e) {
            throw new GitException("Failed to list branches of " + repoDir, e);
        }
    }

    @Override
    public List<String> changedFiles(Path repoDir) {
        try (Git git = open(repoDir)) {
            Status status = git.status().call();
            Set<String> paths = new TreeSet<>();
            paths.addAll(status.getAdded());
            paths.addAll(status.getModified());
            paths.addAll(status.getRemoved());
            paths.addAll(status.getMissing());
            paths.addAll(status.getUntracked());
            return List.copyOf(paths);
        } catch (Exception e) {
            throw new GitException("Failed to determine changed files of " + repoDir, e);
        }
    }

    @Override
    public String diff(Path repoDir) {
        try (Git git = open(repoDir)) {
            Repository repo = git.getRepository();
            AbstractTreeIterator oldTree = prepareTreeParser(repo, Constants.HEAD);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);
                List<DiffEntry> entries = formatter.scan(oldTree, new org.eclipse.jgit.treewalk.FileTreeIterator(repo));
                for (DiffEntry entry : entries) {
                    formatter.format(entry);
                }
                formatter.flush();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new GitException("Failed to diff repository " + repoDir, e);
        }
    }

    @Override
    public List<Change> changes(Path repoDir) {
        try (Git git = open(repoDir)) {
            Status status = git.status().call();
            // Per-file diffs derived from the proven single-formatter full diff
            // (HEAD vs working tree), split on "diff --git" markers.
            java.util.Map<String, String> split = splitDiff(diff(repoDir));
            Set<String> seen = new TreeSet<>();
            List<Change> result = new ArrayList<>();
            addAll(result, seen, status.getAdded(), "ADDED", split);
            addAll(result, seen, status.getModified(), "MODIFIED", split);
            addAll(result, seen, status.getRemoved(), "DELETED", split);
            addAll(result, seen, status.getMissing(), "DELETED", split);
            addAll(result, seen, status.getUntracked(), "UNTRACKED", split);
            return result;
        } catch (Exception e) {
            throw new GitException("Failed to compute changes of " + repoDir, e);
        }
    }

    private static java.util.Map<String, String> splitDiff(String full) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        StringBuilder current = null;
        String currentPath = null;
        for (String line : full.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (currentPath != null) {
                    map.put(currentPath, current.toString());
                }
                current = new StringBuilder(line);
                currentPath = newPath(line);
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (currentPath != null) {
            map.put(currentPath, current.toString());
        }
        return map;
    }

    /** Extracts the new path from a {@code diff --git a/<path> b/<path>} header. */
    private static String newPath(String header) {
        int b = header.indexOf(" b/", header.indexOf(" a/") + 3);
        if (b < 0) {
            return header.substring("diff --git ".length());
        }
        String path = header.substring(b + 3);
        // Unquote when JGit quoted the path.
        if (path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        return path;
    }

    private static void addAll(List<Change> result, Set<String> seen, Set<String> paths,
                               String status, java.util.Map<String, String> diffs) {
        for (String path : paths) {
            if (!seen.add(path)) {
                continue;
            }
            result.add(new Change(path, null, status, diffs.getOrDefault(path, "")));
        }
    }

    @Override
    public List<String> log(Path repoDir, int maxCount) {
        try (Git git = open(repoDir)) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(head));
                int count = 0;
                for (RevCommit commit : walk) {
                    if (count++ >= maxCount) {
                        break;
                    }
                    result.add(commit.getId().getName() + " " + commit.getShortMessage());
                }
            }
            return result;
        } catch (Exception e) {
            throw new GitException("Failed to read log of " + repoDir, e);
        }
    }

    @Override
    public String headCommit(Path repoDir) {
        try (Git git = open(repoDir)) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return head != null ? head.getName() : null;
        } catch (Exception e) {
            throw new GitException("Failed to resolve HEAD of " + repoDir, e);
        }
    }

    @Override
    public boolean hasChanges(Path repoDir) {
        try (Git git = open(repoDir)) {
            return !git.status().call().isClean();
        } catch (Exception e) {
            throw new GitException("Failed to check repository state " + repoDir, e);
        }
    }

    @Override
    public void init(Path repoDir) {
        try {
            Files.createDirectories(repoDir);
            try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
                // repository created
            }
        } catch (Exception e) {
            throw new GitException("Failed to initialize repository " + repoDir, e);
        }
    }

    @Override
    public void clone(String remoteUrl, Path targetDir) {
        try {
            if (Files.exists(targetDir)) {
                throw new GitException("Clone target already exists: " + targetDir);
            }
            try (Git git = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(targetDir.toFile())
                    .call()) {
                // cloned
            }
        } catch (Exception e) {
            throw new GitException("Failed to clone " + remoteUrl + " into " + targetDir, e);
        }
    }

    @Override
    public String commit(Path repoDir, String message, String authorName, String authorEmail) {
        try (Git git = open(repoDir)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            PersonIdent ident = new PersonIdent(authorName, authorEmail);
            RevCommit commit = git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setAllowEmpty(false)
                    .call();
            return commit.getId().getName();
        } catch (Exception e) {
            throw new GitException("Failed to commit in " + repoDir, e);
        }
    }

    @Override
    public void pull(Path repoDir) {
        try (Git git = open(repoDir)) {
            git.pull().call();
        } catch (Exception e) {
            throw new GitException("Failed to pull in " + repoDir, e);
        }
    }

    @Override
    public void push(Path repoDir) {
        try (Git git = open(repoDir)) {
            git.push().call();
        } catch (Exception e) {
            throw new GitException("Failed to push in " + repoDir, e);
        }
    }

    @Override
    public void checkoutBranch(Path repoDir, String branch) {
        try (Git git = open(repoDir)) {
            git.checkout().setName(branch).call();
        } catch (Exception e) {
            throw new GitException("Failed to check out branch " + branch + " in " + repoDir, e);
        }
    }

    @Override
    public void checkoutRemoteBranch(Path repoDir, String branch) {
        try (Git git = open(repoDir)) {
            git.checkout().setCreateBranch(true).setName(branch)
                    .setStartPoint("origin/" + branch).call();
        } catch (Exception e) {
            throw new GitException("Failed to check out remote branch " + branch + " in "
                    + repoDir, e);
        }
    }

    private static Git open(Path repoDir) throws IOException {
        if (!Files.isDirectory(repoDir.resolve(".git"))) {
            throw new GitException("Not a Git repository (no .git directory): " + repoDir);
        }
        return Git.open(repoDir.toFile());
    }

    private static int countCommits(Repository repo) throws IOException {
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) {
            return 0;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(head));
            int count = 0;
            for (RevCommit ignored : walk) {
                count++;
            }
            return count;
        }
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repo, String ref)
            throws IOException {
        ObjectId head = repo.resolve(ref + "^{tree}");
        if (head == null) {
            return new org.eclipse.jgit.treewalk.EmptyTreeIterator();
        }
        try (RevWalk walk = new RevWalk(repo)) {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(repo.newObjectReader(), walk.parseTree(head));
            return parser;
        }
    }
}
