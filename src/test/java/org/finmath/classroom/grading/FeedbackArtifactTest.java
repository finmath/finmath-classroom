package org.finmath.classroom.grading;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.finmath.classroom.io.JsonFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class FeedbackArtifactTest {

	@TempDir Path directory;
	private Path rubric;
	private Path reports;
	private Path context;

	@BeforeEach void setUp() throws IOException {
		rubric = Files.writeString(directory.resolve("rubric.json"), """
				{"tests":[{"class_name":"example.Check","method":"basic","points":5},
				{"class_name":"example.Check","method":"advanced","points":2}]}
				""");
		reports = Files.createDirectory(directory.resolve("reports"));
		context = Files.writeString(directory.resolve("context.json"), """
				{"repository_id":123,"repository":"org/assignment","commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				"ref":"refs/heads/main","run_id":456,"run_attempt":2,"event":"push",
				"workflow_ref":"org/assignment/.github/workflows/feedback.yml@refs/heads/main",
				"workflow_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
				""");
	}

	@Test void testPassingArtifactPreservesContextFeedbackAndInputs() throws Exception {
		Path report = report("<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'/>");
		byte[] rubricBytes = Files.readAllBytes(rubric);
		byte[] contextBytes = Files.readAllBytes(context);
		byte[] reportBytes = Files.readAllBytes(report);
		JsonNode feedback = FeedbackScorer.score(rubric, reports);
		Instant before = Instant.now();
		JsonNode result = create(0);
		assertEquals(Set.of("schema_version", "kind", "authoritative", "generated_at", "context", "rubric_sha256", "execution", "status", "score", "max_score", "feedback"), Set.copyOf(result.propertyNames()));
		assertEquals(1, result.path("schema_version").asInt());
		assertEquals("finmath-classroom-feedback", result.path("kind").asString());
		assertFalse(result.path("authoritative").asBoolean());
		assertEquals(JsonFiles.MAPPER.readTree(contextBytes), result.path("context"));
		assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rubricBytes)), result.path("rubric_sha256").asString());
		assertEquals(feedback, result.path("feedback"));
		Instant generated = Instant.parse(result.path("generated_at").asString());
		assertFalse(generated.isBefore(before));
		assertFalse(generated.isAfter(Instant.now()));
		assertState(result, "passed", 7);
		assertEquals("completed", result.path("execution").path("status").asString());
		assertEquals(0, result.path("execution").path("exit_code").asInt());
		assertEquals(0, FeedbackArtifact.exitCode(result));
		assertTrue(FeedbackArtifact.summary(result).contains("Status: **passed** — 7/7 points."));
		assertTrue(FeedbackArtifact.summary(result).contains("| `basic` | passed | 5/5 |"));
		assertArrayEquals(rubricBytes, Files.readAllBytes(rubric));
		assertArrayEquals(contextBytes, Files.readAllBytes(context));
		assertArrayEquals(reportBytes, Files.readAllBytes(report));
		try(java.util.stream.Stream<Path> files = Files.walk(directory)) {
			assertEquals(5, files.count());
		}
	}

	@Test void testFailedReportsKeepPartialPointsDespiteMavenFailure() throws IOException {
		report("<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'><failure/></testcase>");
		JsonNode result = create(1);
		assertState(result, "failed", 5);
		assertEquals("completed", result.path("execution").path("status").asString());
		assertEquals(1, result.path("execution").path("exit_code").asInt());
		assertEquals(1, FeedbackArtifact.exitCode(result));
		assertTrue(FeedbackArtifact.summary(result).contains("Status: **failed** — 5/7 points."));
		assertEquals(FeedbackScorer.score(rubric, reports), result.path("feedback"));
	}

	@Test void testMissingReportsAndIncompleteCasesHaveUnavailableScore() throws IOException {
		JsonNode absent = FeedbackArtifact.create(rubric, directory.resolve("absent"), context, 1);
		assertState(absent, "incomplete", null);
		assertFalse(Files.exists(directory.resolve("absent")));
		assertState(create(0), "incomplete", null);
		report("<testcase classname='example.Check' name='basic'/>");
		JsonNode partial = create(1);
		assertState(partial, "incomplete", null);
		assertEquals(5, partial.path("feedback").path("score").asInt());
		assertEquals(1, FeedbackArtifact.exitCode(partial));
		String summary = FeedbackArtifact.summary(partial);
		assertTrue(summary.contains("Status: **incomplete** — score unavailable"));
		assertTrue(summary.contains("| `advanced` | missing | 2 |"));
		assertFalse(summary.contains("0/"));
		assertFalse(summary.contains("5/7"));
	}

	@Test void testMalformedReportsHaveUnavailableScoreWithoutEchoingErrorsInSummary() throws IOException {
		Files.writeString(reports.resolve("TEST-<untrusted>.xml"), "<broken>");
		JsonNode result = create(1);
		assertState(result, "error", null);
		assertFalse(result.path("feedback").path("errors").isEmpty());
		assertEquals(2, FeedbackArtifact.exitCode(result));
		String summary = FeedbackArtifact.summary(result);
		assertTrue(summary.contains("Report input could not be validated"));
		assertFalse(summary.contains("<untrusted>"));
		assertFalse(summary.contains("0/7"));
	}

	@Test void testReportedTestErrorsHaveUnavailableScore() throws IOException {
		report("<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'><error/></testcase>");
		JsonNode result = create(1);
		assertState(result, "error", null);
		assertEquals(5, result.path("feedback").path("score").asInt());
	}

	@Test void testTimeoutOverridesEvenPassingReports() throws IOException {
		report("<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'/>");
		JsonNode result = create(124);
		assertState(result, "timed_out", null);
		assertEquals("timed_out", result.path("execution").path("status").asString());
		assertEquals(124, result.path("execution").path("exit_code").asInt());
		assertEquals("passed", result.path("feedback").path("status").asString());
		assertEquals(2, FeedbackArtifact.exitCode(result));
		assertTrue(FeedbackArtifact.summary(result).contains("Status: **timed_out** — score unavailable"));
	}

	@Test void testSignalTerminationIsAnExecutionErrorWithoutAssumingTimeout() throws IOException {
		report("<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'><failure/></testcase>");
		for(int exit : List.of(137, 143)) {
			JsonNode result = create(exit);
			assertState(result, "error", null);
			assertEquals("error", result.path("execution").path("status").asString());
			assertEquals(exit, result.path("execution").path("exit_code").asInt());
			assertEquals("failed", result.path("feedback").path("status").asString());
			assertEquals(5, result.path("feedback").path("score").asInt());
			assertEquals(2, FeedbackArtifact.exitCode(result));
			String summary = FeedbackArtifact.summary(result);
			assertTrue(summary.contains("Status: **error** — score unavailable"));
			assertTrue(summary.contains("Test command exit code: " + exit));
			assertFalse(summary.contains("timed_out"));
		}
	}

	@Test void testExecutionFailureOverridesReportsAndPreservesExitCode() throws IOException {
		for(int exit : List.of(2, 126, 127, 255)) {
			JsonNode result = create(exit);
			assertState(result, "error", null);
			assertEquals("error", result.path("execution").path("status").asString());
			assertEquals(exit, result.path("execution").path("exit_code").asInt());
			assertEquals("incomplete", result.path("feedback").path("status").asString());
		}
	}

	@Test void testPostTestFailureCannotPresentPassingScore() throws IOException {
		report("<testcase classname='example.Check' name='basic'/><testcase classname='example.Check' name='advanced'/>");
		JsonNode result = create(1);
		assertState(result, "error", null);
		assertEquals("completed", result.path("execution").path("status").asString());
		assertEquals("passed", result.path("feedback").path("status").asString());
		assertTrue(FeedbackArtifact.summary(result).contains("Status: **error** — score unavailable"));
		assertEquals(2, FeedbackArtifact.exitCode(result));
	}

	@Test void testSuiteArtifactsPreserveAllOrNothingPointsAndSummary() throws IOException {
		Files.writeString(rubric, "{\"suite\":{\"points\":7}}");
		report("<testcase classname='example.Check' name='basic'/><testcase classname='other.Check' name='other'/>");
		JsonNode passed = create(0);
		assertState(passed, "passed", 7);
		assertTrue(FeedbackArtifact.summary(passed).contains("| `Maven test suite` | passed | 7/7 |"));
		report("<testcase classname='example.Check' name='basic'/><testcase classname='other.Check' name='other'><failure/></testcase>");
		JsonNode failed = create(1);
		assertState(failed, "failed", 0);
		assertTrue(FeedbackArtifact.summary(failed).contains("Status: **failed** — 0/7 points."));
	}

	@Test void testSuitePassingReportsNeedSuccessfulExecutionAndNonemptyReports() throws IOException {
		Files.writeString(rubric, "{\"suite\":{\"points\":7}}");
		assertState(create(0), "incomplete", null);
		report("");
		assertState(create(0), "incomplete", null);
		report("<testcase classname='example.Check' name='basic'/>");
		for(int exit : List.of(1, 2, 137)) {
			JsonNode result = create(exit);
			assertState(result, "error", null);
			assertEquals("passed", result.path("feedback").path("status").asString());
			assertTrue(FeedbackArtifact.summary(result).contains("| `Maven test suite` | passed | 7 |"));
		}
		assertState(create(124), "timed_out", null);
		report("<testcase classname='example.Check' name='basic'><skipped/></testcase>");
		assertState(create(0), "incomplete", null);
	}

	@Test void testInvalidExitCodeAndRubricDoNotProduceArtifact() throws IOException {
		assertThrows(IllegalArgumentException.class, () -> create(-1));
		assertThrows(IllegalArgumentException.class, () -> create(256));
		Files.writeString(rubric, "{\"tests\":[]}");
		assertThrows(IllegalArgumentException.class, () -> create(0));
	}

	@Test void testContextRequiresExactFields() throws IOException {
		String original = Files.readString(context);
		ObjectNode invalid = (ObjectNode)JsonFiles.MAPPER.readTree(original);
		invalid.remove("commit");
		Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
		assertThrows(IllegalArgumentException.class, () -> create(0));
		invalid = (ObjectNode)JsonFiles.MAPPER.readTree(original);
		invalid.put("extra", true);
		Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
		assertThrows(IllegalArgumentException.class, () -> create(0));
		for(String value : List.of("[]", "null", "{}")) {
			Files.writeString(context, value);
			assertThrows(IllegalArgumentException.class, () -> create(0));
		}
	}

	@Test void testContextRejectsInvalidNumericIds() throws IOException {
		String original = Files.readString(context);
		for(String field : List.of("repository_id", "run_id", "run_attempt")) {
			for(String value : List.of("0", "-1", "1.5", "\"123\"", "true", "null", "9223372036854775808")) {
				ObjectNode invalid = (ObjectNode)JsonFiles.MAPPER.readTree(original);
				invalid.set(field, JsonFiles.MAPPER.readTree(value));
				Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
				assertThrows(IllegalArgumentException.class, () -> create(0), field + "=" + value);
			}
		}
	}

	@Test void testContextRejectsInvalidRepositoryShaAndStrings() throws IOException {
		String original = Files.readString(context);
		for(String field : List.of("repository", "commit", "ref", "event", "workflow_ref", "workflow_sha")) {
			for(String value : List.of("\"\"", "\" \"", "null", "123")) {
				ObjectNode invalid = (ObjectNode)JsonFiles.MAPPER.readTree(original);
				invalid.set(field, JsonFiles.MAPPER.readTree(value));
				Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
				assertThrows(IllegalArgumentException.class, () -> create(0), field + "=" + value);
			}
		}
		for(String repository : List.of("org", "https://github.com/org/repo", "org/repo/extra", "org/..", "org/repo name")) {
			ObjectNode invalid = (ObjectNode)JsonFiles.MAPPER.readTree(original);
			invalid.put("repository", repository);
			Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
			assertThrows(IllegalArgumentException.class, () -> create(0));
		}
		for(String field : List.of("commit", "workflow_sha")) {
			ObjectNode invalid = (ObjectNode)JsonFiles.MAPPER.readTree(original);
			invalid.put(field, "g".repeat(40));
			Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
			assertThrows(IllegalArgumentException.class, () -> create(0));
			invalid.put(field, "a".repeat(39));
			Files.writeString(context, JsonFiles.MAPPER.writeValueAsString(invalid));
			assertThrows(IllegalArgumentException.class, () -> create(0));
		}
	}

	@Test void testContextIsBoundedStrictJsonAndCannotBeSymlink() throws IOException {
		String original = Files.readString(context);
		for(String value : List.of(original + " {}", original.replace("\"run_id\":456", "\"run_id\":456,\"run_id\":789"), " ".repeat(16 * 1024 + 1))) {
			Files.writeString(context, value);
			assertThrows(IllegalArgumentException.class, () -> create(0));
		}
		Files.write(context, new byte[] {(byte)0xc3, (byte)0x28});
		assertThrows(IOException.class, () -> create(0));
		Files.writeString(context, original, StandardCharsets.UTF_8);
		Path link = Files.createSymbolicLink(directory.resolve("linked-context.json"), context);
		assertThrows(IllegalArgumentException.class, () -> FeedbackArtifact.create(rubric, reports, link, 0));
	}

	private JsonNode create(int exit) throws IOException {
		return FeedbackArtifact.create(rubric, reports, context, exit);
	}

	private Path report(String cases) throws IOException {
		return Files.writeString(reports.resolve("TEST-example.xml"), "<testsuite>" + cases + "</testsuite>");
	}

	private static void assertState(JsonNode result, String status, Integer score) {
		assertEquals(status, result.path("status").asString());
		if(score == null) assertTrue(result.path("score").isNull());
		else assertEquals(score.intValue(), result.path("score").asInt());
		assertEquals(7, result.path("max_score").asInt());
	}
}
