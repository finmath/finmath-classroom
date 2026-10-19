package org.finmath.classroom.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.finmath.classroom.grading.FeedbackArtifact;
import org.finmath.classroom.io.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DownloadedFeedbackTest {
    private static final String SHA = "a".repeat(40);
    private static final FeedbackSource.Run RUN = new FeedbackSource.Run(456, 2, 123, "org/assignment", "main", SHA,
            "push", "completed", "success", ".github/workflows/feedback.yml", 789, Instant.parse("2026-01-01T12:00:00Z"));
    @TempDir Path directory;

    @Test void acceptsCompleteFeedbackAndVerifiesArchiveDigest() throws Exception {
        ObjectNode result = result();
        byte[] archive = archive(result);
        JsonNode received = DownloadedFeedback.read(archive, RUN, artifact(archive, digest(archive)));
        assertEquals(result, received);
        assertFalse(received.get("authoritative").booleanValue());
        assertEquals(7, received.get("score").intValue());
        assertThrows(IOException.class, () -> DownloadedFeedback.read(archive, RUN, artifact(archive, "sha256:" + "0".repeat(64))));
        assertThrows(IOException.class, () -> DownloadedFeedback.read(archive, RUN, artifact(archive, "md5:unexpected")));
    }

    @Test void acceptsActualProducerResultsIncludingPartialScoresAndExecutionFailures() throws Exception {
        Path rubric = Files.writeString(directory.resolve("rubric.json"), """
                {"tests":[{"class_name":"example.Check","method":"basic","points":5},
                          {"class_name":"example.Check","method":"advanced","points":2}]}
                """);
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Path context = Files.writeString(directory.resolve("context.json"), JsonFiles.MAPPER.writeValueAsString(result().get("context")));
        List<String> cases = List.of(
                "<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'/>",
                "<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'><failure/></testcase>",
                "<testcase classname='example.Check' name='basic'/>",
                "<testcase classname='example.Check' name='basic'><skipped/></testcase>",
                "<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='basic'/>",
                "<testcase classname='example.Check' name='basic'><error/></testcase>");
        for (String report : cases) {
            Files.writeString(reports.resolve("TEST-check.xml"), "<testsuite>" + report + "</testsuite>");
            for (int code : List.of(0, 1, 2, 124)) {
                JsonNode produced = FeedbackArtifact.create(rubric, reports, context, code);
                JsonNode downloaded = read(produced);
                assertEquals(produced.get("status"), downloaded.get("status"));
                assertEquals(produced.get("score").toString(), downloaded.get("score").toString());
            }
        }
        Files.writeString(reports.resolve("TEST-check.xml"), "broken XML");
        assertEquals("error", read(FeedbackArtifact.create(rubric, reports, context, 1)).get("status").stringValue());
    }

    @Test void acceptsSuiteDuplicateStatusWithTheProducersSingleOccurrence() throws Exception {
        Path rubric = Files.writeString(directory.resolve("rubric.json"), "{\"suite\":{\"points\":7}}");
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Path context = Files.writeString(directory.resolve("context.json"), JsonFiles.MAPPER.writeValueAsString(result().get("context")));
        Files.writeString(reports.resolve("TEST-check.xml"), """
                <testsuite><testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='basic'/></testsuite>
                """);
        JsonNode downloaded = read(FeedbackArtifact.create(rubric, reports, context, 1));
        assertEquals("incomplete", downloaded.get("status").stringValue());
        assertEquals(1, downloaded.get("feedback").get("cases").get(0).get("occurrences").intValue());
    }

    @Test void acceptsDecimalPointsWithoutBinaryFloatingPointSumRounding() throws Exception {
        ObjectNode result = result();
        result.put("score", new java.math.BigDecimal("0.3"));
        result.put("max_score", new java.math.BigDecimal("0.3"));
        feedback(result).put("score", new java.math.BigDecimal("0.3"));
        feedback(result).put("max_score", new java.math.BigDecimal("0.3"));
        test(result, 0).put("points", new java.math.BigDecimal("0.1")).put("max_points", new java.math.BigDecimal("0.1"));
        test(result, 1).put("points", new java.math.BigDecimal("0.2")).put("max_points", new java.math.BigDecimal("0.2"));
        assertEquals(new java.math.BigDecimal("0.3"), read(result).get("score").decimalValue());
    }

    @Test void acceptsCaseInsensitiveHexAndRepositoryNamesWithoutChangingBranchOrWorkflowCase() throws Exception {
        ObjectNode result = result();
        context(result).put("commit", SHA.toUpperCase(java.util.Locale.ROOT));
        context(result).put("workflow_sha", SHA.toUpperCase(java.util.Locale.ROOT));
        context(result).put("repository", "ORG/ASSIGNMENT");
        context(result).put("workflow_ref", "ORG/ASSIGNMENT/.github/workflows/feedback.yml@refs/heads/main");
        assertEquals("passed", read(result).get("status").stringValue());
    }

    @Test void rejectsWrongRunContextAndArtifactMetadata() throws Exception {
        rejected(value -> context(value).put("repository_id", 124));
        rejected(value -> context(value).put("repository", "other/assignment"));
        rejected(value -> context(value).put("run_id", 457));
        rejected(value -> context(value).put("run_attempt", 1));
        rejected(value -> context(value).put("commit", "b".repeat(40)));
        rejected(value -> context(value).put("workflow_sha", "b".repeat(40)));
        rejected(value -> context(value).put("event", "pull_request"));
        rejected(value -> context(value).put("ref", "refs/heads/other"));
        rejected(value -> context(value).put("workflow_ref", "org/assignment/.github/workflows/untrusted.yml@refs/heads/main"));
        rejected(value -> context(value).put("workflow_ref", "org/assignment/.github/workflows/feedback.yml@refs/heads/MAIN"));
        byte[] archive = archive(result());
        for (FeedbackSource.Artifact artifact : List.of(
                new FeedbackSource.Artifact(12, "classroom-feedback-456-1", 456, 123, SHA, false, archive.length, null),
                new FeedbackSource.Artifact(12, "classroom-feedback-456-2", 457, 123, SHA, false, archive.length, null),
                new FeedbackSource.Artifact(12, "classroom-feedback-456-2", 456, 124, SHA, false, archive.length, null),
                new FeedbackSource.Artifact(12, "classroom-feedback-456-2", 456, 123, "b".repeat(40), false, archive.length, null),
                new FeedbackSource.Artifact(12, "classroom-feedback-456-2", 456, 123, SHA, true, archive.length, null),
                new FeedbackSource.Artifact(12, "classroom-feedback-456-2", 456, 123, SHA, false, archive.length + 1, null))) {
            assertThrows(IOException.class, () -> DownloadedFeedback.read(archive, RUN, artifact));
        }
    }

    @Test void rejectsUnknownFieldsTypesNumbersAndInconsistentTotals() throws Exception {
        rejected(value -> value.put("unexpected", true));
        rejected(value -> context(value).put("unexpected", true));
        rejected(value -> feedback(value).put("unexpected", true));
        rejected(value -> test(value, 0).put("unexpected", true));
        rejected(value -> ((ObjectNode) value.get("execution")).put("unexpected", true));
        rejected(value -> value.put("schema_version", 2));
        rejected(value -> value.put("authoritative", true));
        rejected(value -> feedback(value).put("authoritative", true));
        rejected(value -> value.put("kind", "final-result"));
        rejected(value -> value.put("generated_at", "today"));
        rejected(value -> value.put("generated_at", "2026-01-01T12:01Z"));
        rejected(value -> value.put("rubric_sha256", "0"));
        rejected(value -> context(value).put("run_id", "456"));
        rejected(value -> value.put("score", "7"));
        rejected(value -> value.putNull("score"));
        rejected(value -> value.put("score", -1));
        rejected(value -> value.put("score", 8));
        rejected(value -> value.put("max_score", 6));
        rejected(value -> feedback(value).put("score", 6));
        rejected(value -> feedback(value).put("max_score", 8));
        rejected(value -> test(value, 0).put("max_points", 1_000_001));
        rejected(value -> test(value, 0).put("points", 1));
        rejected(value -> test(value, 0).put("occurrences", -1));
        rejected(value -> test(value, 0).put("occurrences", 2));
        rejected(value -> test(value, 0).put("method", "advanced"));
        rejected(value -> value.put("score", new java.math.BigDecimal("1e309")));
        rejected(value -> value.put("score", new java.math.BigDecimal("1e-100000")));
    }

    @Test void rejectsInconsistentStatusAndNullScoreRules() throws Exception {
        rejected(value -> value.put("status", "failed"));
        rejected(value -> feedback(value).put("status", "failed"));
        rejected(value -> test(value, 0).put("status", "failed"));
        rejected(value -> ((ObjectNode) value.get("execution")).put("exit_code", 124));
        rejected(value -> ((ObjectNode) value.get("execution")).put("status", "error"));
        rejected(value -> ((ObjectNode) value.get("execution")).put("exit_code", 1));
        rejected(value -> ((ArrayNode) feedback(value).get("errors")).add("A report could not be read."));
        ObjectNode timeout = result();
        ((ObjectNode) timeout.get("execution")).put("exit_code", 124).put("status", "timed_out");
        timeout.put("status", "timed_out").putNull("score");
        assertEquals("timed_out", read(timeout).get("status").stringValue());
        timeout.put("score", 7);
        assertThrows(IOException.class, () -> read(timeout));
    }

    @Test void rejectsOversizedOrMissingCasesAndLongStrings() throws Exception {
        rejected(value -> ((ArrayNode) feedback(value).get("cases")).removeAll());
        rejected(value -> {
            ArrayNode cases = (ArrayNode) feedback(value).get("cases");
            while (cases.size() <= 500) cases.add(test(value, 0).deepCopy());
        });
        rejected(value -> test(value, 0).put("method", "x".repeat(301)));
        rejected(value -> ((ArrayNode) feedback(value).get("errors")).add("x".repeat(16_385)));
        rejected(value -> {
            ArrayNode errors = (ArrayNode) feedback(value).get("errors");
            for (int index = 0; index < 513; index++) errors.add("");
        });
    }

    @Test void rejectsExtraDuplicateDirectoryAndTraversalEntries() throws Exception {
        byte[] json = JsonFiles.MAPPER.writeValueAsBytes(result());
        for (String name : List.of("../result.json", "directory/result.json", "/result.json", "result.json/", "directory/")) {
            assertInvalid(zip(json, false, name));
        }
        assertInvalid(zip(json, false, "result.json", "otherx.json"));
        byte[] duplicate = zip(json, false, "result.json", "otherx.json");
        replaceAscii(duplicate, "otherx.json", "result.json");
        assertInvalid(duplicate);
    }

    @Test void rejectsZipBombAndArchiveBeyondCompressedLimit() throws Exception {
        byte[] payload = new byte[DownloadedFeedback.MAX_JSON_BYTES + 1];
        Arrays.fill(payload, (byte) ' ');
        byte[] bomb = zip(payload, false, "result.json");
        assertInvalid(bomb);
        // A dishonest central directory must not bypass the streaming inflate bound.
        int central = signature(bomb, 0x02014b50);
        putInt(bomb, central + 24, 10);
        assertInvalid(bomb);
        assertInvalid(new byte[DownloadedFeedback.MAX_ARCHIVE_BYTES + 1]);
    }

    @Test void rejectsTruncationBadCrcAndCentralDirectoryMismatch() throws Exception {
        byte[] archive = archive(result());
        assertInvalid(Arrays.copyOf(archive, archive.length - 10));
        assertInvalid(Arrays.copyOf(archive, signature(archive, 0x02014b50)));
        byte[] inconsistent = archive.clone();
        int central = signature(inconsistent, 0x02014b50);
        inconsistent[central + 16] ^= 1;
        assertInvalid(inconsistent);
        byte[] corrupt = zip(JsonFiles.MAPPER.writeValueAsBytes(result()), true, "result.json");
        corrupt[45] ^= 1;
        assertInvalid(corrupt);
    }

    @Test void rejectsInvalidUtf8DuplicateJsonKeysTrailingTokensAndSanitizesErrors() throws Exception {
        String json = JsonFiles.MAPPER.writeValueAsString(result());
        assertInvalid(zip(new byte[] {(byte) 0xc3, 0x28}, false, "result.json"));
        assertInvalid(zip((json + " {}").getBytes(StandardCharsets.UTF_8), false, "result.json"));
        assertInvalid(zip(json.replaceFirst("\\{", "{\"schema_version\":1,").getBytes(StandardCharsets.UTF_8), false, "result.json"));
        assertInvalid(zip("private-untrusted-secret".getBytes(StandardCharsets.UTF_8), false, "result.json"));
    }

    private static ObjectNode result() {
        return (ObjectNode) JsonFiles.MAPPER.readTree("""
                {"schema_version":1,"kind":"finmath-classroom-feedback","authoritative":false,
                 "generated_at":"2026-01-01T12:01:00Z",
                 "context":{"repository_id":123,"repository":"org/assignment",
                  "commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ref":"refs/heads/main",
                  "run_id":456,"run_attempt":2,"event":"push",
                  "workflow_ref":"org/assignment/.github/workflows/feedback.yml@refs/heads/main",
                  "workflow_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "rubric_sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "execution":{"exit_code":0,"status":"completed"},"status":"passed","score":7,"max_score":7,
                 "feedback":{"schema_version":1,"authoritative":false,"purpose":"student feedback",
                  "status":"passed","score":7,"max_score":7,"errors":[],"cases":[
                   {"class_name":"example.Check","method":"basic","status":"passed","occurrences":1,"points":5,"max_points":5},
                   {"class_name":"example.Check","method":"advanced","status":"passed","occurrences":1,"points":2,"max_points":2}]}}
                """);
    }

    private static ObjectNode context(ObjectNode value) { return (ObjectNode) value.get("context"); }
    private static ObjectNode feedback(ObjectNode value) { return (ObjectNode) value.get("feedback"); }
    private static ObjectNode test(ObjectNode value, int index) { return (ObjectNode) value.get("feedback").get("cases").get(index); }

    private static void rejected(Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode result = result();
        mutate.accept(result);
        assertThrows(IOException.class, () -> read(result));
    }

    private static JsonNode read(JsonNode result) throws Exception {
        byte[] archive = archive(result);
        return DownloadedFeedback.read(archive, RUN, artifact(archive, null));
    }

    private static byte[] archive(JsonNode result) throws IOException {
        return zip(JsonFiles.MAPPER.writeValueAsBytes(result), false, "result.json");
    }

    private static FeedbackSource.Artifact artifact(byte[] bytes, String digest) {
        return new FeedbackSource.Artifact(12, "classroom-feedback-456-2", 456, 123, SHA, false, bytes.length, digest);
    }

    private static void assertInvalid(byte[] archive) {
        IOException failure = assertThrows(IOException.class, () -> DownloadedFeedback.read(archive, RUN, artifact(archive, null)));
        assertEquals("Feedback artifact is invalid or does not match its GitHub run.", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] zip(byte[] bytes, boolean stored, String... names) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(bytes.length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static int signature(byte[] bytes, int signature) {
        for (int offset = 0; offset <= bytes.length - 4; offset++) {
            if ((bytes[offset] & 255) == (signature & 255) && (bytes[offset + 1] & 255) == ((signature >>> 8) & 255)
                    && (bytes[offset + 2] & 255) == ((signature >>> 16) & 255) && (bytes[offset + 3] & 255) == ((signature >>> 24) & 255)) return offset;
        }
        throw new AssertionError("Missing ZIP signature");
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        for (int index = 0; index < 4; index++) bytes[offset + index] = (byte) (value >>> (index * 8));
    }

    private static void replaceAscii(byte[] bytes, String old, String replacement) {
        byte[] from = old.getBytes(StandardCharsets.US_ASCII);
        byte[] to = replacement.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset <= bytes.length - from.length; offset++) {
            if (Arrays.equals(bytes, offset, offset + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, bytes, offset, to.length);
            }
        }
    }
}
