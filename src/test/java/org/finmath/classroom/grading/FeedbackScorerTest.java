package org.finmath.classroom.grading;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackScorerTest {
    private static final String BASIC = "{\"class_name\":\"example.Check\",\"method\":\"basic\",\"points\":5}";
    private static final String ADVANCED = "{\"class_name\":\"example.Check\",\"method\":\"advanced\",\"points\":2}";
    @TempDir
    Path root;
    private Path rubric;
    private Path reports;

    @BeforeEach
    void setUp() throws IOException {
        rubric = root.resolve("rubric.json");
        reports = Files.createDirectory(root.resolve("reports"));
        writeRubric("{\"tests\":[" + BASIC + "," + ADVANCED + "]}");
    }

    @Test
    void passingSuiteHasExplicitFeedbackProvenance() throws IOException {
        report(suite(testCase("basic"), testCase("advanced")));
        JsonNode result = score();
        assertResult(result, "passed", 7);
        assertEquals(7, result.get("max_score").asInt());
        assertEquals(1, result.get("schema_version").asInt());
        assertFalse(result.get("authoritative").asBoolean());
        assertEquals("student feedback", result.get("purpose").asString());
        assertTrue(result.get("errors").isEmpty());
        assertEquals(0, FeedbackScorer.exitCode(result));
    }

    @Test
    void failedCaseDoesNotEarnPoints() throws IOException {
        report(suite(testCase("basic"), testCase("advanced", "<failure/>")));
        assertResult(score(), "failed", 5);
        assertEquals(1, FeedbackScorer.exitCode(score()));
    }

    @Test
    void missingCaseIsIncomplete() throws IOException {
        report(suite(testCase("basic")));
        JsonNode result = score();
        assertResult(result, "incomplete", 5);
        assertEquals("missing", result.get("cases").get(1).get("status").asString());
        assertEquals(0, result.get("cases").get(1).get("occurrences").asInt());
    }

    @Test
    void missingDirectoryAndEmptyDirectoryAreIncomplete() throws IOException {
        assertResult(score(), "incomplete", 0);
        assertResult(FeedbackScorer.score(rubric, root.resolve("absent")), "incomplete", 0);
    }

    @Test
    void duplicateCaseAcrossReportsCannotEarnPoints() throws IOException {
        report(suite(testCase("basic"), testCase("advanced")));
        report("TEST-duplicate.xml", suite(testCase("basic")));
        JsonNode result = score();
        assertResult(result, "incomplete", 2);
        assertEquals("duplicate", result.get("cases").get(0).get("status").asString());
        assertEquals(2, result.get("cases").get(0).get("occurrences").asInt());
    }

    @Test
    void duplicateCaseWithinReportCannotEarnPoints() throws IOException {
        report(suite(testCase("basic"), testCase("basic"), testCase("advanced")));
        assertResult(score(), "incomplete", 2);
    }

    @Test
    void skippedCaseCannotEarnPoints() throws IOException {
        report(suite(testCase("basic", "<skipped/>"), testCase("advanced")));
        assertResult(score(), "incomplete", 2);
    }

    @Test
    void testErrorHasErrorStatusButPreservesOtherCasePoints() throws IOException {
        report(suite(testCase("basic", "<error/>"), testCase("advanced")));
        assertResult(score(), "error", 2);
        assertEquals(2, FeedbackScorer.exitCode(score()));
        assertTrue(score().get("errors").isEmpty());
    }

    @Test
    void errorMarkerTakesPrecedenceOverFailureAndSkipped() throws IOException {
        report(suite(testCase("basic", "<skipped/><failure/><error/>"), testCase("advanced")));
        assertEquals("error", score().get("cases").get(0).get("status").asString());
    }

    @Test
    void incompleteTakesPrecedenceOverFailure() throws IOException {
        report(suite(testCase("basic", "<failure/>")));
        assertResult(score(), "incomplete", 0);
    }

    @Test
    void malformedXmlInvalidatesAllScores() throws IOException {
        report(suite(testCase("basic"), testCase("advanced")));
        report("TEST-invalid.xml", "<testsuite>");
        JsonNode result = score();
        assertResult(result, "error", 0);
        assertFalse(result.get("errors").isEmpty());
        for (JsonNode test : result.get("cases")) {
            assertEquals("error", test.get("status").asString());
        }
    }

    @Test
    void dtdAndEntityDeclarationsAreRejectedWithoutResolvingResources() throws IOException {
        for (String prefix : List.of(
                "<!DOCTYPE testsuite>",
                "<!DOCTYPE testsuite [<!ENTITY attack \"value\">]>",
                "<!DOCTYPE testsuite SYSTEM \"file:///nonexistent-classroom-secret\">")) {
            report(prefix + suite(testCase("basic"), testCase("advanced")));
            assertResult(score(), "error", 0);
        }
    }

    @Test
    void utf16AndBomlessUtf16CannotBypassDeclarationChecks() throws IOException {
        String xml = "<!DOCTYPE testsuite [<!ENTITY attack \"value\">]><testsuite/>";
        for (var encoding : List.of(StandardCharsets.UTF_16, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Files.write(reports.resolve("TEST-encoding.xml"), xml.getBytes(encoding));
            assertResult(score(), "error", 0);
        }
    }

    @Test
    void invalidUtf8FailsClosed() throws IOException {
        Files.write(reports.resolve("TEST-encoding.xml"), new byte[] {(byte) 0xc3, (byte) 0x28});
        assertResult(score(), "error", 0);
    }

    @Test
    void utf8BomIsAcceptedForRubricAndReports() throws IOException {
        writeRubric("\ufeff{\"tests\":[" + BASIC + "," + ADVANCED + "]}");
        report("\ufeff" + suite(testCase("basic"), testCase("advanced")));
        assertResult(score(), "passed", 7);
    }

    @Test
    void reportSizeLimitIsEnforced() throws IOException {
        report(" ".repeat(FeedbackScorer.MAX_REPORT_BYTES + 1));
        assertResult(score(), "error", 0);
        assertTrue(score().get("errors").get(0).asString().contains("limit"));
    }

    @Test
    void combinedReportSizeLimitIsEnforced() throws IOException {
        String report = "<testsuite><!--" + "x".repeat(FeedbackScorer.MAX_REPORT_BYTES - 128) + "--></testsuite>";
        for (int i = 0; i < 9; i++) {
            report("TEST-" + i + ".xml", report);
        }
        assertResult(score(), "error", 0);
        assertEquals("reports exceed combined size limit", score().get("errors").get(0).asString());
    }

    @Test
    void reportCountLimitIsEnforcedBeforeParsing() throws IOException {
        for (int i = 0; i <= FeedbackScorer.MAX_REPORT_FILES; i++) {
            Files.createFile(reports.resolve("TEST-" + i + ".xml"));
        }
        assertResult(score(), "error", 0);
        assertEquals("too many report files", score().get("errors").get(0).asString());
    }

    @Test
    void symlinkReportIsRejected() throws IOException {
        Path target = Files.writeString(root.resolve("outside.xml"), "<testsuite/>");
        Files.createSymbolicLink(reports.resolve("TEST-link.xml"), target);
        assertResult(score(), "error", 0);
    }

    @Test
    void matchingDirectoryIsNotAcceptedAsReport() throws IOException {
        Files.createDirectory(reports.resolve("TEST-directory.xml"));
        assertResult(score(), "error", 0);
    }

    @Test
    void reportDirectoryCannotBeSymlinkOrRegularFile() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("linked-reports"), reports);
        assertResult(FeedbackScorer.score(rubric, link), "error", 0);
        assertResult(FeedbackScorer.score(rubric, rubric), "error", 0);
    }

    @Test
    void rubricRequiresExactStructure() throws IOException {
        for (String json : List.of("{}", "[]", "null", "{\"tests\":[]}", "{\"tests\":null}",
                "{\"tests\":\"bad\"}", "{\"tests\":[{}]}",
                "{\"tests\":[" + BASIC + "],\"extra\":1}",
                "{\"tests\":[" + BASIC + "," + BASIC + "]}")) {
            writeRubric(json);
            assertThrows(IllegalArgumentException.class, this::score, json);
        }
    }

    @Test
    void rubricRejectsNonNumericNonPositiveAndExcessivePoints() throws IOException {
        for (String points : List.of("0", "-1", "true", "false", "null", "\"5\"", "1000001", "1e309")) {
            writeRubric("{\"tests\":[" + BASIC.replace("\"points\":5", "\"points\":" + points) + "]}");
            assertThrows(Exception.class, this::score, points);
        }
    }

    @Test
    void rubricRejectsDuplicateKeysAndTrailingJson() throws IOException {
        for (String json : List.of("{\"tests\":[],\"tests\":[]}",
                "{\"tests\":[" + BASIC + "]} {}",
                "{\"tests\":[{\"class_name\":\"example.Check\",\"method\":\"basic\",\"points\":5,\"points\":7}]}")) {
            writeRubric(json);
            assertThrows(Exception.class, this::score);
        }
    }

    @Test
    void rubricRejectsUnsafeIdentifiers() throws IOException {
        for (String method : List.of("basic|markdown", "basic()", "basic\n", "x".repeat(301), "1basic")) {
            writeRubric("{\"tests\":[" + BASIC.replace("basic", method.replace("\n", "\\n")) + "]}");
            assertThrows(IllegalArgumentException.class, this::score);
        }
        writeRubric("{\"tests\":[" + BASIC.replace("example.Check", "example..Check") + "]}");
        assertThrows(IllegalArgumentException.class, this::score);
    }

    @Test
    void rubricRejectsSymlinksAndExcessiveSize() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("linked-rubric.json"), rubric);
        assertThrows(Exception.class, () -> FeedbackScorer.score(link, reports));
        writeRubric(" ".repeat(FeedbackScorer.MAX_RUBRIC_BYTES + 1));
        assertThrows(Exception.class, this::score);
    }

    @Test
    void rubricLimitsExpectedCaseCount() throws IOException {
        StringBuilder tests = new StringBuilder("{\"tests\":[");
        for (int i = 0; i <= FeedbackScorer.MAX_EXPECTED_TESTS; i++) {
            if (i > 0) {
                tests.append(',');
            }
            tests.append(BASIC.replace("basic", "case" + i));
        }
        writeRubric(tests.append("]}").toString());
        assertThrows(IllegalArgumentException.class, this::score);
    }

    @Test
    void namespacedAndNestedSuitesAndUnrelatedCasesAreSupported() throws IOException {
        report("<testsuites xmlns=\"urn:junit\">" + suite(testCase("basic"), testCase("advanced"), testCase("unrelated")) + "</testsuites>");
        assertResult(score(), "passed", 7);
    }

    @Test
    void incorrectRootIsRejectedAndNonMatchingFilesAreIgnored() throws IOException {
        report("ignored.xml", "<not-valid-xml");
        report("<other>" + testCase("basic") + "</other>");
        assertResult(score(), "error", 0);
        report(suite(testCase("basic"), testCase("advanced")));
        assertResult(score(), "passed", 7);
    }

    @Test
    void decimalPointsAreSummedWithoutBinaryFloatingPointArtifacts() throws IOException {
        writeRubric("{\"tests\":[" + BASIC.replace("\"points\":5", "\"points\":0.1") + ","
                + ADVANCED.replace("\"points\":2", "\"points\":0.2") + "]}");
        report(suite(testCase("basic"), testCase("advanced")));
        assertEquals(0.3, score().get("score").asDouble());
        assertTrue(FeedbackScorer.summary(score()).contains("0.3/0.3 points"));
    }

    @Test
    void summaryContainsFeedbackWarningAndPerCaseScores() throws IOException {
        report(suite(testCase("basic")));
        String summary = FeedbackScorer.summary(score());
        assertTrue(summary.contains("Status: **incomplete** — 5/7 points."));
        assertTrue(summary.contains("Feedback only. Final marks require instructor-controlled grading."));
        assertTrue(summary.contains("| `basic` | passed | 5/5 |"));
        assertTrue(summary.contains("| `advanced` | missing | 0/2 |"));
        assertTrue(summary.endsWith("\n"));
    }

    @Test
    void summaryDoesNotEchoUntrustedReportErrors() throws IOException {
        report("TEST-<untrusted>.xml", "<broken>");
        String summary = FeedbackScorer.summary(score());
        assertTrue(summary.contains("Report input could not be validated"));
        assertFalse(summary.contains("<untrusted>"));
    }

    @Test
    void exampleRubricHasFiveTestsAndTwentyTwoPoints() throws IOException {
        JsonNode result = FeedbackScorer.score(Path.of("examples/grading/rubric.json"), reports);
        assertEquals(5, result.get("cases").size());
        assertEquals(22, result.get("max_score").asInt());
    }

    private void writeRubric(String json) throws IOException {
        Files.writeString(rubric, json, StandardCharsets.UTF_8);
    }

    private void report(String xml) throws IOException {
        report("TEST-example.xml", xml);
    }

    private void report(String name, String xml) throws IOException {
        Files.writeString(reports.resolve(name), xml, StandardCharsets.UTF_8);
    }

    private static String testCase(String method) {
        return testCase(method, "");
    }

    private static String testCase(String method, String children) {
        return "<testcase classname=\"example.Check\" name=\"" + method + "\">" + children + "</testcase>";
    }

    private static String suite(String... cases) {
        return "<testsuite>" + String.join("", cases) + "</testsuite>";
    }

    private JsonNode score() throws IOException {
        return FeedbackScorer.score(rubric, reports);
    }

    private static void assertResult(JsonNode result, String status, int points) {
        assertEquals(status, result.get("status").asString());
        assertEquals(points, result.get("score").asInt());
    }
}
