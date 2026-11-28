package eu.kalafatic.dcode.core.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Evidence-based verification results, bound to a specific change-set version (or to a
 * no-change execution). Verification is recorded independently of review approval.
 */
public interface VerificationService {

    /** Verification verdict. */
    enum Verdict {
        PENDING,
        VERIFIED,
        VERIFIED_WITH_LIMITATIONS,
        NEEDS_REWORK,
        FAILED,
        BLOCKED
    }

    /** One verification record. */
    record Verification(
            String id,
            String sessionId,
            String changeSetId,
            Verdict verdict,
            String evidence,
            Instant createdAt
    ) {
    }

    Verification record(String sessionId, String changeSetId, Verdict verdict, String evidence);

    Optional<Verification> latestForChangeSet(String changeSetId);

    List<Verification> listBySession(String sessionId);
}
