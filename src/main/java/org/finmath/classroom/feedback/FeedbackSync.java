package org.finmath.classroom.feedback;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.finmath.classroom.feedback.FeedbackSource.Artifact;
import org.finmath.classroom.feedback.FeedbackSource.Failure;
import org.finmath.classroom.feedback.FeedbackSource.Run;
import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.EnrollmentStore;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;

/** Pulls student feedback without executing student code or holding a database transaction over HTTP. */
public final class FeedbackSync {
    public record Report(int repositories, int received, int alreadyStored, int unavailable,
            int errors, boolean deferred, Instant retryAt) { }

    private final ClassroomConfig config;
    private final EnrollmentStore enrollments;
    private final FeedbackStore store;
    private final FeedbackSource github;
    private final Clock clock;

    public FeedbackSync(ClassroomConfig config, EnrollmentStore enrollments, FeedbackStore store,
            FeedbackSource github, Clock clock) {
        this.config = config;
        this.enrollments = enrollments;
        this.store = store;
        this.github = github;
        this.clock = clock;
    }

    public Report sync(String lectureId, String assignmentId, String workflow) throws IOException {
        String file = workflowFile(workflow);
        enrollments.verifyConfiguration(config);
        Instant deferred = store.deferredUntil();
        if (deferred != null && clock.instant().isBefore(deferred)) return new Report(0, 0, 0, 0, 0, true, deferred);
        Counts counts = new Counts();
        for (Enrollment enrollment : pollingOrder(lectureId, assignmentId)) {
            counts.repositories++;
            int previousErrors = counts.errors;
            int previousUnavailable = counts.unavailable;
            try {
                FeedbackSource.Repository repository = github.repository(enrollment.repository());
                if (repository.id() != enrollment.repositoryId() || !repository.name().equalsIgnoreCase(enrollment.repository())
                        || !repository.privateRepository() || repository.fork()) {
                    throw new IllegalArgumentException("Enrolled repository identity or visibility no longer matches.");
                }
                String branch = config.assignment(enrollment.lectureId(), enrollment.assignmentId()).branch();
                for (Run run : github.runs(enrollment.repository(), file)) {
                    // Listing deliberately has no search filters: old runs can acquire new attempts.
                    if (!"push".equals(run.event()) || !branch.equals(run.branch())) continue;
                    verifyRun(enrollment, run, file);
                    if (run.attempt() < 1 || run.attempt() > 1000) throw new IllegalArgumentException("Too many workflow run attempts.");
                    List<Artifact> artifacts = null;
                    for (int attempt = 1; attempt <= run.attempt(); attempt++) {
                        if (store.received(enrollment.repositoryId(), run.id(), attempt)) {
                            counts.alreadyStored++;
                            continue;
                        }
                        Run current;
                        try {
                            current = attempt == run.attempt() ? run : github.attempt(enrollment.repository(), run.id(), attempt);
                        } catch (Failure failure) {
                            if (failure.retryAt() != null || failure.status() == 401) throw failure;
                            recordUnavailableAttempt(enrollment, run, attempt, failureMessage(failure));
                            counts.errors++;
                            continue;
                        } catch (IOException failure) {
                            recordUnavailableAttempt(enrollment, run, attempt, "Workflow attempt could not be fetched; it will be retried.");
                            counts.errors++;
                            continue;
                        }
                        verifyRun(enrollment, current, file);
                        if (current.id() != run.id() || current.attempt() != attempt || current.workflowId() != run.workflowId()
                                || !current.commit().equalsIgnoreCase(run.commit()) || !current.branch().equals(branch)
                                || !current.event().equals("push")) {
                            throw new IllegalArgumentException("Workflow attempt does not match its run.");
                        }
                        if (!"completed".equals(current.status())) {
                            record(enrollment, current, "pending", "Workflow attempt has not completed.", null);
                            counts.unavailable++;
                            continue;
                        }
                        if (artifacts == null) artifacts = github.artifacts(enrollment.repository(), run.id());
                        collect(enrollment, current, artifacts, counts);
                    }
                }
                store.checked(enrollment, counts.errors > previousErrors || counts.unavailable > previousUnavailable ? "attention" : "ok", null, clock.instant());
            } catch (Failure failure) {
                counts.errors++;
                store.checked(enrollment, failure.retryAt() == null ? "unavailable" : "deferred", failureMessage(failure), clock.instant());
                if (failure.retryAt() != null) {
                    Instant retry = failure.retryAt().isAfter(clock.instant()) ? failure.retryAt() : clock.instant().plusSeconds(60);
                    store.defer(retry);
                    return counts.report(true, retry);
                }
                if (failure.status() == 401) break;
            } catch (IOException | IllegalArgumentException failure) {
                counts.errors++;
                // Never persist HTTP bodies, signed download URLs or parser input fragments.
                store.checked(enrollment, "unavailable", "Repository feedback could not be checked; verify identity, workflow and API access.", clock.instant());
            }
        }
        return counts.report(false, null);
    }

