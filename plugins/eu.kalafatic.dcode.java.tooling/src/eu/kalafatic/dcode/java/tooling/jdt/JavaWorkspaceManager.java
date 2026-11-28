package eu.kalafatic.dcode.java.tooling.jdt;

import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless Eclipse Java workspace manager.
 *
 * <p>Runs a real Eclipse workspace (ResourcesPlugin + JDT Core) without SWT, Workbench
 * or a display. Java projects discovered in DCode repositories are imported as linked
 * Eclipse projects with resolved classpaths; JDT semantic services (Java model, search
 * index, hierarchies, batch compiler) operate on them.</p>
 *
 * <p>All public operations are safe to call when Eclipse services failed to start: they
 * throw {@link ToolingException} with a clear message instead of crashing the runtime.</p>
 */
@SuppressWarnings("restriction")
public final class JavaWorkspaceManager {

    /** One imported Java project. */
    public record ProjectHandle(
            String repositoryName,
            String moduleDir,
            IProject project,
            IJavaProject javaProject,
            JavaProjectKind kind
    ) {
    }

    private static final int MAX_SCAN_DEPTH = 4;
    private static final int MAX_SOURCE_FILES = 2000;
    private static final Duration MAVEN_TIMEOUT = Duration.ofSeconds(120);

    private static final java.util.concurrent.atomic.AtomicLong INSTANCE_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private final Map<String, ProjectHandle> projectsByKey = new ConcurrentHashMap<>();
    private final Map<String, String> mavenClasspathCache = new ConcurrentHashMap<>();
    private final Object startLock = new Object();
    private final long instanceId = INSTANCE_SEQ.incrementAndGet();
    private volatile boolean started;

