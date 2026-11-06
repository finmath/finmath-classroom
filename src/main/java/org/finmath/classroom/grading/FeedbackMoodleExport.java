package org.finmath.classroom.grading;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.finmath.classroom.io.JsonFiles;

/** Maps one assignment's latest collected feedback to Moodle usernames supplied by the instructor. */
public final class FeedbackMoodleExport {
    private FeedbackMoodleExport() { }

    /** The caller must select latest rows for exactly one lecture and assignment before exporting. */
    public static MoodleGradeExport.Summary export(List<Map<String, Object>> latestRows, Path mapping,
            Path output, String gradeItem, BigDecimal expectedSourceMax, BigDecimal moodleMax,
            BigDecimal offset, int decimals) throws IOException {
        if (expectedSourceMax != null) {
            MoodleGradeExport.validateNumber(expectedSourceMax, "Expected source maximum");
            if (expectedSourceMax.signum() <= 0) throw invalid("Expected source maximum must be positive");
        }
        Map<String, String> usernames = usernames(mapping);
        List<MoodleGradeExport.Grade> grades = new ArrayList<>();
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
            String username = usernames.get(student);
            if (username == null) {
                throw invalid("Missing Moodle username mapping for student ID " + student);
            }
            BigDecimal score = number(row.get("score"), "Feedback score");
            BigDecimal sourceMax = number(row.get("maxScore"), "Feedback maximum");
            if (expectedSourceMax != null && sourceMax.compareTo(expectedSourceMax) != 0) {
                throw invalid("Feedback maximum differs from --source-max for student ID " + student);
            }
            grades.add(new MoodleGradeExport.Grade(username, score, sourceMax));
        }
        var summary = MoodleGradeExport.export(grades, output, gradeItem, moodleMax, offset, decimals);
        return new MoodleGradeExport.Summary(summary.exported(), summary.skipped() + skipped);
    }

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