    private List<Enrollment> pollingOrder(String lectureId, String assignmentId) throws IOException {
        List<Enrollment> candidates = enrollments.list(lectureId, assignmentId).stream()
                .filter(enrollment -> enrollment.repositoryId() != null
                        && Set.of("active", "invitation_pending", "retryable").contains(enrollment.status()))
                .toList();
        if (candidates.isEmpty()) return candidates;
        Map<Long, Map<String, Object>> checks = new HashMap<>();
        for (Map<String, Object> check : store.checks(lectureId, assignmentId)) {
            if ("deferred".equals(check.get("status"))) checks.put(((Number) check.get("repositoryId")).longValue(), check);
        }
        Map<Long, Instant> deferred = new HashMap<>();
        int start = 0;
        Instant oldest = Instant.MAX;
        for (int index = 0; index < candidates.size(); index++) {
            Enrollment enrollment = candidates.get(index);
            Map<String, Object> check = checks.get(enrollment.repositoryId());
            if (check == null || !Objects.equals(enrollment.lectureId(), check.get("lectureId"))
                    || !Objects.equals(enrollment.assignmentId(), check.get("assignmentId"))
                    || !Objects.equals(enrollment.studentId(), check.get("studentId"))) continue;
            Instant checkedAt = Instant.parse((String) check.get("lastCheckedAt"));
            deferred.put(enrollment.repositoryId(), checkedAt);
            if (checkedAt.isBefore(oldest)) {
                oldest = checkedAt;
                start = index;
            }
        }
        List<Enrollment> ordered = new ArrayList<>(candidates);
        // Resume the interrupted repository, then its successors before wrapping to completed work.
        Collections.rotate(ordered, -start);
        // Separate filtered invocations can leave several markers; honor the oldest interruption first.
        ordered.sort(Comparator.comparing(enrollment -> deferred.getOrDefault(enrollment.repositoryId(), Instant.MAX)));
        return ordered;
    }

    private void collect(Enrollment enrollment, Run run, List<Artifact> artifacts, Counts counts) throws IOException {
        String name = "classroom-feedback-" + run.id() + "-" + run.attempt();
        List<Artifact> matches = artifacts.stream().filter(artifact -> name.equals(artifact.name())).toList();
        if (matches.isEmpty()) {
            record(enrollment, run, "missing", "Expected feedback artifact is not available; it will be checked again.", null);
            counts.unavailable++;
            return;
        }
        if (matches.size() != 1) {
            record(enrollment, run, "invalid", "More than one artifact has the expected name.", null);
            counts.errors++;
            return;
        }
        Artifact artifact = matches.get(0);
        if (artifact.runId() != run.id() || artifact.repositoryId() != run.repositoryId()
                || !artifact.commit().equalsIgnoreCase(run.commit()) || artifact.sizeBytes() < 1
                || artifact.sizeBytes() > DownloadedFeedback.MAX_ARCHIVE_BYTES) {
            record(enrollment, run, "invalid", "Artifact metadata or archive size does not match the expected run.", artifact);
            counts.errors++;
            return;
        }
        if (artifact.expired()) {
            record(enrollment, run, "expired", "Artifact expired before collection; a new workflow run is needed.", artifact);
            counts.unavailable++;
            return;
        }
        byte[] archive;
        try {
            archive = github.download(enrollment.repository(), artifact.id());
        } catch (Failure failure) {
            if (failure.retryAt() != null || failure.status() == 401) throw failure;
            record(enrollment, run, failure.status() == 410 ? "expired" : "unavailable", failureMessage(failure), artifact);
            counts.errors++;
            return;
        } catch (IOException failure) {
            record(enrollment, run, "unavailable", "Artifact download failed; it will be retried.", artifact);
            counts.errors++;
            return;
        }
        tools.jackson.databind.JsonNode result;
        try {
            result = DownloadedFeedback.read(archive, run, artifact);
        } catch (IOException | IllegalArgumentException failure) {
            record(enrollment, run, "invalid", "Feedback archive, result schema or run correlation is invalid.", artifact);
            counts.errors++;
            return;
        }
        store.record(enrollment, run, "received", null, artifact, archive, result, clock.instant());
        counts.received++;
    }

    private void record(Enrollment enrollment, Run run, String status, String problem, Artifact artifact) throws IOException {
        store.record(enrollment, run, status, problem, artifact, null, null, clock.instant());
    }

    private void recordUnavailableAttempt(Enrollment enrollment, Run run, int attempt, String problem) throws IOException {
        // Keep the known run identity, without attributing the latest attempt's outcome to an older one.
        Run unavailable = new Run(run.id(), attempt, run.repositoryId(), run.repository(), run.branch(), run.commit(),
                run.event(), "unknown", null, run.workflowPath(), run.workflowId(), run.createdAt());
        record(enrollment, unavailable, "unavailable", problem, null);
    }

    private static void verifyRun(Enrollment enrollment, Run run, String file) {
        if (run.repositoryId() != enrollment.repositoryId() || !run.repository().equalsIgnoreCase(enrollment.repository())
                || !run.workflowPath().equals(".github/workflows/" + file) || run.id() <= 0 || run.workflowId() <= 0) {
            throw new IllegalArgumentException("Workflow run identity does not match the enrolled repository and workflow.");
        }
    }

    public static String workflowFile(String workflow) {
        String file = workflow.startsWith(".github/workflows/") ? workflow.substring(".github/workflows/".length()) : workflow;
        if (!file.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,190}\\.ya?ml")) throw new IllegalArgumentException("--workflow must name one .yml or .yaml file in .github/workflows.");
        return file;
    }

    private static String failureMessage(Failure failure) {
        return "GitHub feedback request failed" + (failure.status() > 0 ? " (HTTP " + failure.status() + ")" : "") + "; check access and retry availability.";
    }

    private static final class Counts {
        int repositories;
        int received;
        int alreadyStored;
        int unavailable;
        int errors;

        Report report(boolean deferred, Instant retryAt) {
            return new Report(repositories, received, alreadyStored, unavailable, errors, deferred, retryAt);
        }
    }
}
