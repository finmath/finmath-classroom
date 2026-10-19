package org.finmath.classroom.feedback;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.finmath.classroom.io.JsonFiles;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

/** Validates bounded, untrusted feedback without extracting or executing archive contents. */
public final class DownloadedFeedback {
    public static final int MAX_ARCHIVE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final Set<String> RESULT_FIELDS = Set.of("schema_version", "kind", "authoritative",
            "generated_at", "context", "rubric_sha256", "execution", "status", "score", "max_score", "feedback");
    private static final Set<String> CONTEXT_FIELDS = Set.of("repository_id", "repository", "commit", "ref",
            "run_id", "run_attempt", "event", "workflow_ref", "workflow_sha");
    private static final Set<String> FEEDBACK_FIELDS = Set.of("schema_version", "authoritative", "purpose",
            "cases", "status", "score", "max_score", "errors");
    private static final Set<String> METHOD_FIELDS = Set.of("class_name", "method", "status", "occurrences", "points", "max_points");
    private static final Set<String> SUITE_FIELDS = Set.of("suite", "status", "occurrences", "points", "max_points");
    private static final Set<String> CASE_STATES = Set.of("passed", "failed", "error", "skipped", "missing", "duplicate");
    private static final BigDecimal MAX_POINTS = BigDecimal.valueOf(1_000_000);

    private DownloadedFeedback() { }