    /** Ensures the Eclipse workspace is running (idempotent, lazy). */
    public void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (startLock) {
            if (started) {
                return;
            }
            try {
                // A headless runtime has no `-data` argument: give the Resources
                // workspace a stable instance location under the DCode home before the
                // resources bundle starts. Without this, ResourcesPlugin.getWorkspace()
                // throws "Workspace is already closed or not ready yet".
                ensureInstanceLocation();
                // Open the Eclipse resources workspace BEFORE activating JDT Core:
                // JDT's JavaCore.start() (lazy) requires a ready workspace (it creates a
                // WorkspaceJob). Starting JDT first crashes with "Workspace is already
                // closed or not ready yet".
                startBundle("org.eclipse.core.resources");
                ResourcesPlugin.getWorkspace().getRoot();
                startBundle("org.eclipse.core.jobs");
                startBundle("org.eclipse.jdt.core");
                started = true;
                DCodeLog.info("java.tooling", "Eclipse JDT workspace started (headless)");
            } catch (Exception e) {
                throw new ToolingException("Eclipse workspace services are unavailable: "
                        + e.getMessage(), e);
            }
        }
    }

    private static void startBundle(String symbolicName) throws Exception {
        Bundle bundle = Platform.getBundle(symbolicName);
        if (bundle == null) {
            throw new ToolingException("Bundle not installed: " + symbolicName);
        }
        if (bundle.getState() != Bundle.ACTIVE && bundle.getState() != Bundle.STARTING) {
            bundle.start(Bundle.START_TRANSIENT);
        }
    }

    /** Sets the instance location under the DCode home when none is configured. */
    private static void ensureInstanceLocation() {
        try {
            var instanceLocation = org.eclipse.core.runtime.Platform.getInstanceLocation();
            if (instanceLocation != null && !instanceLocation.isSet()) {
                String home = System.getProperty("dcode.home",
                        System.getProperty("user.home", ".") + "/.dcode");
                java.net.URL url = java.nio.file.Path.of(home, "workspace").toUri().toURL();
                instanceLocation.set(url, false);
            }
        } catch (Exception e) {
            // Leave unset; ResourcesPlugin.getWorkspace() reports the definitive error.
        }
    }

    /**
     * Scans a repository for Java projects and imports all of them. Returns the
     * discovered handles; already-imported projects are reused.
     */
    public synchronized List<ProjectHandle> scanRepository(Workspace workspace,
                                                           RepositoryRef repository) {
        ensureStarted();
        java.nio.file.Path repoDir = java.nio.file.Path.of(repository.localPath());
        List<java.nio.file.Path> moduleDirs = discoverModules(repoDir);
        List<ProjectHandle> handles = new ArrayList<>();
        for (java.nio.file.Path moduleDir : moduleDirs) {
            try {
                handles.add(importProject(workspace, repository, repoDir, moduleDir));
            } catch (Exception e) {
                DCodeLog.error("java.tooling", "Failed to import Java project "
                        + moduleDir, e);
            }
        }
        // Reactor classpath: sibling Maven modules participate as JDT project entries
        // (shared source index, matching a reactor build).
        for (ProjectHandle handle : handles) {
            if (handle.kind() == JavaProjectKind.MAVEN) {
                try {
                    applyClasspath(handle, handles);
                } catch (Exception e) {
                    DCodeLog.error("java.tooling", "Failed to set classpath for "
                            + handle.moduleDir(), e);
                }
            }
        }
        return handles;
    }

    /** Returns the handle for a module inside a repository, importing it on demand. */
    public ProjectHandle projectForModule(Workspace workspace, RepositoryRef repository,
                                          String moduleDir) {
        ensureStarted();
        java.nio.file.Path repoDir = java.nio.file.Path.of(repository.localPath());
        java.nio.file.Path modulePath = moduleDir == null || moduleDir.isBlank()
                ? repoDir : repoDir.resolve(moduleDir).normalize();
        String key = key(repository.name(), modulePath);
        ProjectHandle existing = projectsByKey.get(key);
        if (existing != null) {
            return existing;
        }
        scanRepository(workspace, repository);
        existing = projectsByKey.get(key);
        if (existing == null) {
            throw new ToolingException("No Java project found at " + modulePath
                    + " in repository " + repository.name());
        }
        return existing;
    }

    /** Resolves the project handle for a workspace-relative source path. */
    public ProjectHandle projectForPath(Workspace workspace, String workspaceRelativePath) {
        ensureStarted();
        RepositoryRef repository = repositoryForPath(workspace, workspaceRelativePath);
        java.nio.file.Path repoDir = java.nio.file.Path.of(repository.localPath());
        java.nio.file.Path file = java.nio.file.Path.of(workspace.rootPath(), workspaceRelativePath).normalize();
        ProjectHandle best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (ProjectHandle handle : projectsByKey.values()) {
            if (!handle.repositoryName().equals(repository.name())) {
                continue;
            }
            java.nio.file.Path module = java.nio.file.Path.of(handle.moduleDir()).normalize();
            if (file.startsWith(module)) {
                int depth = module.getNameCount();
                if (depth < bestDepth) {
                    bestDepth = depth;
                    best = handle;
                }
            }
        }
        if (best == null) {
            // lazy recovery: index all repositories and retry
            for (RepositoryRef ref : workspace.repositories()) {
                try {
                    scanRepository(workspace, ref);
                } catch (Exception ignored) {
                    // keep scanning the others
                }
            }
            best = null;
            bestDepth = Integer.MAX_VALUE;
            for (ProjectHandle handle : projectsByKey.values()) {
                if (!handle.repositoryName().equals(repository.name())) {
                    continue;
                }
                java.nio.file.Path module = java.nio.file.Path.of(handle.moduleDir()).normalize();
                if (file.startsWith(module)) {
                    int depth = module.getNameCount();
                    if (depth < bestDepth) {
                        bestDepth = depth;
                        best = handle;
                    }
                }
            }
        }
        if (best == null) {
            throw new ToolingException("No imported Java project contains "
                    + workspaceRelativePath + " (run java_project_structure first)");
        }
        return best;
    }

    /** Finds the repository that contains a workspace-relative path. */
    public RepositoryRef repositoryForPath(Workspace workspace, String workspaceRelativePath) {
        java.nio.file.Path file = java.nio.file.Path.of(workspace.rootPath(), workspaceRelativePath).normalize();
        RepositoryRef best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (RepositoryRef repository : workspace.repositories()) {
            java.nio.file.Path repoDir = java.nio.file.Path.of(repository.localPath()).normalize();
            if (file.startsWith(repoDir)) {
                int depth = repoDir.getNameCount();
                if (depth < bestDepth) {
                    bestDepth = depth;
                    best = repository;
                }
            }
        }
        if (best == null) {
            throw new ToolingException("Path is not inside any workspace repository: "
                    + workspaceRelativePath);
        }
        return best;
    }

    /** Looks up a type by fully qualified name in an imported project. */
    public IType findType(ProjectHandle handle, String fullyQualifiedName) {
        ensureStarted();
        try {
            IType type = handle.javaProject().findType(fullyQualifiedName);
            if (type != null && type.exists()) {
                openQuietly(type);
                return type;
            }
        } catch (CoreException e) {
            throw new ToolingException("Type lookup failed for " + fullyQualifiedName, e);
        }
        // Fallback: search the Java index for the type declaration.
        try {
            org.eclipse.jdt.core.search.SearchPattern pattern =
                    org.eclipse.jdt.core.search.SearchPattern.createPattern(
                            fullyQualifiedName,
                            org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
                            org.eclipse.jdt.core.search.IJavaSearchConstants.DECLARATIONS,
                            org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH);
            final IType[] found = new IType[1];
            new org.eclipse.jdt.core.search.SearchEngine().search(pattern,
                    new org.eclipse.jdt.core.search.SearchParticipant[]{
                            org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant()},
                    org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
                    new org.eclipse.jdt.core.search.SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(
                                org.eclipse.jdt.core.search.SearchMatch match) {
                            if (found[0] == null
                                    && match.getElement() instanceof IType type) {
                                found[0] = type;
                            }
                        }
                    }, new NullProgressMonitor());
            if (found[0] != null) {
                openQuietly(found[0]);
                return found[0];
            }
        } catch (Exception ignored) {
            // fall through to the structured error
        }
        throw new ToolingException("Type not found in project " + handle.project().getName()
                + ": " + fullyQualifiedName);
    }

    /**
     * Compiles all sources of a project with the Eclipse batch compiler and returns the
     * structured problems. Uses the project's resolved classpath (real dependency
     * checking, including missing types).
     */
    public List<ProblemRecord> diagnostics(ProjectHandle handle) {
        ensureStarted();
        List<java.nio.file.Path> sources = collectSources(handle);
        if (sources.isEmpty()) {
            DCodeLog.warn("java.tooling", "No source files found for diagnostics in "
                    + handle.project().getName() + " (sourceDirs="
                    + sourceDirectories(handle) + ")");
            return List.of();
        }
        java.nio.file.Path outDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "dcode-jdt-out-" + Long.toHexString(System.nanoTime()));
        try {
            Files.createDirectories(outDir);
            List<String> args = new ArrayList<>();
            args.add("-21");
            args.add("-proc:none");
            args.add("-d");
            args.add(outDir.toString());
            args.add("-cp");
            args.add(classpathString(handle));
            for (java.nio.file.Path source : sources) {
                args.add(quote(source.toString()));
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            PrintWriter out = new PrintWriter(stdout, true, StandardCharsets.UTF_8);
            PrintWriter err = new PrintWriter(stderr, true, StandardCharsets.UTF_8);
            try {
                org.eclipse.jdt.core.compiler.batch.BatchCompiler.compile(
                        String.join(" ", args), out, err, null);
            } finally {
                out.flush();
                err.flush();
            }
            return ProblemParser.parse(stdout.toString(StandardCharsets.UTF_8)
                    + System.lineSeparator() + stderr.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ToolingException("Diagnostics failed: " + e.getMessage(), e);
        } finally {
            deleteRecursively(outDir);
        }
    }

    /** Resolves a possibly project-relative entry path against the project location. */
    private static java.nio.file.Path absolutePath(ProjectHandle handle, IPath path) {
        if (path == null) {
            return null;
        }
        if (path.isAbsolute()) {
            // Workspace-absolute classpath paths start with the project name; resolve
            // them against the project location (which may be outside the workspace).
            if (path.segmentCount() > 0
                    && path.segment(0).equals(handle.project().getName())) {
                IPath location = handle.project().getLocation();
                if (location == null) {
                    return null;
                }
                return java.nio.file.Path.of(
                        location.append(path.removeFirstSegments(1)).toOSString());
            }
            return java.nio.file.Path.of(path.toOSString());
        }
        IPath location = handle.project().getLocation();
        if (location == null) {
            return null;
        }
        return java.nio.file.Path.of(location.append(path).toOSString());
    }

    /** Classpath of a project as a classpath-string (for AST/batch compiler). */
    public String classpathString(ProjectHandle handle) {
        List<String> entries = new ArrayList<>();
        for (IClasspathEntry entry : resolvedClasspath(handle)) {
            java.nio.file.Path file = absolutePath(handle, entry.getPath());
            if (file != null && java.nio.file.Files.exists(file)
                    && !"jrt-fs.jar".equals(file.getFileName().toString())) {
                entries.add(file.toString());
            }
        }
        entries.add(java.nio.file.Path.of(System.getProperty("java.home"), "lib", "jrt-fs.jar").toString());
        return String.join(java.io.File.pathSeparator, entries);
    }

    /** Source directories of a project (absolute paths). */
    public List<java.nio.file.Path> sourceDirectories(ProjectHandle handle) {
        List<java.nio.file.Path> result = new ArrayList<>();
        for (IClasspathEntry entry : resolvedClasspath(handle)) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                java.nio.file.Path file = absolutePath(handle, entry.getPath());
                if (file != null && java.nio.file.Files.isDirectory(file)) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    /** Raw classpath entries of a project. */
    public IClasspathEntry[] resolvedClasspath(ProjectHandle handle) {
        try {
            return handle.javaProject().getResolvedClasspath(true);
        } catch (Exception e) {
            DCodeLog.warn("java.tooling", "getResolvedClasspath failed for "
                    + handle.project().getName() + ": " + e);
            return new IClasspathEntry[0];
        }
    }

    /** All imported handles of one repository. */
    public List<ProjectHandle> projectsOf(String repositoryName) {
        List<ProjectHandle> result = new ArrayList<>();
        for (ProjectHandle handle : projectsByKey.values()) {
            if (handle.repositoryName().equals(repositoryName)) {
                result.add(handle);
            }
        }
        return result;
    }

    public Collection<ProjectHandle> allProjects() {
        return List.copyOf(projectsByKey.values());
    }

    // ---- internals ----

    private static IProject findProjectAtLocation(java.nio.file.Path moduleDir) {
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            try {
                IPath location = candidate.getLocation();
                if (location != null && candidate.isOpen()
                        && java.nio.file.Path.of(location.toOSString()).normalize()
                        .equals(moduleDir.normalize())) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // skip unreadable projects
            }
        }
        return null;
    }

    private ProjectHandle importProject(Workspace workspace, RepositoryRef repository,
                                        java.nio.file.Path repoDir, java.nio.file.Path moduleDir) throws Exception {
        String key = key(repository.name(), moduleDir);
        ProjectHandle existing = projectsByKey.get(key);
        if (existing != null && existing.project().exists()) {
            return existing;
        }
        JavaProjectKind kind = detectKind(moduleDir);
        String projectName = projectName(repository, repoDir, moduleDir);

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        boolean adopted = false;
        if (!project.exists()) {
            // Another manager instance (or a previous runtime) may already have
            // imported this module directory under a different project name.
            IProject existingAtLocation = findProjectAtLocation(moduleDir);
            if (existingAtLocation != null && existingAtLocation.isOpen()) {
                project = existingAtLocation;
                adopted = true;
            }
        }
        if (!adopted && project.exists()) {
            // The Eclipse workspace outlives one runtime/test; a project with the same
            // name may point at a stale location. Recreate it when locations differ;
            // adopt it when it already maps to this module directory.
            IPath currentLocation = project.getLocation();
            boolean sameLocation = currentLocation != null
                    && java.nio.file.Path.of(currentLocation.toOSString()).normalize()
                    .equals(moduleDir.normalize());
            if (sameLocation && project.isOpen()) {
                adopted = true;
            } else {
                try {
                    project.delete(true, false, new NullProgressMonitor());
                } catch (CoreException ignored) {
                    // recreate below; stale project is unusable anyway
                }
            }
        }
        if (!adopted && !project.exists()) {
            IProjectDescription description = ResourcesPlugin.getWorkspace()
                    .newProjectDescription(projectName);
            description.setLocation(new Path(moduleDir.toString()));
            description.setNatureIds(new String[]{JavaCore.NATURE_ID});
            org.eclipse.core.resources.ICommand buildCommand = description.newCommand();
            buildCommand.setBuilderName(JavaCore.BUILDER_ID);
            description.setBuildSpec(new org.eclipse.core.resources.ICommand[]{buildCommand});
            project.create(description, new NullProgressMonitor());
        }
        if (!project.isOpen()) {
            project.open(new NullProgressMonitor());
        }
        IJavaProject javaProject = JavaCore.create(project);
        javaProject.setOption(JavaCore.COMPILER_SOURCE, "21");
        javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21");
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, "21");

        ProjectHandle handle = new ProjectHandle(repository.name(), moduleDir.toString(),
                project, javaProject, kind);
        projectsByKey.put(key, handle);
        return handle;
    }

    private void applyClasspath(ProjectHandle handle, List<ProjectHandle> reactor)
            throws Exception {
        List<IClasspathEntry> entries = new ArrayList<>();
        // JDK via the running JVM image (headless-safe, no jdt.launching).
        java.nio.file.Path jrt = java.nio.file.Path.of(System.getProperty("java.home"), "lib", "jrt-fs.jar");
        entries.add(JavaCore.newLibraryEntry(new Path(jrt.toString()), null, null, false));

        IPath projectLocation = handle.project().getLocation();
        for (java.nio.file.Path source : sourceDirectoriesOfKind(handle)) {
            IPath relativeSource = projectLocation != null
                    ? new Path(source.toString()).makeRelativeTo(projectLocation)
                    : new Path(source.toString());
            // JDT classpath entry paths are workspace-absolute (project-relative).
            IPath entryPath = new Path("/").append(handle.project().getName())
                    .append(relativeSource);
            entries.add(JavaCore.newSourceEntry(entryPath, null));
        }
        java.nio.file.Path output = java.nio.file.Path.of(handle.moduleDir(), "target", "classes");

        if (handle.kind() == JavaProjectKind.MAVEN) {
            for (String jar : mavenClasspath(handle.moduleDir())) {
                java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
                if (Files.isRegularFile(jarPath)) {
                    entries.add(JavaCore.newLibraryEntry(new Path(jar), null, null, false));
                }
            }
            for (ProjectHandle sibling : reactor) {
                if (sibling != handle && sibling.kind() == JavaProjectKind.MAVEN) {
                    entries.add(JavaCore.newProjectEntry(
                            new Path("/" + sibling.project().getName())));
                }
            }
        } else if (handle.kind() == JavaProjectKind.ECLIPSE) {
            // The .classpath file is read from the project location itself.
            IFile classpathFile = handle.project().getFile(".classpath");
            if (classpathFile.exists()) {
                entries.clear();
                entries.addAll(java.util.Arrays.asList(
                        handle.javaProject().readRawClasspath()));
            }
        }
        handle.javaProject().setRawClasspath(entries.toArray(new IClasspathEntry[0]),
                new NullProgressMonitor());
        handle.javaProject().setOutputLocation(new Path("/")
                .append(handle.project().getName()).append("target/classes"),
                new NullProgressMonitor());
        DCodeLog.info("java.tooling", "Classpath set for " + handle.project().getName()
                + ": " + entries.size() + " entries, sources="
                + sourceDirectoriesOfKind(handle) + " verify="
                + java.util.Arrays.toString(handle.javaProject().readRawClasspath()));
        handle.project().build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
    }

    private List<java.nio.file.Path> sourceDirectoriesOfKind(ProjectHandle handle) {
        List<java.nio.file.Path> result = new ArrayList<>();
        java.nio.file.Path module = java.nio.file.Path.of(handle.moduleDir());
        if (handle.kind() == JavaProjectKind.MAVEN) {
            addIfExists(result, module.resolve("src/main/java"));
            addIfExists(result, module.resolve("src/test/java"));
        } else {
            addIfExists(result, module.resolve("src"));
        }
        if (result.isEmpty()) {
            addIfExists(result, module);
        }
        return result;
    }

    private static void addIfExists(List<java.nio.file.Path> result, java.nio.file.Path dir) {
        if (Files.isDirectory(dir)) {
            result.add(dir);
        }
    }

    private JavaProjectKind detectKind(java.nio.file.Path moduleDir) {
        if (Files.isRegularFile(moduleDir.resolve("pom.xml"))) {
            return JavaProjectKind.MAVEN;
        }
        if (Files.isRegularFile(moduleDir.resolve(".project"))
                && (Files.isRegularFile(moduleDir.resolve(".classpath"))
                || Files.isRegularFile(moduleDir.resolve(".settings/jdt.core.prefs")))) {
            return JavaProjectKind.ECLIPSE;
        }
        return JavaProjectKind.OSGI;
    }

    private List<java.nio.file.Path> discoverModules(java.nio.file.Path repoDir) {
        List<java.nio.file.Path> modules = new ArrayList<>();
        discoverModules(repoDir, repoDir, 0, modules);
        return modules;
    }

    private void discoverModules(java.nio.file.Path root, java.nio.file.Path dir, int depth,
                                 List<java.nio.file.Path> modules) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        if (isSkippedDirectory(name)) {
            return;
        }
        if (isJavaModule(dir, root)) {
            modules.add(dir);
            return; // module boundary: do not descend into nested modules here
        }
        try (var stream = Files.list(dir)) {
            for (java.nio.file.Path child : stream.filter(Files::isDirectory).toList()) {
                discoverModules(root, child, depth + 1, modules);
            }
        } catch (IOException ignored) {
            // unreadable directory: skip
        }
    }

    private boolean isJavaModule(java.nio.file.Path dir, java.nio.file.Path repoRoot) {
        boolean maven = Files.isRegularFile(dir.resolve("pom.xml"));
        boolean eclipse = Files.isRegularFile(dir.resolve(".project"))
                && Files.isRegularFile(dir.resolve(".classpath"));
        boolean osgi = !dir.equals(repoRoot)
                && Files.isRegularFile(dir.resolve("META-INF/MANIFEST.MF"))
                && !maven && !eclipse;
        if (eclipse || osgi) {
            return true;
        }
        return maven && (Files.isDirectory(dir.resolve("src/main/java"))
                || Files.isDirectory(dir.resolve("src/test/java"))
                || Files.isDirectory(dir.resolve("src/java")));
    }

    private static boolean isSkippedDirectory(String name) {
        return name.equals(".git") || name.equals("target") || name.equals("build")
                || name.equals("node_modules") || name.equals(".mvn") || name.equals("bin")
                || name.equals("out") || name.equals(".settings");
    }

    private List<String> mavenClasspath(String moduleDir) {
        String cached = mavenClasspathCache.get(moduleDir);
        if (cached != null) {
            List<String> result = new ArrayList<>();
            for (String part : cached.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    result.add(part);
                }
            }
            return result;
        }
        java.nio.file.Path module = java.nio.file.Path.of(moduleDir);
        java.nio.file.Path outputFile = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "dcode-cp-" + Integer.toHexString(moduleDir.hashCode()) + ".txt");
        List<String> result = new ArrayList<>();
        try {
            Files.deleteIfExists(outputFile);
            String mvn = Files.isExecutable(module.resolve("mvnw"))
                    ? module.resolve("mvnw").toString() : "mvn";
            List<String> command = List.of(mvn, "-q", "-B", "dependency:build-classpath",
                    "-Dmdep.outputFile=" + outputFile.toAbsolutePath(),
                    "-DincludeScope=test");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(module.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            ByteArrayOutputStream drain = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(drain);
                } catch (IOException ignored) {
                    // process died
                }
            });
            reader.start();
            if (!process.waitFor(MAVEN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                DCodeLog.warn("java.tooling", "Maven classpath resolution timed out for "
                        + moduleDir + "; falling back to pom parsing");
                result = classpathFromPom(module);
            } else {
                if (Files.isRegularFile(outputFile)) {
                    for (String part : Files.readString(outputFile).trim()
                            .split(java.io.File.pathSeparator)) {
                        if (!part.isBlank()) {
                            result.add(part);
                        }
                    }
                }
                if (result.isEmpty()) {
                    result = classpathFromPom(module);
                }
            }
        } catch (Exception e) {
            DCodeLog.warn("java.tooling", "Maven classpath resolution failed for "
                    + moduleDir + ": " + e.getMessage() + "; falling back to pom parsing");
            result = classpathFromPom(module);
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
                // best effort
            }
        }
        String joined = String.join(java.io.File.pathSeparator, result);
        mavenClasspathCache.put(moduleDir, joined);
        return result;
    }

    /** Fallback: resolve pom dependencies against the local Maven repository. */
    private List<String> classpathFromPom(java.nio.file.Path moduleDir) {
        List<String> result = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document document = factory.newDocumentBuilder()
                    .parse(moduleDir.resolve("pom.xml").toFile());
            org.w3c.dom.NodeList dependencies = document.getElementsByTagName("dependency");
            String localRepo = java.nio.file.Path.of(System.getProperty("user.home"), ".m2",
                    "repository").toString();
            for (int i = 0; i < dependencies.getLength(); i++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) dependencies.item(i);
                String groupId = childText(element, "groupId");
                String artifactId = childText(element, "artifactId");
                String version = childText(element, "version");
                if (version.isBlank()) {
                    version = document.getElementsByTagName("version").item(0) != null
                            ? childText((org.w3c.dom.Element)
                            document.getElementsByTagName("version").item(0), "") : "";
                }
                if (!groupId.isBlank() && !artifactId.isBlank() && !version.isBlank()) {
                    java.nio.file.Path jar = java.nio.file.Path.of(localRepo,
                            groupId.replace('.', '/'), artifactId, version,
                            artifactId + "-" + version + ".jar");
                    if (Files.isRegularFile(jar)) {
                        result.add(jar.toString());
                    }
                }
            }
        } catch (Exception ignored) {
            // no usable pom: empty classpath
        }
        return result;
    }

    private static String childText(org.w3c.dom.Element parent, String tagName) {
        org.w3c.dom.NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) {
            return "";
        }
        return children.item(0).getTextContent().trim();
    }

    private List<java.nio.file.Path> collectSources(ProjectHandle handle) {
        List<java.nio.file.Path> result = new ArrayList<>();
        for (java.nio.file.Path sourceDir : sourceDirectories(handle)) {
            try (var stream = Files.walk(sourceDir)) {
                for (java.nio.file.Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (result.size() >= MAX_SOURCE_FILES) {
                        return result;
                    }
                    result.add(file);
                }
            } catch (IOException ignored) {
                // unreadable source dir
            }
        }
        return result;
    }

    private String projectName(RepositoryRef repository, java.nio.file.Path repoDir,
                                java.nio.file.Path moduleDir) {
        String relative = repoDir.relativize(moduleDir).toString().replace('/', '_')
                .replace('\\', '_').replace('.', '_');
        String base = repository.name() + "__" + (relative.isBlank() ? "root" : relative)
                + "_" + instanceId;
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    private static String key(String repositoryName, java.nio.file.Path moduleDir) {
        return repositoryName + "|" + moduleDir;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void deleteRecursively(java.nio.file.Path root) {
        try (var stream = Files.walk(root)) {
            for (java.nio.file.Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best effort cleanup of the scratch output directory
        }
    }

    /** Rebuilds the Java index of a project (after refactorings). */
    public void rebuildIndex(ProjectHandle handle) {
        try {
            handle.project().build(IncrementalProjectBuilder.FULL_BUILD,
                    new NullProgressMonitor());
        } catch (CoreException e) {
            throw new ToolingException("Index rebuild failed for "
                    + handle.project().getName(), e);
        }
    }

    private static void openQuietly(IType type) {
        try {
            if (type instanceof org.eclipse.jdt.core.IOpenable openable) {
                openable.open(new NullProgressMonitor());
            }
        } catch (Exception ignored) {
            // structure reads fall back to whatever the model already has
        }
    }

    /**
     * Returns the compilation unit for a workspace-relative .java path. The path is
     * resolved against the workspace root and must fall inside the project module.
     */
    public ICompilationUnit compilationUnit(ProjectHandle handle, String workspaceRoot,
                                            String workspaceRelativePath) {
        java.nio.file.Path file = java.nio.file.Path.of(workspaceRoot,
                workspaceRelativePath).normalize();
        if (!Files.isRegularFile(file)) {
            throw new ToolingException("No such Java source: " + workspaceRelativePath);
        }
        java.nio.file.Path module = java.nio.file.Path.of(handle.moduleDir()).normalize();
        if (!file.startsWith(module)) {
            throw new ToolingException("Source file is outside the project module: "
                    + workspaceRelativePath);
        }
        IFile resource = handle.project().getFile(
                new Path(module.relativize(file).toString()));
        if (resource == null || !resource.exists()) {
            throw new ToolingException("Source file is not part of the Eclipse project: "
                    + workspaceRelativePath);
        }
        return JavaCore.createCompilationUnitFrom(resource);
    }
}
