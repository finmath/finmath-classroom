package org.finmath.classroom.feedback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.finmath.classroom.feedback.FeedbackSource.Failure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

class GitHubFeedbackClientTest {
    private static final String REPOSITORY = "teaching/course-42";
    private static final String TOKEN = "github_pat_private_feedback_token";
    private static final String SHA = "a".repeat(40);
    private static final String SIGNED = "https://store.blob.core.windows.net/artifact?sig=private-signed-secret";
    private final Queue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<URI> destinations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI base;
    private HttpClient realHttp;
    private GitHubFeedbackClient github;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            Response response = responses.poll();
            if (response == null) response = new Response(500, "Unexpected request", Map.of());
            response.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            // Chunked transfer exercises streaming bounds independently of Content-Length.
            exchange.sendResponseHeaders(response.status(), 0);
            try {
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) { /* Oversized responses are deliberately cancelled. */ }
            finally { exchange.close(); }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        realHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        github = new GitHubFeedbackClient(TOKEN, base, realHttp);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsRepositoryWithGetAndPrivateToken() throws IOException {
        reply(200, repository());
        FeedbackSource.Repository result = github.repository(REPOSITORY);
        assertEquals(123, result.id());
        assertEquals(REPOSITORY, result.name());
        assertTrue(result.privateRepository());
        assertFalse(result.fork());
        assertEquals(new Request("GET", "/repos/" + REPOSITORY, "Bearer " + TOKEN), requests.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"123\"", "0", "-1", "1.5", "9223372036854775808", "true", "null", "{}"})
    void rejectsRepositoryIdentityTypeCoercion(String id) {
        reply(200, repository().replace("\"id\":123", "\"id\":" + id));
        assertThrows(Failure.class, () -> github.repository(REPOSITORY));
    }

    @Test
    void rejectsRepositoryPrivacyTypeCoercionAndWrongName() {
        reply(200, repository().replace("\"private\":true", "\"private\":\"true\""));
        assertThrows(Failure.class, () -> github.repository(REPOSITORY));
        reply(200, repository().replace(REPOSITORY, "other/private"));
        assertThrows(Failure.class, () -> github.repository(REPOSITORY));
    }

