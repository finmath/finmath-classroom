package org.finmath.classroom.feedback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.finmath.classroom.registration.EnrollmentStore.Enrollment;

/** Read-only views of collected feedback for the current enrollments. */
public final class FeedbackListing {
    private static final Comparator<Map<String, Object>> ATTEMPT_ORDER = Comparator
            .comparing((Map<String, Object> row) -> Instant.parse((String) row.get("createdAt")))
            .thenComparingLong(row -> ((Number) row.get("runId")).longValue())
            .thenComparingInt(row -> ((Number) row.get("runAttempt")).intValue());

    private FeedbackListing() { }

    /**
     * Returns one row per current enrollment, preserving enrollment order. The latest attempt
     * belongs to the newest created run; collecting or rerunning an older run does not promote it.
     */
    public static List<Map<String, Object>> latest(List<Enrollment> enrollments,
            List<Map<String, Object>> results, List<Map<String, Object>> checks) {
        Map<Association, Map<String, Object>> latest = new HashMap<>();
        for (Map<String, Object> result : results) {
            Association association = association(result);
            if (association != null) {
                latest.merge(association, result,
                        (first, second) -> ATTEMPT_ORDER.compare(first, second) >= 0 ? first : second);
            }
        }
        Map<Association, Map<String, Object>> repositoryChecks = new HashMap<>();
        for (Map<String, Object> check : checks) {
            Association association = association(check);
            if (association != null) repositoryChecks.put(association, check);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Enrollment enrollment : enrollments) {
            Association association = enrollment.repositoryId() == null ? null : new Association(
                    enrollment.repositoryId(), enrollment.lectureId(), enrollment.assignmentId(), enrollment.studentId());
            Map<String, Object> result = latest.get(association);
            Map<String, Object> row = result == null
                    ? uncollected(enrollment, repositoryChecks.get(association)) : new LinkedHashMap<>(result);
            row.put("lectureId", enrollment.lectureId());
            row.put("assignmentId", enrollment.assignmentId());
            row.put("studentId", enrollment.studentId());
            row.put("name", enrollment.name());
            row.put("githubId", enrollment.githubId());
            row.put("githubLogin", enrollment.githubLogin());
            row.put("identitySource", enrollment.identitySource());
            row.put("repositoryId", enrollment.repositoryId());
            row.put("repository", enrollment.repository());
            row.put("authoritative", false);
            row.put("provenance", "student-workflow");
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static Map<String, Object> uncollected(Enrollment enrollment, Map<String, Object> check) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String field : List.of("runId", "runAttempt", "branch", "commit", "event", "runStatus", "conclusion",
                "workflowPath", "workflowId", "createdAt", "receivedAt", "lastCheckedAt", "artifactId",
                "artifactName", "artifactDigest", "archiveSha256", "feedbackStatus", "score", "maxScore")) {
            row.put(field, null);
        }
        boolean unavailable = check != null && ("unavailable".equals(check.get("status")) || "deferred".equals(check.get("status")));
        row.put("collectionStatus", unavailable ? "unavailable" : "not_collected");
        String problem = enrollment.repositoryId() == null ? "Repository has not been provisioned."
                : "No workflow attempts have been collected for this enrollment.";
        if (check != null) {
            row.put("lastCheckedAt", check.get("lastCheckedAt"));
            if (unavailable) {
                problem = check.get("problem") instanceof String message && !message.isBlank()
                        ? message : "Repository feedback could not be checked.";
            }
        }
        row.put("problem", problem);
        return row;
    }

    private static Association association(Map<String, Object> row) {
        return row.get("repositoryId") instanceof Number repository
                ? new Association(repository.longValue(), (String) row.get("lectureId"),
                        (String) row.get("assignmentId"), (String) row.get("studentId"))
                : null;
    }

    private record Association(long repositoryId, String lectureId, String assignmentId, String studentId) { }
}
