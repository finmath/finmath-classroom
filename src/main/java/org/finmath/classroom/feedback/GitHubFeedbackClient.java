package org.finmath.classroom.feedback;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.finmath.classroom.io.JsonFiles;

import tools.jackson.databind.JsonNode;

/** Bounded, read-only GitHub.com Actions adapter. Never executes repository code. */
public final class GitHubFeedbackClient implements FeedbackSource {
    private static final URI GITHUB_API = URI.create("https://api.github.com/");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int JSON_LIMIT = 4 * 1024 * 1024;
    private static final int ARCHIVE_LIMIT = 2 * 1024 * 1024;
    private static final int PAGE_SIZE = 100;
    private static final int PAGE_LIMIT = 100;
    private final String token;
    private final URI api;
    private final HttpClient http;

    public GitHubFeedbackClient(String token) {
        this(token, GITHUB_API, HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Package-private transport seam for offline tests; production always uses GitHub.com. */
    GitHubFeedbackClient(String token, URI api, HttpClient http) {
        if (api == null || http == null || http.followRedirects() != HttpClient.Redirect.NEVER
                || api.getUserInfo() != null || api.getQuery() != null || api.getFragment() != null
                || !"/".equals(api.getPath()) || (!GITHUB_API.equals(api)
                && !("http".equals(api.getScheme()) && "127.0.0.1".equals(api.getHost()) && api.getPort() > 0))) {
            throw new IllegalArgumentException("Invalid GitHub feedback transport");
        }
        this.token = token;
        this.api = api;
        this.http = http;
    }

    @Override
    public Repository repository(String fullName) throws IOException {
        String name = repositoryPath(fullName);
        JsonNode value = json(api("repos/" + name, 200));
        String actualName = text(value, "full_name", 140);
        if (!actualName.equalsIgnoreCase(name)) throw invalidMetadata();
        return new Repository(positive(value, "id"), actualName, bool(value, "private"), bool(value, "fork"));
    }

    @Override
    public List<Run> runs(String repository, String workflowFile) throws IOException {
        String name = repositoryPath(repository);
        if (workflowFile == null || !workflowFile.matches("[A-Za-z0-9_.-]{1,255}")
                || workflowFile.equals(".") || workflowFile.equals("..")) {
            throw new IllegalArgumentException("Invalid workflow file name");
        }
        List<Run> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        int scanned = 0;
        // Deliberately unfiltered: GitHub caps searches using branch/event/status/etc. at 1,000 runs.
        String route = "repos/" + name + "/actions/workflows/" + workflowFile + "/runs";
        for (int page = 1; page <= PAGE_LIMIT; page++) {
            JsonNode value = json(api(route + "?per_page=" + PAGE_SIZE + "&page=" + page, 200));
            JsonNode entries = page(value, "workflow_runs");
            for (JsonNode entry : entries) {
                if (!ids.add(positive(entry, "id"))) throw invalidMetadata();
                // Pull requests and tag pushes can have different or null branch/ref metadata.
                // Skip these locally without changing the unfiltered API request or page count.
                if (!"push".equals(text(entry, "event", 64)) || entry.path("head_branch").isNull()) continue;
                Run run = run(entry, name);
                result.add(run);
            }
            scanned += entries.size();
            if (finished(value, entries.size(), scanned)) return List.copyOf(result);
        }
        throw paginationLimit();
    }

    @Override
    public Run attempt(String repository, long runId, int attempt) throws IOException {
        String name = repositoryPath(repository);
        requireId(runId);
        if (attempt < 1 || attempt > 1000) throw new IllegalArgumentException("Invalid workflow run attempt");
        Run result = run(json(api("repos/" + name + "/actions/runs/" + runId + "/attempts/" + attempt, 200)), name);
        if (result.id() != runId || result.attempt() != attempt) throw invalidMetadata();
        return result;
    }

    @Override
    public List<Artifact> artifacts(String repository, long runId) throws IOException {
        String name = repositoryPath(repository);
        requireId(runId);
        List<Artifact> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int page = 1; page <= PAGE_LIMIT; page++) {
            JsonNode value = json(api("repos/" + name + "/actions/runs/" + runId
                    + "/artifacts?per_page=" + PAGE_SIZE + "&page=" + page, 200));
            JsonNode entries = page(value, "artifacts");
            for (JsonNode entry : entries) {
                JsonNode workflowRun = entry.path("workflow_run");
                long actualRunId = positive(workflowRun, "id");
                if (actualRunId != runId) throw invalidMetadata();
                long artifactId = positive(entry, "id");
                if (!ids.add(artifactId)) throw invalidMetadata();
                String digest = nullableText(entry, "digest", 71);
                if (digest != null && !digest.matches("sha256:[a-fA-F0-9]{64}")) throw invalidMetadata();
                result.add(new Artifact(artifactId, text(entry, "name", 255), actualRunId,
                        positive(workflowRun, "repository_id"), sha(workflowRun, "head_sha"),
                        bool(entry, "expired"), nonnegative(entry, "size_in_bytes"), digest));
            }
            if (finished(value, entries.size(), result.size())) return List.copyOf(result);
        }
        throw paginationLimit();
    }

    @Override
    public byte[] download(String repository, long artifactId) throws IOException {
        String name = repositoryPath(repository);
        requireId(artifactId);
        // Ignore archive_download_url from untrusted metadata and derive the API route from identity.
        HttpResponse<byte[]> response = api("repos/" + name + "/actions/artifacts/" + artifactId + "/zip", 302);
        URI previous = api;
        for (int redirects = 0; redirects < 3; redirects++) {
            URI destination;
            try {
                String location = response.headers().firstValue("Location").orElseThrow();
                if (location.length() > 16384) throw new IllegalArgumentException();
                destination = previous.resolve(URI.create(location));
            } catch (RuntimeException exception) {
                throw failure("Invalid artifact download redirect");
            }
            validateDownload(destination);
            response = request(destination, false, ARCHIVE_LIMIT);
            if (response.statusCode() == 200) return response.body();
            if (!redirect(response.statusCode())) throw httpFailure(response);
            previous = destination;
        }
        throw failure("Artifact download redirect limit exceeded");
    }

    private HttpResponse<byte[]> api(String route, int expectedStatus) throws IOException {
        HttpResponse<byte[]> response = request(api.resolve(route), true, JSON_LIMIT);
        if (response.statusCode() != expectedStatus) throw httpFailure(response);
        return response;
    }

    private HttpResponse<byte[]> request(URI uri, boolean authenticated, int limit) throws IOException {
        CompletableFuture<HttpResponse<byte[]>> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("User-Agent", "finmath-classroom-feedback").GET();
            if (authenticated) {
                if (!sameOrigin(api, uri)) throw failure("Invalid GitHub API origin");
                if (token == null || token.isBlank() || token.length() > 4096 || !token.matches("[A-Za-z0-9_]+")) {
                    throw failure("A valid CLASSROOM_GITHUB_TOKEN is required for feedback collection");
                }
                request.header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Authorization", "Bearer " + token);
            } else {
                request.header("Accept", "application/octet-stream");
            }
            response = http.sendAsync(request.build(), information -> new LimitedBody(limit));
        } catch (RuntimeException exception) {
            throw failure("GitHub feedback request failed");
        }
        try {
            return response.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw failure("GitHub feedback request interrupted");
        } catch (TimeoutException exception) {
            response.cancel(true);
            throw failure("GitHub feedback request timed out");
        } catch (ExecutionException exception) {
            response.cancel(true);
            throw failure(exception.getCause() instanceof HttpTimeoutException
                    ? "GitHub feedback request timed out" : "GitHub feedback transport or response size failure");
        } catch (RuntimeException exception) {
            response.cancel(true);
            throw failure("GitHub feedback request failed");
        }
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost()) && expected.getPort() == actual.getPort()
                && actual.getUserInfo() == null;
    }

    private static void validateDownload(URI uri) throws Failure {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com")
                || host.equals("release-assets.githubusercontent.com"))) {
            throw failure("Rejected artifact download redirect");
        }
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Failure httpFailure(HttpResponse<?> response) {
        int status = response.statusCode();
        Instant retryAt = null;
        boolean limited = status == 429 || (status == 403 &&
                (response.headers().firstValue("Retry-After").isPresent()
                || response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")));
        if (limited) {
            Instant now = Instant.now();
            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            try {
                long seconds = Long.parseLong(retryAfter);
                if (seconds >= 0) retryAt = now.plusSeconds(seconds);
            } catch (RuntimeException exception) {
                try {
                    retryAt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (RuntimeException ignored) { /* No valid Retry-After value. */ }
            }
            if (retryAt == null) {
                try {
                    retryAt = Instant.ofEpochSecond(Long.parseLong(response.headers().firstValue("X-RateLimit-Reset").orElse("")));
                } catch (RuntimeException ignored) { /* Fall back for a secondary rate limit. */ }
            }
            if (retryAt == null || !retryAt.isAfter(now)) retryAt = now.plusSeconds(60);
        }
        return new Failure("GitHub feedback request returned HTTP " + status, status, retryAt);
    }

    private static JsonNode json(HttpResponse<byte[]> response) throws Failure {
        try {
            JsonNode value = JsonFiles.MAPPER.readTree(response.body());
            if (value != null && value.isObject()) return value;
        } catch (RuntimeException exception) { /* Never retain parser diagnostics containing private response data. */ }
        throw failure("Invalid GitHub feedback JSON response");
    }

    private static JsonNode page(JsonNode value, String field) throws Failure {
        JsonNode entries = value.path(field);
        long count = nonnegative(value, "total_count");
        if (!entries.isArray() || entries.size() > PAGE_SIZE) throw invalidMetadata();
        if (count > PAGE_SIZE * PAGE_LIMIT) throw paginationLimit();
        return entries;
    }

    private static boolean finished(JsonNode value, int pageSize, int collected) throws Failure {
        long count = nonnegative(value, "total_count");
        if (collected >= count) return true;
        if (pageSize == 0) throw failure("Incomplete GitHub feedback pagination");
        return false;
    }

    private static Run run(JsonNode value, String expectedRepository) throws Failure {
        JsonNode repository = value.path("repository");
        String name = text(repository, "full_name", 140);
        if (!name.equalsIgnoreCase(expectedRepository)) throw invalidMetadata();
        long attempt = positive(value, "run_attempt");
        if (attempt > 1000) throw invalidMetadata();
        Instant created;
        try {
            created = Instant.parse(text(value, "created_at", 64));
        } catch (RuntimeException exception) {
            throw invalidMetadata();
        }
        String branch = text(value, "head_branch", 255);
        String commit = sha(value, "head_sha");
        return new Run(positive(value, "id"), (int) attempt, positive(repository, "id"), name,
                branch, commit, text(value, "event", 64),
                text(value, "status", 64), nullableText(value, "conclusion", 64), workflowPath(value, branch, commit),
                positive(value, "workflow_id"), created);
    }

    private static String workflowPath(JsonNode value, String branch, String commit) throws Failure {
        String path = text(value, "path", 1024);
        int separator = path.indexOf('@');
        if (separator < 0) return path;
        if (separator == 0) throw invalidMetadata();
        String reference = path.substring(separator + 1);
        if (!reference.equals(branch) && !reference.equals("refs/heads/" + branch)
                && !reference.equalsIgnoreCase(commit)) throw invalidMetadata();
        return path.substring(0, separator);
    }

    private static String sha(JsonNode value, String field) throws Failure {
        String sha = text(value, field, 64);
        if (!sha.matches("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")) throw invalidMetadata();
        return sha;
    }

    private static String text(JsonNode value, String field, int limit) throws Failure {
        JsonNode node = value.path(field);
        if (!node.isString()) throw invalidMetadata();
        String text = node.asString();
        if (text.isBlank() || text.length() > limit || text.chars().anyMatch(Character::isISOControl)) throw invalidMetadata();
        return text;
    }

    private static String nullableText(JsonNode value, String field, int limit) throws Failure {
        JsonNode node = value.path(field);
        return node.isNull() || node.isMissingNode() ? null : text(value, field, limit);
    }

    private static boolean bool(JsonNode value, String field) throws Failure {
        if (!value.path(field).isBoolean()) throw invalidMetadata();
        return value.path(field).asBoolean();
    }

    private static long nonnegative(JsonNode value, String field) throws Failure {
        JsonNode number = value.path(field);
        if (!number.isIntegralNumber() || !number.canConvertToLong() || number.asLong() < 0) throw invalidMetadata();
        return number.asLong();
    }

    private static long positive(JsonNode value, String field) throws Failure {
        long result = nonnegative(value, field);
        if (result == 0) throw invalidMetadata();
        return result;
    }

    private static String repositoryPath(String repository) {
        if (repository == null || !repository.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
                || repository.endsWith("/.") || repository.endsWith("/..")) {
            throw new IllegalArgumentException("Invalid GitHub repository name; expected owner/name");
        }
        return repository;
    }

    private static void requireId(long id) {
        if (id <= 0) throw new IllegalArgumentException("Invalid GitHub identifier");
    }

    private static Failure invalidMetadata() { return failure("Invalid GitHub feedback metadata"); }
    private static Failure paginationLimit() { return failure("GitHub feedback pagination limit exceeded"); }
    private static Failure failure(String message) { return new Failure(message, 0, null); }

    /** Cancels an oversized body before its buffers reach the accumulating subscriber. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private int bytes;
        private boolean failed;

        LimitedBody(int limit) { this.limit = limit; }

        @Override
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new IOException("GitHub feedback response exceeds size limit"));
                    return;
                }
                bytes += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) { if (!failed) delegate.onError(throwable); }

        @Override
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
