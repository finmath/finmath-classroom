package org.finmath.classroom.feedback;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** Read-only access to repository permissions, workflow metadata and feedback artifact archives. */
public interface FeedbackSource {

    record Repository(long id, String name, boolean privateRepository, boolean fork) { }

    /** Verified GitHub account identity and its base repository permission. */
    record Access(long userId, String login, String permission) { }

    /** workflowPath is the canonical file path, without GitHub's optional @ref suffix. */
    record Run(long id, int attempt, long repositoryId, String repository, String branch, String commit,
            String event, String status, String conclusion, String workflowPath, long workflowId, Instant createdAt) { }

    record Artifact(long id, String name, long runId, long repositoryId, String commit,
            boolean expired, long sizeBytes, String digest) { }

    Repository repository(String fullName) throws IOException;

    /** Reads effective repository access; returns null when GitHub responds with HTTP 404. */
    Access access(String repository, String login) throws IOException;

    /** Lists branch-bearing push runs, scanning the workflow's unfiltered run history. */
    List<Run> runs(String repository, String workflowFile) throws IOException;

    Run attempt(String repository, long runId, int attempt) throws IOException;

    List<Artifact> artifacts(String repository, long runId) throws IOException;

    byte[] download(String repository, long artifactId) throws IOException;

    /** Sanitized failure suitable for reports; a retry time is present only for rate limits. */
    final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final Instant retryAt;

        public Failure(String message, int status, Instant retryAt) {
            super(message);
            this.status = status;
            this.retryAt = retryAt;
        }

        public int status() { return status; }

        public Instant retryAt() { return retryAt; }
    }
}
