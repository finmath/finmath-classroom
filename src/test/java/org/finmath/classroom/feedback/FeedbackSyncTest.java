package org.finmath.classroom.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.finmath.classroom.feedback.FeedbackSource.Artifact;
import org.finmath.classroom.feedback.FeedbackSource.Run;
import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.EnrollmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.node.ObjectNode;

class FeedbackSyncTest {
    @TempDir Path directory;
    private final Instant now = Instant.parse("2026-10-19T12:00:00Z");
    private final String sha = "0123456789abcdef0123456789abcdef01234567";
    private final String repository = "example-org/assignment-student";
    private final FakeSource source = new FakeSource();
    private ClassroomConfig config;
    private EnrollmentStore enrollments;
    private FeedbackStore store;

    @BeforeEach void prepare() throws Exception {
        var assignment = new ClassroomConfig.Assignment("task", "Task", "example-org/template", "task", "main", null, "assignment-key", null, null);
        config = new ClassroomConfig(List.of(new ClassroomConfig.Lecture("lecture", "Lecture", "example-org", null,
                "lecture-key", List.of(), List.of(assignment))));
        enrollments = new EnrollmentStore(directory.resolve("classroom.db"), true);
        enrollments.importConfiguration(config, null);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO students VALUES ('lecture','alice','Alice',42,'alice','self','42')");
            statement.executeUpdate("INSERT INTO enrollments (lecture_id,assignment_id,student_id,accepted_name,identity_source,identity_subject,accepted_at,declaration_version,declaration,repository,repository_id,status) VALUES ('lecture','task','alice','Alice','self','42','2026-10-19T10:00:00Z','1','accepted','example-org/assignment-student',123456,'active')");
        }
        store = new FeedbackStore(directory, true);
    }

    private FeedbackSync syncer() { return new FeedbackSync(config, enrollments, store, source, Clock.fixed(now, ZoneOffset.UTC)); }
    private FeedbackSync.Report sync() throws IOException { return syncer().sync(null, null, "classroom.yml"); }
    private Run run(int attempt, String state) {
        return new Run(987654, attempt, 123456, repository, "main", sha, "push", state,
                state.equals("completed") ? "failure" : null, ".github/workflows/classroom.yml", 55, now.minusSeconds(86400 * 60));
    }

    private byte[] archive(int attempt, boolean wrongCommit) throws Exception {
        ObjectNode result = (ObjectNode) JsonFiles.read(Path.of("examples/grading/result.json"), 1024 * 1024);
        ((ObjectNode) result.path("context")).put("run_attempt", attempt);
        if (wrongCommit) ((ObjectNode) result.path("context")).put("commit", "a".repeat(40));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("result.json"));
            zip.write(JsonFiles.MAPPER.writeValueAsBytes(result));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private Artifact addArtifact(int attempt, boolean expired, boolean wrongCommit) throws Exception {
        byte[] bytes = archive(attempt, wrongCommit);
        Artifact artifact = new Artifact(attempt, "classroom-feedback-987654-" + attempt, 987654, 123456, sha, expired, bytes.length, null);
        source.artifacts.add(artifact);
        source.archives.put((long) attempt, bytes);
        return artifact;
    }

    @Test void collectsFailureAndSurvivesRepeatedCollectionAndUpstreamDeletion() throws Exception {
        source.runs = List.of(run(1, "completed"));
        addArtifact(1, false, false);
        var report = sync();
        assertEquals(1, report.received());
        assertEquals(0, report.errors());
        assertEquals(15, store.result(123456, 987654, 1).path("score").asInt());
        assertEquals("failed", store.list(null, null).get(0).get("feedbackStatus"));
        source.artifacts.clear();
        store = new FeedbackStore(directory, true);
        assertEquals(1, sync().alreadyStored());
        assertEquals(1, source.downloads);
        assertEquals(1, source.artifactRequests);
        assertEquals(15, new FeedbackStore(directory, false).result(123456, 987654, 1).path("score").asInt());
    }

    @Test void oldRunRerunInProgressStillRecoversPreviousAttemptThenNewAttempt() throws Exception {
        source.runs = List.of(run(2, "in_progress"));
        source.attempts.put(1, run(1, "completed"));
        addArtifact(1, false, false);
        assertEquals(1, sync().received());
        assertEquals("pending", store.list(null, null).get(0).get("collectionStatus"));
        source.runs = List.of(run(2, "completed"));
        addArtifact(2, false, false);
        var report = sync();
        assertEquals(1, report.received());
        assertEquals(1, report.alreadyStored());
        assertEquals(2, store.list(null, null).size());
        assertTrue(store.received(123456, 987654, 1));
        assertTrue(store.received(123456, 987654, 2));
        assertEquals(1, source.attemptRequests);
    }

    @Test void missingFeedbackIsNullAndRetriedWhenItArrives() throws Exception {
        source.runs = List.of(run(1, "completed"));
        assertEquals(1, sync().unavailable());
        var row = store.list(null, null).get(0);
        assertEquals("missing", row.get("collectionStatus"));
        assertNull(row.get("score"));
        addArtifact(1, false, false);
        assertEquals(1, sync().received());
        assertEquals(15, store.result(123456, 987654, 1).path("score").asInt());
    }

    @Test void unavailableOldAttemptDoesNotBlockNewerResultAndIsRetriedLater() throws Exception {
        source.runs = List.of(run(2, "completed"));
        source.attemptFailure = new FeedbackSource.Failure("private response", 404, null);
        addArtifact(2, false, false);
        var report = sync();
        assertEquals(1, report.errors());
        assertEquals(1, report.received());
        assertTrue(store.received(123456, 987654, 2));
        var older = store.list(null, null).get(1);
        assertEquals("unavailable", older.get("collectionStatus"));
        assertEquals("unknown", older.get("runStatus"));
        assertNull(older.get("conclusion"));
        assertNull(older.get("score"));
        assertFalse(older.toString().contains("private response"));
        source.attemptFailure = null;
        source.attempts.put(1, run(1, "completed"));
        addArtifact(1, false, false);
        assertEquals(1, sync().received());
        assertTrue(store.received(123456, 987654, 1));
    }

    @Test void replacedRepositoryIsNeverReadForArtifacts() throws Exception {
        source.repositoryId = 999;
        source.runs = List.of(run(1, "completed"));
        assertEquals(1, sync().errors());
        assertEquals(0, source.runRequests);
        assertTrue(store.list(null, null).isEmpty());
        assertEquals("unavailable", store.checks(null, null).get(0).get("status"));
    }

    @Test void onlyConfiguredBranchPushesAreCollected() throws Exception {
        Run base = run(1, "completed");
        source.runs = List.of(new Run(base.id(), 1, base.repositoryId(), repository, "main", sha, "pull_request", "completed", "failure", base.workflowPath(), 55, now),
                new Run(base.id(), 1, base.repositoryId(), repository, "other", sha, "push", "completed", "failure", base.workflowPath(), 55, now));
        assertEquals(0, sync().received());
        assertEquals(0, source.artifactRequests);
        assertTrue(store.list(null, null).isEmpty());
    }

    @Test void mismatchedResultIdentityIsInvalidNotZero() throws Exception {
        source.runs = List.of(run(1, "completed"));
        addArtifact(1, false, true);
        assertEquals(1, sync().errors());
        var row = store.list(null, null).get(0);
        assertEquals("invalid", row.get("collectionStatus"));
        assertNull(row.get("score"));
        assertFalse(store.received(123456, 987654, 1));
    }

    @Test void expiredArtifactsAreVisibleWithoutDownload() throws Exception {
        source.runs = List.of(run(1, "completed"));
        addArtifact(1, true, false);
        assertEquals(1, sync().unavailable());
        assertEquals("expired", store.list(null, null).get(0).get("collectionStatus"));
        assertEquals(0, source.downloads);
    }

    @Test void duplicateArtifactNamesAreRejected() throws Exception {
        source.runs = List.of(run(1, "completed"));
        Artifact artifact = addArtifact(1, false, false);
        source.artifacts.add(artifact);
        assertEquals(1, sync().errors());
        assertEquals("invalid", store.list(null, null).get(0).get("collectionStatus"));
        assertEquals(0, source.downloads);
    }

    @Test void rateLimitDelaySurvivesRestartAndSuppressesRequestsUntilDue() throws Exception {
        source.failure = new FeedbackSource.Failure("secret response body", 429, now.plusSeconds(900));
        var report = sync();
        assertTrue(report.deferred());
        assertEquals(1, report.errors());
        store = new FeedbackStore(directory, true);
        assertTrue(sync().deferred());
        assertEquals(1, source.runRequests);
        assertFalse(store.checks(null, null).toString().contains("secret"));
        source.failure = null;
        var later = new FeedbackSync(config, enrollments, store, source, Clock.fixed(now.plusSeconds(901), ZoneOffset.UTC));
        assertFalse(later.sync(null, null, "classroom.yml").deferred());
        assertEquals(2, source.runRequests);
    }

    @Test void invalidWorkflowRejectedBeforeNetworkOrWrites() {
        assertThrows(IllegalArgumentException.class, () -> syncer().sync(null, null, "../private.yml"));
        assertEquals(0, source.repositoryRequests);
    }

    private final class FakeSource implements FeedbackSource {
        long repositoryId = 123456;
        int repositoryRequests, runRequests, artifactRequests, attemptRequests, downloads;
        List<Run> runs = List.of();
        List<Artifact> artifacts = new ArrayList<>();
        Map<Integer, Run> attempts = new HashMap<>();
        Map<Long, byte[]> archives = new HashMap<>();
        Failure failure, attemptFailure;

        public Repository repository(String name) { repositoryRequests++; return new Repository(repositoryId, name, true, false); }
        public List<Run> runs(String name, String workflow) throws IOException {
            assertEquals("classroom.yml", workflow);
            runRequests++;
            if (failure != null) throw failure;
            return runs;
        }
        public Run attempt(String name, long run, int attempt) throws IOException {
            attemptRequests++;
            if (attemptFailure != null) throw attemptFailure;
            return attempts.get(attempt);
        }
        public List<Artifact> artifacts(String name, long run) { artifactRequests++; return List.copyOf(artifacts); }
        public byte[] download(String name, long artifact) { downloads++; return archives.get(artifact); }
    }
}