    /** Returns validated feedback. Correlation establishes origin, never an authoritative grade. */
    public static JsonNode read(byte[] archive, FeedbackSource.Run run, FeedbackSource.Artifact artifact) throws IOException {
        try {
            require(archive != null && archive.length > 0 && archive.length <= MAX_ARCHIVE_BYTES);
            metadata(run, artifact);
            require(artifact.sizeBytes() == archive.length);
            if (artifact.digest() != null) {
                require(artifact.digest().matches("sha256:[0-9a-fA-F]{64}"));
                // GitHub hashes the downloaded archive stream, before decompression.
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
                require(artifact.digest().substring(7).equalsIgnoreCase(digest));
            }
            byte[] content = contents(archive);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            JsonNode result = JsonFiles.MAPPER.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
            validate(result, run);
            return result;
        } catch (IOException | RuntimeException exception) {
            // Neither ZIP names nor parser diagnostics may disclose untrusted/private input.
            throw new IOException("Feedback artifact is invalid or does not match its GitHub run.");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private static void metadata(FeedbackSource.Run run, FeedbackSource.Artifact artifact) {
        require(run != null && artifact != null && run.id() > 0 && run.attempt() > 0 && run.repositoryId() > 0);
        require("push".equals(run.event()) && "completed".equals(run.status()));
        require(run.repository() != null && run.repository().matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}"));
        require(run.branch() != null && !run.branch().isBlank());
        require(run.commit() != null && run.commit().matches("[0-9a-fA-F]{40}"));
        require(run.workflowPath() != null && run.workflowPath().matches("\\.github/workflows/[^/]+\\.ya?ml"));
        require(artifact.id() > 0 && !artifact.expired() && artifact.runId() == run.id()
                && artifact.repositoryId() == run.repositoryId() && run.commit().equalsIgnoreCase(artifact.commit()));
        require(("classroom-feedback-" + run.id() + "-" + run.attempt()).equals(artifact.name()));
    }

    private static byte[] contents(byte[] archive) throws IOException {
        // ZipInputStream alone accepts a ZIP truncated after its local entry. Check the
        // central directory and end record too, requiring one ordinary, complete ZIP.
        int end = -1;
        for (int offset = archive.length - 22; offset >= Math.max(0, archive.length - 65_557); offset--) {
            if (u32(archive, offset) == 0x06054b50L && offset + 22 + u16(archive, offset + 20) == archive.length) {
                end = offset;
                break;
            }
        }
        require(end >= 0 && u16(archive, end + 4) == 0 && u16(archive, end + 6) == 0
                && u16(archive, end + 8) == 1 && u16(archive, end + 10) == 1);
        long directoryOffset = u32(archive, end + 16);
        long directorySize = u32(archive, end + 12);
        require(directoryOffset >= 30 && directoryOffset + directorySize == end && directorySize >= 46);
        int central = (int) directoryOffset;
        require(u32(archive, central) == 0x02014b50L && u32(archive, 0) == 0x04034b50L);
        require(u16(archive, central + 34) == 0 && u32(archive, central + 42) == 0);
        int nameLength = u16(archive, central + 28);
        require(46L + nameLength + u16(archive, central + 30) + u16(archive, central + 32) == directorySize);
        require(nameLength == 11 && new String(archive, central + 46, nameLength, StandardCharsets.US_ASCII).equals("result.json"));
        long compressed = u32(archive, central + 20);
        long inflated = u32(archive, central + 24);
        require(compressed <= MAX_ARCHIVE_BYTES && inflated > 0 && inflated <= MAX_JSON_BYTES);
        int unixType = (int) (u32(archive, central + 38) >>> 16) & 0170000;
        require((u16(archive, central + 4) >>> 8) != 3 || unixType == 0 || unixType == 0100000);
        long contentEnd = 30L + u16(archive, 26) + u16(archive, 28) + compressed;
        boolean descriptor = (u16(archive, 6) & 8) != 0;
        require(descriptor ? directoryOffset - contentEnd == 12 || directoryOffset - contentEnd == 16
                : directoryOffset == contentEnd);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry = input.getNextEntry();
            require(entry != null && !entry.isDirectory() && entry.getName().equals("result.json"));
            byte[] json = input.readNBytes(MAX_JSON_BYTES + 1);
            require(json.length > 0 && json.length <= MAX_JSON_BYTES);
            require(input.getNextEntry() == null); // Complete the entry and validate its CRC.
            require(entry.getSize() == inflated && json.length == inflated && entry.getCompressedSize() == compressed
                    && entry.getCrc() == u32(archive, central + 16));
            return json;
        }
    }

    private static void validate(JsonNode result, FeedbackSource.Run run) {
        fields(result, RESULT_FIELDS);
        version(result);
        require(string(result, "kind", 100).equals("finmath-classroom-feedback"));
        String generatedAt = string(result, "generated_at", 100);
        require(generatedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})"));
        OffsetDateTime.parse(generatedAt);
        require(string(result, "rubric_sha256", 64).matches("[0-9a-f]{64}"));
        context(result.get("context"), run);
        JsonNode execution = result.get("execution");
        fields(execution, Set.of("exit_code", "status"));
        long code = integer(execution.get("exit_code"), 0, 255);
        String executionStatus = code == 124 ? "timed_out" : code <= 1 ? "completed" : "error";
        require(string(execution, "status", 20).equals(executionStatus));
        JsonNode feedback = result.get("feedback");
        feedback(feedback);
        String expected = feedback.get("status").stringValue();
        if (!executionStatus.equals("completed")) expected = executionStatus;
        else if (expected.equals("passed") && code != 0) expected = "error";
        require(string(result, "status", 20).equals(expected));
        BigDecimal maximum = number(result.get("max_score"), true);
        require(maximum.compareTo(number(feedback.get("max_score"), true)) == 0);
        if (expected.equals("passed") || expected.equals("failed")) {
            require(number(result.get("score"), false).compareTo(number(feedback.get("score"), false)) == 0);
        } else require(result.get("score").isNull());
    }

    private static void context(JsonNode context, FeedbackSource.Run run) {
        fields(context, CONTEXT_FIELDS);
        require(integer(context.get("repository_id"), 1, Long.MAX_VALUE) == run.repositoryId());
        require(integer(context.get("run_id"), 1, Long.MAX_VALUE) == run.id());
        require(integer(context.get("run_attempt"), 1, Long.MAX_VALUE) == run.attempt());
        require(string(context, "repository", 140).equalsIgnoreCase(run.repository()));
        require(string(context, "commit", 40).equalsIgnoreCase(run.commit()));
        require(string(context, "workflow_sha", 40).equalsIgnoreCase(run.commit()));
        require(string(context, "event", 2048).equals("push"));
        String ref = "refs/heads/" + run.branch();
        require(string(context, "ref", 2048).equals(ref));
        String workflow = run.repository() + "/" + run.workflowPath() + "@" + ref;
        String claimed = string(context, "workflow_ref", 2048);
        // Repository names are case-insensitive; workflow paths and branch names are not.
        require(claimed.length() == workflow.length()
                && claimed.regionMatches(true, 0, run.repository(), 0, run.repository().length())
                && claimed.substring(run.repository().length()).equals(workflow.substring(run.repository().length())));
    }

