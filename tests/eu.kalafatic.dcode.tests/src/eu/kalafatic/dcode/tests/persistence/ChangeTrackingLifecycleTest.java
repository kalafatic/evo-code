package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.DiffHunks;
import eu.kalafatic.dcode.core.model.FileChange;
import eu.kalafatic.dcode.core.model.FileChangeStatus;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.VerificationService;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteChangeSetService;
import eu.kalafatic.dcode.persistence.service.SqliteExecutionAuditService;
import eu.kalafatic.dcode.persistence.service.SqliteReviewService;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteVerificationService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies durable change-set persistence (versioning, diffs), review decisions and the
 * single authoritative completion decision.
 */
class ChangeTrackingLifecycleTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private SqliteWorkspaceService workspaces;
    private SqliteSessionService sessions;
    private SqliteChangeSetService changeSets;
    private SqliteVerificationService verifications;
    private SqliteReviewService reviews;
    private String sessionId;
    private String repositoryId;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabaseManager(temp.resolve("changes.db"));
        new SchemaMigrationService(database).migrate();
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        workspaces = new SqliteWorkspaceService(database, bus);
        reviews = new SqliteReviewService(database);
        SqliteExecutionAuditService audit = new SqliteExecutionAuditService(database);
        changeSets = new SqliteChangeSetService(database);
        verifications = new SqliteVerificationService(database);
        sessions = new SqliteSessionService(database, bus, workspaces, changeSets,
                verifications, reviews, audit);

        var workspace = workspaces.createWorkspace("changes", WorkspaceMode.LOCAL,
                temp.resolve("ws").toString());
        repositoryId = "repo-1";
        Session session = sessions.createSession(new SessionService.CreateSessionRequest(
                "changes", workspace.id(), null, null, AgentRole.MAIN, null));
        sessionId = session.getId();
    }

    @Test
    void changeSetVersionsPersistWithDiffsAndSurviveRestart() {
        ChangeSet v1 = changeSets.persist(new ChangeSet("cs-1", sessionId, repositoryId,
                "v1", List.of(
                        new FileChange("A.java", null, FileChangeStatus.MODIFIED,
                                DiffHunks.parse("@@ -1,1 +1,1 @@\n-old\n+new"), "@@ -1,1 +1,1 @@\n-old\n+new"),
                        new FileChange("B.java", null, FileChangeStatus.ADDED, List.of(),
                                "--- /dev/null\n+++ b/B.java\n+line1\n+line2")),
                Instant.now(), 0, null, "run-1", "FINALIZED"));
        assertEquals(1, v1.version());

        ChangeSet v2 = changeSets.persist(new ChangeSet("cs-2", sessionId, repositoryId,
                "v2", List.of(
                        new FileChange("A.java", null, FileChangeStatus.MODIFIED,
                                List.of(), "@@ -1,1 +1,1 @@\n-new\n+newer")),
                Instant.now(), 0, null, "run-2", "FINALIZED"));
        assertEquals(2, v2.version());
        assertEquals("cs-1", v2.predecessorId());

        // Simulated restart: new store over the same database.
        SqliteChangeSetService reopened = new SqliteChangeSetService(database);
        assertEquals(2, reopened.listBySession(sessionId).size());
        ChangeSet loaded = reopened.latest(sessionId, repositoryId).orElseThrow();
        assertEquals(2, loaded.version());
        assertTrue(loaded.files().get(0).diff().contains("+newer"));
        assertEquals("MODIFIED", reopened.find("cs-1").orElseThrow().files().get(0).status().name());
        assertEquals("ADDED", reopened.find("cs-1").orElseThrow().files().get(1).status().name());
    }

    @Test
    void completionRequiresApprovalAndVerification() {
        ChangeSet changeSet = changeSets.persist(new ChangeSet("cs-c", sessionId, repositoryId,
                "change", List.of(new FileChange("A.java", null, FileChangeStatus.MODIFIED,
                        List.of(), "@@ -1,1 +1,1 @@\n-a\n+b")),
                Instant.now(), 0, null, "run-c", "FINALIZED"));

        sessions.transition(sessionId, SessionState.RUNNING);
        sessions.transition(sessionId, SessionState.WAITING_FOR_APPROVAL);

        ReviewService.Review review = reviews.createReview(sessionId, changeSet.id());
        // Approval alone (no verification) must not complete.
        reviews.approve(review.id(), "tester", "looks fine");
        sessions.evaluateCompletion(sessionId);
        assertEquals(SessionState.WAITING_FOR_APPROVAL,
                sessions.getSession(sessionId).orElseThrow().getState());

        // With verification, completion is granted.
        verifications.record(sessionId, changeSet.id(),
                VerificationService.Verdict.VERIFIED_WITH_LIMITATIONS, "build+test passed");
        sessions.evaluateCompletion(sessionId);
        assertEquals(SessionState.COMPLETED,
                sessions.getSession(sessionId).orElseThrow().getState());
    }

    @Test
    void rejectionSendsBackForReworkAndPreservesHistory() {
        ChangeSet v1 = changeSets.persist(new ChangeSet("cs-r1", sessionId, repositoryId,
                "v1", List.of(new FileChange("A.java", null, FileChangeStatus.MODIFIED,
                        List.of(), "@@ -1 +1 @@\n-x\n+y")),
                Instant.now(), 0, null, "run-r1", "FINALIZED"));
        ReviewService.Review review = reviews.createReview(sessionId, v1.id());
        reviews.reject(review.id(), "reviewer", "wrong approach");

        // Rework produces a new version; the rejected review remains.
        ChangeSet v2 = changeSets.persist(new ChangeSet("cs-r2", sessionId, repositoryId,
                "v2", List.of(new FileChange("A.java", null, FileChangeStatus.MODIFIED,
                        List.of(), "@@ -1 +1 @@\n-y\n+z")),
                Instant.now(), 0, null, "run-r2", "FINALIZED"));
        assertEquals(2, v2.version());

        assertEquals(ReviewService.Status.REJECTED, reviews.find(review.id()).orElseThrow().status());
        assertEquals(2, changeSets.listBySession(sessionId).size());
        assertNotNull(reviews.find(review.id()).orElseThrow().decidedAt());
    }
}
