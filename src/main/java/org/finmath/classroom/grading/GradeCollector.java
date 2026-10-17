package org.finmath.classroom.grading;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;

import tools.jackson.databind.JsonNode;

/**
 * Correlates trusted final-runner results with an instructor's frozen manifest.
 * This class does not run or sandbox student code, authenticate a result's
 * producer, or accept student-editable feedback artifacts as authoritative.
 */
public final class GradeCollector {
    static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    static final int MAX_RESULT_BYTES = 64 * 1024;
    static final int MAX_SUBMISSIONS = 10_000;
    static final int MAX_DIRECTORY_ENTRIES = 20_000;
    private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(1_000_000);
    private static final List<String> FIELDS = List.of("student_id", "github_username", "assignment",
            "repository", "commit", "received_at", "status", "score", "max_score", "graded_at");
    private static final Set<String> MANIFEST_FIELDS = Set.of("version", "assignment", "deadline",
            "generated_at", "submissions");
    private static final Set<String> SUBMISSION_FIELDS = Set.of("student_id", "github_username",
            "repository", "commit", "received_at", "status");
    private static final Set<String> RESULT_FIELDS = Set.of("assignment", "repository", "commit",
            "status", "score", "max_score");
    private static final Set<String> SUBMISSION_STATUSES = Set.of("submitted", "no_submission", "not_enrolled");
    private static final Set<String> RESULT_STATUSES = Set.of("passed", "failed", "compile_error", "timeout",
            "infrastructure_error", "incomplete", "error");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40}");

    /** Validate all inputs before atomically replacing a private CSV output. */
    public void collect(Path manifestPath, Path resultsDirectory, Path output) throws IOException {
        if (output.getFileName() == null || !output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw invalid("Output filename must end in .csv");
        }
        if (Files.isSymbolicLink(output)) {
            throw invalid("CSV output must not be a symlink");
        }
        rejectOverwrite(output, manifestPath);
        JsonNode manifest = loadManifest(manifestPath);
        if (Files.isSymbolicLink(resultsDirectory)
                || !Files.isDirectory(resultsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("Results must be a real directory, not a symlink");
        }

        String assignment = manifest.get("assignment").stringValue();
        Set<ResultKey> expected = new HashSet<>();
        for (JsonNode submission : manifest.get("submissions")) {
            if ("submitted".equals(submission.get("status").stringValue())) {
                expected.add(key(assignment, submission));
            }
        }
        List<Path> paths = resultPaths(resultsDirectory);
        Map<ResultKey, JsonNode> results = new HashMap<>();
        for (Path path : paths) {
            rejectOverwrite(output, path);
            JsonNode result = loadResult(path);
            ResultKey key = key(result.get("assignment").stringValue(), result);
            if (!expected.contains(key)) {
                throw invalid("Unknown or mismatched assignment/repository/commit in " + path.getFileName());
            }
            if (results.putIfAbsent(key, result) != null) {
                throw invalid("Duplicate result for " + key.repository());
            }
        }

        StringBuilder csv = new StringBuilder();
        appendRow(csv, new ArrayList<>(FIELDS));
        for (JsonNode submission : manifest.get("submissions")) {
            String status = submission.get("status").stringValue();
            JsonNode result = null;
            if ("submitted".equals(status)) {
                result = results.get(key(assignment, submission));
                status = result == null ? "missing_result" : result.get("status").stringValue();
            }
            List<String> row = new ArrayList<>();
            for (String field : FIELDS) {
                if ("assignment".equals(field)) {
                    row.add(spreadsheetSafe(assignment));
                } else if ("status".equals(field)) {
                    row.add(status);
                } else if ("score".equals(field) || "max_score".equals(field)) {
                    JsonNode value = result == null ? null : result.get(field);
                    row.add(value == null || value.isNull() ? "" : value.decimalValue().stripTrailingZeros().toPlainString());
                } else {
                    JsonNode value = "graded_at".equals(field)
                            ? (result == null ? null : result.get(field)) : submission.get(field);
                    row.add(value == null || value.isNull() ? "" : spreadsheetSafe(value.stringValue()));
                }
            }
            appendRow(csv, row);
        }
        SafeFiles.writeAtomic(output, csv.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private static JsonNode loadManifest(Path path) throws IOException {
        JsonNode manifest = schema(JsonFiles.read(path, MAX_MANIFEST_BYTES), MANIFEST_FIELDS, Set.of(), "manifest");
        JsonNode version = manifest.get("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
            throw invalid("Only manifest version 1 is supported");
        }
        assignment(manifest.get("assignment"));
        Instant deadline = timestamp(manifest.get("deadline"), "deadline");
        timestamp(manifest.get("generated_at"), "generated_at");
        JsonNode submissions = manifest.get("submissions");
        if (!submissions.isArray() || submissions.size() > MAX_SUBMISSIONS) {
            throw invalid("submissions must be an array of at most " + MAX_SUBMISSIONS + " records");
        }
        Set<String> ids = new HashSet<>();
        Set<String> users = new HashSet<>();
        Set<String> repositories = new HashSet<>();
        for (JsonNode submission : submissions) {
            schema(submission, SUBMISSION_FIELDS, Set.of(), "submission");
            String id = text(submission.get("student_id"), "student_id", 200);
            String user = username(submission.get("github_username"), "github_username");
            if (!ids.add(id) || !users.add(user.toLowerCase(Locale.ROOT))) {
                throw invalid("Duplicate student or GitHub account in manifest");
            }
            String status = text(submission.get("status"), "submission status", 30);
            if (!SUBMISSION_STATUSES.contains(status)) {
                throw invalid("Invalid submission status");
            }
            JsonNode repository = submission.get("repository");
            if (!repository.isNull()
                    && !repositories.add(repository(repository).toLowerCase(Locale.ROOT))) {
                throw invalid("Duplicate repository in manifest");
            }
            if ("submitted".equals(status)) {
                if (repository.isNull()) {
                    throw invalid("Submitted record has no repository");
                }
                commit(submission.get("commit"));
                if (timestamp(submission.get("received_at"), "received_at").isAfter(deadline)) {
                    throw invalid("Submission was received after the deadline");
                }
            } else {
                if (!submission.get("commit").isNull() || !submission.get("received_at").isNull()) {
                    throw invalid("Unsubmitted record has commit or received_at");
                }
                if ("not_enrolled".equals(status) && !repository.isNull()) {
                    throw invalid("Not-enrolled record has a repository");
                }
            }
        }
        return manifest;
    }

    private static JsonNode loadResult(Path path) throws IOException {
        JsonNode result = schema(JsonFiles.read(path, MAX_RESULT_BYTES), RESULT_FIELDS, Set.of("graded_at"), "result");
        assignment(result.get("assignment"));
        repository(result.get("repository"));
        commit(result.get("commit"));
        String status = text(result.get("status"), "result status", 30);
        if (!RESULT_STATUSES.contains(status)) {
            throw invalid("Invalid result status");
        }
        BigDecimal score = number(result.get("score"), "score", false);
        BigDecimal maximum = number(result.get("max_score"), "max_score", true);
        if (score != null && maximum != null && score.compareTo(maximum) > 0) {
            throw invalid("score exceeds max_score");
        }
        if ("passed".equals(status) || "failed".equals(status)) {
            if (score == null || maximum == null) {
                throw invalid("Completed grade requires score and max_score");
            }
            boolean fullMarks = score.compareTo(maximum) == 0;
            if ("passed".equals(status) != fullMarks) {
                throw invalid("Result status is inconsistent with its score");
            }
        } else if ("compile_error".equals(status) || "timeout".equals(status)) {
            if (score != null && score.signum() != 0) {
                throw invalid("Compile errors/timeouts may only explicitly score zero");
            }
        } else if (score != null) {
            throw invalid("Infrastructure or incomplete results cannot assign a score");
        }
        if (result.has("graded_at")) {
            timestamp(result.get("graded_at"), "graded_at");
        }
        return result;
    }

    private static List<Path> resultPaths(Path directory) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path path : entries) {
                if (++count > MAX_DIRECTORY_ENTRIES) {
                    throw invalid("Results directory has too many entries");
                }
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    paths.add(path);
                    if (paths.size() > MAX_SUBMISSIONS) {
                        throw invalid("Too many result files");
                    }
                }
            }
        }
        paths.sort(Path::compareTo);
        return paths;
    }

    private static JsonNode schema(JsonNode node, Set<String> required, Set<String> optional, String label) {
        if (node == null || !node.isObject()) {
            throw invalid(label + " must be an object");
        }
        Set<String> actual = new HashSet<>(node.propertyNames());
        if (!actual.containsAll(required)) {
            throw invalid(label + " has missing fields");
        }
        actual.removeAll(required);
        actual.removeAll(optional);
        if (!actual.isEmpty()) {
            throw invalid(label + " has unknown fields");
        }
        return node;
    }

    private static String text(JsonNode node, String label, int maximum) {
        if (node == null || !node.isString()) {
            throw invalid(label + " must be a nonempty string");
        }
        String value = node.stringValue();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maximum || value.indexOf('\0') >= 0) {
            throw invalid(label + " is empty, too long, or contains NUL");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid(label + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid(label + " contains invalid Unicode");
            }
        }
        return value;
    }

    private static String username(JsonNode node, String label) {
        String value = text(node, label, 39);
        if (!USERNAME.matcher(value).matches()) {
            throw invalid(label + " is not a GitHub username");
        }
        return value;
    }

    private static String assignment(JsonNode node) {
        String value = text(node, "assignment", 100);
        if (!NAME.matcher(value).matches()) {
            throw invalid("assignment must be a simple assignment identifier");
        }
        return value;
    }

    private static String repository(JsonNode node) {
        String value = text(node, "repository", 140);
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || !USERNAME.matcher(parts[0]).matches() || !NAME.matcher(parts[1]).matches()) {
            throw invalid("repository must be owner/repository without a URL or path");
        }
        return value;
    }

    private static String commit(JsonNode node) {
        String value = text(node, "commit", 40);
        if (!COMMIT.matcher(value).matches()) {
            throw invalid("commit must be a full 40-character hexadecimal SHA");
        }
        return value;
    }

    private static Instant timestamp(JsonNode node, String label) {
        String value = text(node, label, 64);
        try {
            if (!value.contains("T")) {
                throw invalid(label + " must include a time and explicit timezone");
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw invalid(label + " must be an ISO timestamp with an explicit timezone");
        }
    }

    private static BigDecimal number(JsonNode node, String label, boolean positive) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid(label + " must be a finite number or null");
        }
        BigDecimal value;
        try {
            value = node.decimalValue();
        } catch (RuntimeException exception) {
            throw invalid(label + " must be a finite number");
        }
        if (value == null || value.signum() < 0 || (positive && value.signum() == 0)
                || value.compareTo(MAX_SCORE) > 0) {
            throw invalid(label + " is outside the allowed score range");
        }
        return value;
    }

    private static ResultKey key(String assignment, JsonNode node) {
        return new ResultKey(assignment, node.get("repository").stringValue(), node.get("commit").stringValue());
    }

    private static void rejectOverwrite(Path output, Path input) throws IOException {
        if (output.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
                || (Files.exists(output) && Files.exists(input) && Files.isSameFile(output, input))) {
            throw invalid("Output must not overwrite an input file");
        }
    }

    static String spreadsheetSafe(String value) {
        int index = 0;
        boolean leadingControl = false;
        while (index < value.length()) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                break;
            }
            leadingControl |= character == '\t' || character == '\r' || character == '\n';
            index++;
        }
        if (leadingControl || (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0)) {
            return "'" + value;
        }
        return value;
    }

    private static void appendRow(StringBuilder output, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            String value = cells.get(index);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                output.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                output.append(value);
            }
        }
        output.append("\r\n");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record ResultKey(String assignment, String repository, String commit) { }
}
