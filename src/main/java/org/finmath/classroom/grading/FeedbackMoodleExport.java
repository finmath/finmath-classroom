package org.finmath.classroom.grading;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.RegistrationService.Identity;

/** Maps one assignment's latest collected feedback to saved Moodle IDs or supplied usernames. */
public final class FeedbackMoodleExport {
    private FeedbackMoodleExport() { }

    public record MoodleIdExport(MoodleGradeExport.Summary summary, String issuer) { }

    /** The caller must select latest rows for exactly one lecture and assignment before exporting. */
    public static MoodleGradeExport.Summary export(List<Map<String, Object>> latestRows, Path mapping,
            Path output, String gradeItem, BigDecimal expectedSourceMax, BigDecimal moodleMax,
            BigDecimal offset, int decimals) throws IOException {
        Map<String, String> usernames = usernames(mapping);
        Selection selected = select(latestRows, expectedSourceMax);
        List<MoodleGradeExport.Grade> grades = new ArrayList<>();
        for (Score score : selected.scores()) {
            String username = usernames.get(score.student());
            if (username == null) throw invalid("Missing Moodle username mapping for student ID " + score.student());
            grades.add(new MoodleGradeExport.Grade(username, score.points(), score.maximum()));
        }
        var summary = MoodleGradeExport.export(grades, output, gradeItem, moodleMax, offset, decimals);
        return new MoodleGradeExport.Summary(summary.exported(), summary.skipped() + selected.skipped());
    }

    /** Uses verified identities from the registration database, never student-workflow identity fields. */
    public static MoodleIdExport exportByMoodleId(List<Map<String, Object>> latestRows, Map<String, Identity> identities,
            Path output, String gradeItem, BigDecimal expectedSourceMax, BigDecimal moodleMax,
            BigDecimal offset, int decimals) throws IOException {
        Selection selected = select(latestRows, expectedSourceMax);
        List<MoodleGradeExport.Grade> grades = new ArrayList<>();
        String issuer = null;
        for (Score score : selected.scores()) {
            Identity identity = identities.get(score.student());
            String source = moodleIssuer(identity, score.student());
            if (issuer != null && !issuer.equals(source)) {
                throw invalid("Moodle ID export cannot combine identities from different Moodle issuers");
            }
            issuer = source;
            MoodleGradeExport.validateUserId(identity.subject(), "Saved identity for student ID " + score.student());
            grades.add(new MoodleGradeExport.Grade(identity.subject(), score.points(), score.maximum()));
        }
        var summary = MoodleGradeExport.exportUserIds(grades, output, gradeItem, moodleMax, offset, decimals);
        return new MoodleIdExport(new MoodleGradeExport.Summary(summary.exported(), summary.skipped() + selected.skipped()), issuer);
    }

    private static String moodleIssuer(Identity identity, String student) {
        String source = identity == null ? null : identity.source();
        if (source == null || !source.startsWith("moodle:")) {
            throw invalid("No saved Moodle identity for student ID " + student
                    + "; register through Moodle or supply --moodle-users for username mapping");
        }
        String issuer = source.substring("moodle:".length());
        try {
            URI uri = URI.create(issuer);
            if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null) return issuer;
        } catch (IllegalArgumentException exception) {
            // Do not include arbitrary persisted identity text in diagnostics.
        }
        throw invalid("Saved Moodle issuer is invalid for student ID " + student);
    }

    private static Selection select(List<Map<String, Object>> latestRows, BigDecimal expectedSourceMax) {
        if (expectedSourceMax != null) {
            MoodleGradeExport.validateNumber(expectedSourceMax, "Expected source maximum");
            if (expectedSourceMax.signum() <= 0) throw invalid("Expected source maximum must be positive");
        }
        List<Score> scores = new ArrayList<>();
        Set<String> students = new HashSet<>();
        String lecture = null;
        String assignment = null;
        int skipped = 0;
        for (Map<String, Object> row : latestRows) {
            String rowLecture = identity(row, "lectureId");
            String rowAssignment = identity(row, "assignmentId");
            if (lecture != null && (!lecture.equals(rowLecture) || !assignment.equals(rowAssignment))) {
                throw invalid("Moodle export requires exactly one lecture and assignment");
            }
            lecture = rowLecture;
            assignment = rowAssignment;
            String student = identity(row, "studentId");
            if (!students.add(student)) throw invalid("Moodle export requires one latest row per student");
            if (!"received".equals(row.get("collectionStatus"))
                    || !("passed".equals(row.get("feedbackStatus")) || "failed".equals(row.get("feedbackStatus")))
                    || row.get("score") == null) {
                skipped++;
                continue;
            }
            BigDecimal score = number(row.get("score"), "Feedback score");
            BigDecimal sourceMax = number(row.get("maxScore"), "Feedback maximum");
            if (expectedSourceMax != null && sourceMax.compareTo(expectedSourceMax) != 0) {
                throw invalid("Feedback maximum differs from --source-max for student ID " + student);
            }
            scores.add(new Score(student, score, sourceMax));
        }
        return new Selection(scores, skipped);
    }

    private record Score(String student, BigDecimal points, BigDecimal maximum) { }
    private record Selection(List<Score> scores, int skipped) { }

    private static Map<String, String> usernames(Path path) throws IOException {
        var json = JsonFiles.read(path, 4 * 1024 * 1024);
        if (!json.isObject()) throw invalid("Moodle username mapping must be a JSON object keyed by student ID");
        Map<String, String> mapping = new HashMap<>();
        Set<String> usernames = new HashSet<>();
        for (var entry : json.properties()) {
            if (entry.getKey().isBlank() || !entry.getValue().isString()) {
                throw invalid("Moodle username mapping requires nonblank student IDs and string usernames");
            }
            String username = entry.getValue().stringValue();
            MoodleGradeExport.validateText(username, "Mapped Moodle username");
            if (!usernames.add(username.toLowerCase(Locale.ROOT))) {
                throw invalid("Moodle username mapping contains a duplicate username");
            }
            mapping.put(entry.getKey(), username);
        }
        return mapping;
    }

    private static String identity(Map<String, Object> row, String key) {
        if (!(row.get(key) instanceof String value) || value.isBlank()) {
            throw invalid("Feedback row is missing " + key);
        }
        return value;
    }

    private static BigDecimal number(Object value, String label) {
        if (!(value instanceof Number)) throw invalid(label + " must be numeric");
        try {
            BigDecimal number = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
            MoodleGradeExport.validateNumber(number, label);
            return number;
        } catch (NumberFormatException exception) {
            throw invalid(label + " must be a finite decimal number");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
