package org.finmath.classroom.grading;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Run metadata and execution status around student feedback; metadata does not establish provenance. */
public final class FeedbackArtifact {

	private static final Set<String> CONTEXT_FIELDS = Set.of("repository_id", "repository", "commit", "ref", "run_id", "run_attempt", "event", "workflow_ref", "workflow_sha");

	private FeedbackArtifact() { }

	/** Reads bounded inputs and creates an artifact without changing files or executing tests. */
	public static JsonNode create(Path rubric, Path reports, Path context, int testExitCode) throws IOException {
		if(testExitCode < 0 || testExitCode > 255) throw new IllegalArgumentException("Test exit code must be between 0 and 255.");
		JsonNode metadata = JsonFiles.read(context, 16 * 1024);
		if(!metadata.isObject() || metadata.size() != CONTEXT_FIELDS.size() || !metadata.propertyNames().containsAll(CONTEXT_FIELDS)) {
			throw new IllegalArgumentException("Feedback context must contain exactly repository_id, repository, commit, ref, run_id, run_attempt, event, workflow_ref and workflow_sha.");
		}
		for(String field : Set.of("repository_id", "run_id", "run_attempt")) {
			JsonNode value = metadata.get(field);
			if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
				throw new IllegalArgumentException("Feedback context " + field + " must be a positive integer.");
			}
		}
		for(String field : Set.of("repository", "commit", "ref", "event", "workflow_ref", "workflow_sha")) {
			JsonNode value = metadata.get(field);
			if(!value.isTextual() || value.stringValue().isBlank() || value.stringValue().length() > 2048) {
				throw new IllegalArgumentException("Feedback context " + field + " must be a nonempty string of at most 2048 characters.");
			}
		}
		String repository = metadata.get("repository").stringValue();
		if(!repository.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}") || repository.endsWith("/.") || repository.endsWith("/..")) {
			throw new IllegalArgumentException("Feedback context repository must be owner/name.");
		}
		for(String field : Set.of("commit", "workflow_sha")) {
			if(!metadata.get(field).stringValue().matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("Feedback context " + field + " must be a 40-character commit SHA.");
		}
		String rubricHash;
		try {
			rubricHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SafeFiles.read(rubric, FeedbackScorer.MAX_RUBRIC_BYTES)));
		} catch(NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
		JsonNode feedback = FeedbackScorer.score(rubric, reports);
		String execution = testExitCode == 124 ? "timed_out" : testExitCode == 0 || testExitCode == 1 ? "completed" : "error";
		String status = feedback.path("status").asString();
		if(!execution.equals("completed")) status = execution;
		else if(status.equals("passed") && testExitCode != 0) status = "error";
		ObjectNode result = JsonFiles.MAPPER.createObjectNode();
		result.put("schema_version", 1);
		result.put("kind", "finmath-classroom-feedback");
		result.put("authoritative", false);
		result.put("generated_at", Instant.now().toString());
		result.set("context", metadata);
		result.put("rubric_sha256", rubricHash);
		result.putObject("execution").put("exit_code", testExitCode).put("status", execution);
		result.put("status", status);
		if(status.equals("passed") || status.equals("failed")) result.set("score", feedback.get("score"));
		else result.putNull("score");
		result.set("max_score", feedback.get("max_score"));
		result.set("feedback", feedback);
		return result;
	}

	/** Formats effective status and case details without treating unavailable results as earned zeroes. */
	public static String summary(JsonNode result) {
		boolean scored = result.path("score").isNumber();
		StringBuilder text = new StringBuilder("## Assignment feedback\n\nStatus: **");
		text.append(result.path("status").asString()).append("** — ");
		if(scored) text.append(number(result.path("score"))).append('/').append(number(result.path("max_score"))).append(" points.\n\n");
		else text.append("score unavailable (maximum ").append(number(result.path("max_score"))).append(" points).\n\n");
		text.append("Test command exit code: ").append(result.path("execution").path("exit_code").asInt()).append(".\n\n")
				.append("Feedback only. Final marks require instructor-controlled grading.\n\n")
				.append("| Test | Result | ").append(scored ? "Points" : "Maximum points").append(" |\n| --- | --- | ---: |\n");
		for(JsonNode test : result.path("feedback").path("cases")) {
			text.append("| `").append(test.path("method").asString()).append("` | ").append(test.path("status").asString()).append(" | ");
			if(scored) text.append(number(test.path("points"))).append('/');
			text.append(number(test.path("max_points"))).append(" |\n");
		}
		if(!result.path("feedback").path("errors").isEmpty()) text.append("\nReport input could not be validated; see feedback JSON for details.\n");
		return text.toString();
	}

	/** Passed = 0, failed/incomplete = 1, report/execution errors and timeouts = 2. */
	public static int exitCode(JsonNode result) {
		return FeedbackScorer.exitCode(result);
	}

	private static String number(JsonNode value) {
		return value.decimalValue().stripTrailingZeros().toPlainString();
	}
}