    private static void feedback(JsonNode feedback) {
        fields(feedback, FEEDBACK_FIELDS);
        version(feedback);
        require(string(feedback, "purpose", 100).equals("student feedback"));
        JsonNode errors = feedback.get("errors");
        require(errors.isArray() && errors.size() <= 512);
        for (JsonNode error : errors) require(error.isTextual() && error.stringValue().length() <= 16_384);
        JsonNode cases = feedback.get("cases");
        require(cases.isArray() && !cases.isEmpty() && cases.size() <= 500);
        Set<String> states = new HashSet<>();
        Set<String> names = new HashSet<>();
        BigDecimal score = BigDecimal.ZERO;
        BigDecimal maximum = BigDecimal.ZERO;
        for (JsonNode test : cases) {
            boolean suite = test.has("suite");
            fields(test, suite ? SUITE_FIELDS : METHOD_FIELDS);
            if (suite) require(cases.size() == 1 && string(test, "suite", 100).equals("Maven test suite"));
            else require(names.add(string(test, "class_name", 300) + "\0" + string(test, "method", 300)));
            String state = string(test, "status", 20);
            require(CASE_STATES.contains(state));
            long occurrences = integer(test.get("occurrences"), 0, Integer.MAX_VALUE);
            if (!errors.isEmpty()) require(state.equals("error"));
            if (suite) {
                require(occurrences <= 1);
                require(state.equals("error") || (state.equals("missing") ? occurrences == 0 : occurrences == 1));
            } else if (errors.isEmpty()) {
                require(state.equals("missing") ? occurrences == 0 : state.equals("duplicate") ? occurrences > 1 : occurrences == 1);
            }
            BigDecimal points = number(test.get("points"), false);
            BigDecimal max = number(test.get("max_points"), true);
            require(max.compareTo(MAX_POINTS) <= 0 && points.compareTo(MAX_POINTS) <= 0);
            require(points.compareTo(state.equals("passed") ? max : BigDecimal.ZERO) == 0);
            score = score.add(points);
            maximum = maximum.add(max);
            states.add(state);
        }
        require(number(feedback.get("score"), false).compareTo(score) == 0);
        require(number(feedback.get("max_score"), true).compareTo(maximum) == 0);
        String status = !errors.isEmpty() || states.contains("error") ? "error"
                : states.contains("missing") || states.contains("duplicate") || states.contains("skipped") ? "incomplete"
                : states.contains("failed") ? "failed" : "passed";
        require(string(feedback, "status", 20).equals(status));
    }

    private static void version(JsonNode node) {
        require(integer(node.get("schema_version"), 1, 1) == 1);
        require(node.get("authoritative").isBoolean() && !node.get("authoritative").booleanValue());
    }

    private static void fields(JsonNode object, Set<String> expected) {
        require(object != null && object.isObject() && object.size() == expected.size()
                && object.propertyNames().containsAll(expected));
    }

    private static String string(JsonNode object, String field, int limit) {
        JsonNode value = object.get(field);
        require(value != null && value.isTextual() && !value.stringValue().isEmpty() && value.stringValue().length() <= limit);
        return value.stringValue();
    }

    private static long integer(JsonNode value, long minimum, long maximum) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        long number = value.longValue();
        require(number >= minimum && number <= maximum);
        return number;
    }

    private static BigDecimal number(JsonNode value, boolean positive) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()));
        BigDecimal number = value.decimalValue();
        require(number.precision() <= 1000 && number.scale() >= -1000 && number.scale() <= 1000);
        require(positive ? number.signum() > 0 : number.signum() >= 0);
        return number;
    }

    private static int u16(byte[] data, int offset) {
        require(offset >= 0 && offset <= data.length - 2);
        return (data[offset] & 255) | (data[offset + 1] & 255) << 8;
    }

    private static long u32(byte[] data, int offset) {
        return (long) u16(data, offset) | (long) u16(data, offset + 2) << 16;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid feedback artifact.");
    }
}