    @Test
    void paginatesRunsWithoutSearchFilters() throws IOException {
        reply(200, page("workflow_runs", 2, run(11, 1)));
        reply(200, page("workflow_runs", 2, run(12, 2)));
        List<FeedbackSource.Run> result = github.runs(REPOSITORY, "feedback.yml");
        assertEquals(List.of(11L, 12L), result.stream().map(FeedbackSource.Run::id).toList());
        assertEquals(2, result.get(1).attempt());
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), result.get(0).createdAt());
        assertEquals("/repos/" + REPOSITORY + "/actions/workflows/feedback.yml/runs?per_page=100&page=1", requests.get(0).path());
        assertEquals("/repos/" + REPOSITORY + "/actions/workflows/feedback.yml/runs?per_page=100&page=2", requests.get(1).path());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void readsSpecificAttemptAndChecksReturnedIdentity() throws IOException {
        reply(200, run(11, 2));
        assertEquals(2, github.attempt(REPOSITORY, 11, 2).attempt());
        assertEquals("/repos/" + REPOSITORY + "/actions/runs/11/attempts/2", requests.get(0).path());
        reply(200, run(11, 1));
        assertThrows(Failure.class, () -> github.attempt(REPOSITORY, 11, 2));
        reply(200, run(12, 2));
        assertThrows(Failure.class, () -> github.attempt(REPOSITORY, 11, 2));
    }

    @Test
    void unrelatedPullRequestAndTagRunsDoNotBlockPushCollectionOrPagination() throws IOException {
        String pullRequest = run(11, 1).replace("\"event\":\"push\"", "\"event\":\"pull_request\"")
                .replace("\"head_branch\":\"main\"", "\"head_branch\":null")
                .replace("feedback.yml", "feedback.yml@refs/pull/42/merge");
        String tag = run(12, 1).replace("\"head_branch\":\"main\"", "\"head_branch\":null")
                .replace("feedback.yml", "feedback.yml@refs/tags/v1");
        reply(200, page("workflow_runs", 3, pullRequest + "," + tag));
        reply(200, page("workflow_runs", 3, run(13, 1)));
        assertEquals(List.of(13L), github.runs(REPOSITORY, "feedback.yml").stream().map(FeedbackSource.Run::id).toList());
        assertEquals(2, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "0", "1001", "1.5", "null"})
    void rejectsInvalidAttemptMetadata(String attempt) {
        reply(200, page("workflow_runs", 1, run(11, 1).replace("\"run_attempt\":1", "\"run_attempt\":" + attempt)));
        assertThrows(Failure.class, () -> github.runs(REPOSITORY, "feedback.yml"));
    }

    @Test
    void rejectsMalformedRunMetadata() {
        for (String malformed : List.of(
                run(11, 1).replace(SHA, "not-a-commit"),
                run(11, 1).replace("\"head_branch\":\"main\"", "\"head_branch\":42"),
                run(11, 1).replace("\"main\"", "\"" + "a".repeat(256) + "\""),
                run(11, 1).replace("2026-10-01T12:00:00Z", "private-invalid-time"),
                run(11, 1).replace("\"workflow_id\":99", "\"workflow_id\":\"99\""))) {
            reply(200, page("workflow_runs", 1, malformed));
            assertThrows(Failure.class, () -> github.runs(REPOSITORY, "feedback.yml"));
        }
    }

    @Test
    void normalizesDocumentedWorkflowPathReferences() throws IOException {
        for (String reference : List.of("main", "refs/heads/main", SHA.toUpperCase(java.util.Locale.ROOT))) {
            reply(200, page("workflow_runs", 1, run(11, 1).replace("feedback.yml", "feedback.yml@" + reference)));
            assertEquals(".github/workflows/feedback.yml", github.runs(REPOSITORY, "feedback.yml").get(0).workflowPath());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong-branch", "refs/heads/wrong-branch", "refs/tags/main", "main@main"})
    void rejectsWorkflowPathReferenceMismatch(String reference) {
        reply(200, page("workflow_runs", 1, run(11, 1).replace("feedback.yml", "feedback.yml@" + reference)));
        assertThrows(Failure.class, () -> github.runs(REPOSITORY, "feedback.yml"));
    }

    @Test
    void paginatesArtifactsAndReadsMandatoryWorkflowIdentity() throws IOException {
        reply(200, page("artifacts", 2, artifact(21)));
        reply(200, page("artifacts", 2, artifact(22)));
        List<FeedbackSource.Artifact> result = github.artifacts(REPOSITORY, 11);
        assertEquals(List.of(21L, 22L), result.stream().map(FeedbackSource.Artifact::id).toList());
        assertEquals(123, result.get(0).repositoryId());
        assertEquals(SHA, result.get(0).commit());
        assertEquals("sha256:" + "b".repeat(64), result.get(0).digest());
        assertEquals("/repos/" + REPOSITORY + "/actions/runs/11/artifacts?per_page=100&page=2", requests.get(1).path());
    }

    @Test
    void rejectsMissingOrCoercedArtifactIdentity() {
        for (String malformed : List.of(
                artifact(21).replace("\"workflow_run\":", "\"unused\":"),
                artifact(21).replace("\"repository_id\":123", "\"repository_id\":\"123\""),
                artifact(21).replace("\"id\":11", "\"id\":12"),
                artifact(21).replace(SHA, ""),
                artifact(21).replace("\"expired\":false", "\"expired\":\"false\""),
                artifact(21).replace("\"size_in_bytes\":64", "\"size_in_bytes\":-1"))) {
            reply(200, page("artifacts", 1, malformed));
            assertThrows(Failure.class, () -> github.artifacts(REPOSITORY, 11));
        }
    }

    @Test
    void rejectsTruncatedOrOverLimitPagination() {
        reply(200, page("workflow_runs", 10001, run(11, 1)));
        assertTrue(assertThrows(Failure.class, () -> github.runs(REPOSITORY, "feedback.yml")).getMessage().contains("pagination limit"));
        reply(200, page("artifacts", 1, ""));
        assertTrue(assertThrows(Failure.class, () -> github.artifacts(REPOSITORY, 11)).getMessage().contains("Incomplete"));
        reply(200, page("artifacts", 2, artifact(21)));
        reply(200, page("artifacts", 2, artifact(21)));
        assertThrows(Failure.class, () -> github.artifacts(REPOSITORY, 11));
    }

    @Test
    void enforcesOneHundredPageLimitWithoutReturningPartialResults() {
        for (int i = 1; i <= 100; i++) reply(200, page("artifacts", 101, artifact(i)));
        Failure failure = assertThrows(Failure.class, () -> github.artifacts(REPOSITORY, 11));
        assertTrue(failure.getMessage().contains("pagination limit"));
        assertEquals(100, requests.size());
    }

    @Test
    void downloadsOnlyKnownArtifactRouteAndNeverForwardsAuthorization() throws IOException {
        routeStorageToLocalServer();
        reply(302, "", Map.of("Location", SIGNED));
        reply(200, "archive bytes");
        assertArrayEquals("archive bytes".getBytes(StandardCharsets.UTF_8), github.download(REPOSITORY, 21));
        assertEquals("/repos/" + REPOSITORY + "/actions/artifacts/21/zip", requests.get(0).path());
        assertEquals("Bearer " + TOKEN, requests.get(0).authorization());
        assertNull(requests.get(1).authorization());
        assertEquals(URI.create(SIGNED), destinations.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://store.blob.core.windows.net/archive",
            "https://evil.example/archive?secret=value",
            "https://blob.core.windows.net.evil.example/archive",
            "https://api.github.com/archive",
            "https://127.0.0.1/archive",
            "https://user:secret@store.blob.core.windows.net/archive",
            "https://store.blob.core.windows.net:444/archive",
            "https://store.blob.core.windows.net/archive#secret",
            "https://store.blob.core.windows.net./archive",
            "file:///private/artifact",
            "not a uri"
    })
    void rejectsUnsafeRedirectsBeforeSendingRequests(String location) {
        reply(302, "private body", Map.of("Location", location));
        Failure failure = assertThrows(Failure.class, () -> github.download(REPOSITORY, 21));
        assertEquals(1, requests.size());
        assertSanitized(failure);
        assertFalse(failure.getMessage().contains(location));
    }

    @Test
    void validatesEveryStorageRedirectAndBoundsRedirectCount() {
        routeStorageToLocalServer();
        reply(302, "", Map.of("Location", SIGNED));
        reply(302, "", Map.of("Location", "https://evil.example/private-signed-secret"));
        assertThrows(Failure.class, () -> github.download(REPOSITORY, 21));
        assertEquals(2, requests.size());
        requests.clear();
        for (int i = 0; i < 4; i++) reply(302, "", Map.of("Location", SIGNED));
        Failure failure = assertThrows(Failure.class, () -> github.download(REPOSITORY, 21));
        assertTrue(failure.getMessage().contains("redirect limit"));
        assertEquals(4, requests.size());
        assertTrue(requests.subList(1, requests.size()).stream().allMatch(request -> request.authorization() == null));
    }

    @Test
    void boundsArchiveAndJsonBodiesDuringStreaming() {
        routeStorageToLocalServer();
        reply(302, "", Map.of("Location", SIGNED));
        reply(200, "x".repeat(2 * 1024 * 1024 + 1));
        assertSanitized(assertThrows(Failure.class, () -> github.download(REPOSITORY, 21)));
        reply(200, "x".repeat(4 * 1024 * 1024 + 1));
        assertSanitized(assertThrows(Failure.class, () -> github.repository(REPOSITORY)));
    }

    @Test
    void surfacesExpiredArtifactAndSanitizesErrorBodies() {
        reply(410, "private-signed-secret " + TOKEN + " https://private.example");
        Failure failure = assertThrows(Failure.class, () -> github.download(REPOSITORY, 21));
        assertEquals(410, failure.status());
        assertNull(failure.retryAt());
        assertSanitized(failure);
        reply(200, "{malformed-private-signed-secret-" + TOKEN);
        assertSanitized(assertThrows(Failure.class, () -> github.repository(REPOSITORY)));
    }

    @Test
    void reportsRateLimitDeadlineWithoutSleeping() {
        Instant before = Instant.now();
        reply(429, "private-signed-secret", Map.of("Retry-After", "120"));
        Failure secondary = assertThrows(Failure.class, () -> github.repository(REPOSITORY));
        assertEquals(429, secondary.status());
        assertTrue(secondary.retryAt().isAfter(before.plusSeconds(119)));
        assertTrue(secondary.retryAt().isBefore(Instant.now().plusSeconds(121)));
        Instant reset = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        reply(403, "private-signed-secret", Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", Long.toString(reset.getEpochSecond())));
        Failure primary = assertThrows(Failure.class, () -> github.repository(REPOSITORY));
        assertEquals(reset, primary.retryAt());
        assertSanitized(primary);
    }

    @Test
    void doesNotMistakePermissionFailuresForRateLimits() {
        reply(403, "private-signed-secret", Map.of("X-RateLimit-Remaining", "4999",
                "X-RateLimit-Reset", Long.toString(Instant.now().plusSeconds(600).getEpochSecond())));
        Failure failure = assertThrows(Failure.class, () -> github.repository(REPOSITORY));
        assertEquals(403, failure.status());
        assertNull(failure.retryAt());
        assertSanitized(failure);
        reply(401, "private-signed-secret", Map.of("Retry-After", "120"));
        assertNull(assertThrows(Failure.class, () -> github.repository(REPOSITORY)).retryAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You have exceeded a secondary rate limit. Please wait a few minutes before you try again.",
            "You have triggered an abuse detection mechanism. Please wait a few minutes before you try again."
    })
    void recognizesSecondaryLimitsWithoutRetryHeadersOrExhaustedPrimaryQuota(String message) {
        Instant before = Instant.now();
        reply(403, "{\"message\":\"" + message + " private-signed-secret " + TOKEN + "\"}",
                Map.of("X-RateLimit-Remaining", "4999",
                        "X-RateLimit-Reset", Long.toString(before.plusSeconds(5).getEpochSecond())));

        Failure failure = assertThrows(Failure.class, () -> github.repository(REPOSITORY));

        assertEquals(403, failure.status());
        assertTrue(failure.retryAt().isAfter(before.plusSeconds(59)));
        assertTrue(failure.retryAt().isBefore(Instant.now().plusSeconds(61)));
        assertSanitized(failure);
    }

    @Test
    void ignoresPermissionMessagesAndMalformedOrOversizedErrorJson() {
        for (String body : List.of("{\"message\":\"Resource not accessible by personal access token\"}",
                "{\"message\":\"secondary rate limit private-signed-secret",
                "{\"message\":\"secondary rate limit\",\"padding\":\"" + "x".repeat(64 * 1024) + "\"}")) {
            reply(403, body, Map.of("X-RateLimit-Remaining", "4999"));
            Failure failure = assertThrows(Failure.class, () -> github.repository(REPOSITORY));
            assertNull(failure.retryAt());
            assertSanitized(failure);
        }
    }

    @Test
    void sanitizesTransportFailuresAndRejectsRedirectingHttpClient() {
        HttpClient transport = mock(HttpClient.class);
        when(transport.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(transport.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException(TOKEN + " " + SIGNED)));
        GitHubFeedbackClient failed = new GitHubFeedbackClient(TOKEN, base, transport);
        assertSanitized(assertThrows(Failure.class, () -> failed.repository(REPOSITORY)));
        assertThrows(IllegalArgumentException.class, () -> new GitHubFeedbackClient(TOKEN, base,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()));
        assertThrows(IllegalArgumentException.class, () -> new GitHubFeedbackClient(TOKEN, URI.create("https://evil.example/"), realHttp));
    }

    private void routeStorageToLocalServer() {
        HttpClient transport = mock(HttpClient.class);
        when(transport.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(transport.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    destinations.add(request.uri());
                    if (request.uri().getScheme().equals("https")) {
                        // Test-only routing preserves the outbound headers and body subscriber.
                        HttpRequest.Builder local = HttpRequest.newBuilder(base.resolve("storage")).GET();
                        request.headers().map().forEach((key, values) -> values.forEach(value -> local.header(key, value)));
                        request = local.build();
                    }
                    return realHttp.sendAsync(request, handler);
                });
        github = new GitHubFeedbackClient(TOKEN, base, transport);
    }

    private void reply(int status, String body) { reply(status, body, Map.of()); }
    private void reply(int status, String body, Map<String, String> headers) { responses.add(new Response(status, body, headers)); }

    private static void assertSanitized(Failure failure) {
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains("private-signed-secret"));
        assertFalse(failure.getMessage().contains("https://"));
        assertNull(failure.getCause());
    }

    private static String repository() {
        return "{\"id\":123,\"full_name\":\"" + REPOSITORY + "\",\"private\":true,\"fork\":false}";
    }

    private static String run(long id, int attempt) {
        return "{\"id\":" + id + ",\"run_attempt\":" + attempt + ",\"repository\":" + repository()
                + ",\"head_branch\":\"main\",\"head_sha\":\"" + SHA + "\",\"event\":\"push\",\"status\":\"completed\","
                + "\"conclusion\":\"success\",\"path\":\".github/workflows/feedback.yml\",\"workflow_id\":99,"
                + "\"created_at\":\"2026-10-01T12:00:00Z\"}";
    }

    private static String artifact(long id) {
        return "{\"id\":" + id + ",\"name\":\"feedback\",\"expired\":false,\"size_in_bytes\":64,\"digest\":\"sha256:"
                + "b".repeat(64) + "\",\"archive_download_url\":\"https://evil.example/ignored\","
                + "\"workflow_run\":{\"id\":11,\"repository_id\":123,\"head_sha\":\"" + SHA + "\"}}";
    }

    private static String page(String name, int total, String elements) {
        return "{\"total_count\":" + total + ",\"" + name + "\":[" + elements + "]}";
    }

    private record Response(int status, String body, Map<String, String> headers) { }
    private record Request(String method, String path, String authorization) { }
}
