package org.finmath.classroom.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.GitHubException.Category;
import org.finmath.classroom.registration.GitHubException.Operation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

class GitHubClientTest {
	private static final String REPOSITORY = "{\"id\":123,\"full_name\":\"teaching/course-42\",\"private\":true,\"fork\":false,\"is_template\":false,\"default_branch\":\"main\"}";
	private static final String USER = "{\"id\":42,\"login\":\"Student\",\"type\":\"User\"}";
	private final Queue<Response> responses = new ConcurrentLinkedQueue<>();
	private final List<Request> requests = new CopyOnWriteArrayList<>();
	private HttpServer server;
	private GitHubClient github;
	private URI base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Authorization"),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), exchange.getRequestHeaders().getFirst("Content-Type")));
			Response response = responses.poll();
			if(response == null) {
				response = new Response(500, "Unexpected request");
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			if(response.status() == 302) {
				exchange.getResponseHeaders().set("Location", base + "redirected");
			}
			byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), response.status() == 204 ? -1 : body.length);
			if(response.status() != 204) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();
		base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
		github = new GitHubClient("provisioning-secret", "client-id", "oauth-secret", base, base);
	}

	@AfterEach
	void stopServer() {
		if(server != null) server.stop(0);
	}

	@Test
	void testCreatePrivateCopyOfDefaultBranch() throws IOException {
		responses.add(new Response(201, REPOSITORY));
		GitHubGateway.Repository repository = github.create("templates/exercise", "teaching", "course-42");
		assertEquals(123, repository.id());
		assertEquals("https://github.com/teaching/course-42", repository.url());
		assertTrue(repository.privateRepository());
		assertFalse(repository.fork());
		Request request = requests.get(0);
		assertEquals("POST", request.method());
		assertEquals("/repos/templates/exercise/generate", request.path());
		assertEquals("Bearer provisioning-secret", request.authorization());
		assertEquals("application/json", request.contentType());
		JsonNode payload = JsonFiles.MAPPER.readTree(request.body());
		assertEquals("teaching", payload.path("owner").asString());
		assertEquals("course-42", payload.path("name").asString());
		assertTrue(payload.path("private").asBoolean());
		assertFalse(payload.path("include_all_branches").asBoolean());
	}

	@Test
	void testStaffMaintainAndStudentWritePermissionsWithInvitationStatus() throws IOException {
		responses.add(new Response(204, ""));
		responses.add(new Response(201, "{}"));
		responses.add(new Response(204, ""));
		github.grantStaff("Teaching", "staff", "teaching/course-42");
		assertTrue(github.invite("teaching/course-42", "student"));
		assertFalse(github.invite("teaching/course-42", "student"));
		assertEquals("/orgs/Teaching/teams/staff/repos/teaching/course-42", requests.get(0).path());
		assertEquals("maintain", JsonFiles.MAPPER.readTree(requests.get(0).body()).path("permission").asString());
		assertEquals("/repos/teaching/course-42/collaborators/student", requests.get(1).path());
		assertEquals("push", JsonFiles.MAPPER.readTree(requests.get(1).body()).path("permission").asString());
		assertTrue(requests.stream().allMatch(request -> request.method().equals("PUT") && request.authorization().equals("Bearer provisioning-secret")));
	}

	@Test
	void testReadNumericAccountAndRepositoryIdentities() throws IOException {
		responses.add(new Response(200, USER));
		responses.add(new Response(200, REPOSITORY));
		assertEquals(new GitHubGateway.User(42, "Student"), github.user("student"));
		assertEquals(123, github.repository("teaching/course-42").id());
		assertEquals("/users/student", requests.get(0).path());
		assertEquals("/repos/teaching/course-42", requests.get(1).path());
	}

	@Test
	void testOAuthUsesPkceAndSeparateStudentToken() throws IOException {
		String redirect = "https://classroom.example/oauth/callback";
		URI authorization = URI.create(github.authorizationUrl(redirect, "state +/=", "challenge"));
		Map<String, String> query = form(authorization.getRawQuery());
		assertEquals("/login/oauth/authorize", authorization.getPath());
		assertEquals("client-id", query.get("client_id"));
		assertEquals(redirect, query.get("redirect_uri"));
		assertEquals("state +/=", query.get("state"));
		assertEquals("challenge", query.get("code_challenge"));
		assertEquals("S256", query.get("code_challenge_method"));
		assertEquals("", query.get("scope"));
		assertFalse(authorization.toString().contains("secret"));
		responses.add(new Response(200, "{\"access_token\":\"student_token\",\"token_type\":\"bearer\"}"));
		responses.add(new Response(200, USER));
		responses.add(new Response(200, USER));
		assertEquals(42, github.authenticate("code +/=", redirect, "verifier +/=").id());
		github.user("student");
		Request exchange = requests.get(0);
		assertEquals("POST", exchange.method());
		assertEquals("/login/oauth/access_token", exchange.path());
		assertEquals("application/x-www-form-urlencoded", exchange.contentType());
		assertNull(exchange.authorization());
		assertEquals(Map.of("client_id", "client-id", "client_secret", "oauth-secret", "code", "code +/=", "redirect_uri", redirect, "code_verifier", "verifier +/="), form(exchange.body()));
		assertEquals("/user", requests.get(1).path());
		assertEquals("Bearer student_token", requests.get(1).authorization());
		assertEquals("Bearer provisioning-secret", requests.get(2).authorization());
	}

	@ParameterizedTest
	@ValueSource(ints = {301, 302, 401, 403, 404, 422, 429, 500})
	void testFailuresNeverRetryOrExposeResponseSecrets(int status) {
		responses.add(new Response(status, "{\"message\":\"provisioning-secret oauth-secret student_token\"}"));
		GitHubException exception = assertThrows(GitHubException.class, () -> github.create("templates/exercise", "teaching", "course-42"));
		assertEquals(Operation.CREATE_REPOSITORY, exception.operation());
		assertEquals(Category.HTTP_STATUS, exception.category());
		assertEquals(status, exception.httpStatus());
		assertFalse(exception.getMessage().contains("secret"));
		assertFalse(exception.getMessage().contains("student_token"));
		assertNull(exception.getCause());
		assertEquals(1, requests.size());
	}

	@ParameterizedTest
	@EnumSource(Operation.class)
	void testHttpFailuresIdentifyTheOperationWithoutRequestOrResponseData(Operation operation) {
		if(operation == Operation.OAUTH_USER_LOOKUP) {
			responses.add(new Response(200, "{\"access_token\":\"student_token\",\"token_type\":\"bearer\"}"));
		}
		responses.add(new Response(403, "{\"message\":\"provisioning-secret oauth-secret student_token\"}"));
		GitHubException failure = assertThrows(GitHubException.class, () -> {
			switch(operation) {
				case ACCOUNT_LOOKUP -> github.user("student");
				case REPOSITORY_LOOKUP -> github.repository("teaching/course-42");
				case CREATE_REPOSITORY -> github.create("templates/exercise", "teaching", "course-42");
				case GRANT_STAFF -> github.grantStaff("teaching", "staff", "teaching/course-42");
				case INVITE_STUDENT -> github.invite("teaching/course-42", "student");
				case OAUTH_TOKEN_EXCHANGE, OAUTH_USER_LOOKUP -> github.authenticate("private-code", "https://classroom.example/callback", "private-verifier");
			}
		});
		assertEquals(operation, failure.operation());
		assertEquals(Category.HTTP_STATUS, failure.category());
		assertEquals(403, failure.httpStatus());
		assertEquals("GitHub " + operation + " failed: HTTP_STATUS (HTTP 403)", failure.getMessage());
		assertNull(failure.getCause());
		assertEquals(operation == Operation.OAUTH_USER_LOOKUP ? 2 : 1, requests.size());
	}

	@Test
	void testMissingOptionalTemplateFlagIsFalseForMetadataAndCreation() throws IOException {
		String withoutFlag = REPOSITORY.replace("\"is_template\":false,", "");
		responses.add(new Response(200, withoutFlag));
		assertFalse(github.repository("teaching/course-42").template());
		responses.add(new Response(201, withoutFlag));
		GitHubGateway.Repository created = github.create("templates/exercise", "teaching", "course-42");
		assertFalse(created.template());
		assertEquals(123, created.id());
		assertTrue(created.privateRepository());
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "\"false\"", "1"})
	void testPresentTemplateFlagMustStillBeBoolean(String flag) {
		responses.add(new Response(201, REPOSITORY.replace("\"is_template\":false", "\"is_template\":" + flag)));
		GitHubException failure = assertThrows(GitHubException.class, () -> github.create("templates/exercise", "teaching", "course-42"));
		assertEquals(Operation.CREATE_REPOSITORY, failure.operation());
		assertEquals(Category.INVALID_RESPONSE, failure.category());
		assertNull(failure.httpStatus());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"id\":42,\"login\":\"someone-else\",\"type\":\"User\"}",
			"{\"id\":\"42\",\"login\":\"student\",\"type\":\"User\"}",
			"{\"id\":42,\"login\":\"student\",\"type\":\"Organization\"}",
			"{\"id\":0,\"login\":\"student\",\"type\":\"User\"}"})
	void testRejectsInvalidOrMismatchedStudentIdentity(String response) {
		responses.add(new Response(200, response));
		GitHubException failure = assertThrows(GitHubException.class, () -> github.user("student"));
		assertEquals(Operation.ACCOUNT_LOOKUP, failure.operation());
		assertEquals(Category.INVALID_RESPONSE, failure.category());
	}

	@Test
	void testRejectsUnexpectedRepositoryIdentityOrVisibility() {
		responses.add(new Response(200, REPOSITORY.replace("teaching/course-42", "other/course-42")));
		assertThrows(IOException.class, () -> github.repository("teaching/course-42"));
		responses.add(new Response(201, REPOSITORY.replace("\"private\":true", "\"private\":false")));
		assertThrows(IOException.class, () -> github.create("templates/exercise", "teaching", "course-42"));
		responses.add(new Response(201, REPOSITORY.replace("\"fork\":false", "\"fork\":true")));
		assertThrows(IOException.class, () -> github.create("templates/exercise", "teaching", "course-42"));
	}

	@Test
	void testRejectsPathInjectionBeforeSendingCredentials() {
		assertThrows(IllegalArgumentException.class, () -> github.user("../user"));
		assertThrows(IllegalArgumentException.class, () -> github.repository("teaching/../other"));
		assertThrows(IllegalArgumentException.class, () -> github.create("templates/exercise", "teaching", ".."));
		assertThrows(IllegalArgumentException.class, () -> github.grantStaff("teaching", "staff", "other/course-42"));
		assertThrows(IllegalArgumentException.class, () -> github.invite("teaching/course-42", "student?token=private"));
		assertTrue(requests.isEmpty());
	}

	@Test
	void testRejectsOversizedOrMalformedResponsesWithoutLeaks() {
		responses.add(new Response(200, "{\"secret\":\"" + "x".repeat(1024 * 1024) + "\"}"));
		assertThrows(IOException.class, () -> github.user("student"));
		responses.add(new Response(200, "{\"access_token\":\"student_token\", malformed-secret"));
		GitHubException exception = assertThrows(GitHubException.class, () -> github.authenticate("code", "https://classroom.example/callback", "verifier"));
		assertEquals(Operation.OAUTH_TOKEN_EXCHANGE, exception.operation());
		assertEquals(Category.INVALID_JSON, exception.category());
		assertFalse(exception.getMessage().contains("student_token"));
		assertFalse(exception.getMessage().contains("malformed-secret"));
		assertNull(exception.getCause());
	}

	@Test
	void testOAuthErrorDoesNotBecomeAnAuthenticatedUser() {
		responses.add(new Response(200, "{\"error\":\"bad_verification_code\",\"error_description\":\"private details\"}"));
		GitHubException exception = assertThrows(GitHubException.class, () -> github.authenticate("code", "https://classroom.example/callback", "verifier"));
		assertEquals(Operation.OAUTH_TOKEN_EXCHANGE, exception.operation());
		assertEquals(Category.AUTHENTICATION_REJECTED, exception.category());
		assertFalse(exception.getMessage().contains("private details"));
		assertEquals(1, requests.size());
	}

	@Test
	void testCredentialsAreRequiredOnlyForTheOperationUsingThem() throws IOException {
		GitHubClient lookup = new GitHubClient("provisioning-secret", null, null, base, base);
		responses.add(new Response(200, USER));
		assertEquals(42, lookup.user("student").id());
		assertThrows(IllegalStateException.class, () -> lookup.authorizationUrl("https://classroom.example/callback", "state", "challenge"));
		GitHubClient offline = new GitHubClient(null, null, null, base, base);
		assertThrows(IllegalStateException.class, () -> offline.user("student"));
		assertEquals(1, requests.size());
	}

	private static Map<String, String> form(String input) {
		Map<String, String> fields = new LinkedHashMap<>();
		for(String field : input.split("&")) {
			String[] pair = field.split("=", 2);
			fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
		}
		return fields;
	}

	private record Request(String method, String path, String authorization, String body, String contentType) { }
	private record Response(int status, String body) { }
}
