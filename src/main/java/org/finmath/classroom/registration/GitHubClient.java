package org.finmath.classroom.registration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.finmath.classroom.io.JsonFiles;

import tools.jackson.databind.JsonNode;

/** Small GitHub.com HTTP adapter with separate provisioning and student authentication credentials. */
public final class GitHubClient implements GitHubGateway {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	private static final int RESPONSE_LIMIT = 1024 * 1024;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
	private final String provisioningToken;
	private final String clientId;
	private final String clientSecret;
	private final URI api;
	private final URI oauth;

	/** Creates a GitHub.com client. Credentials are never included in exceptions or persisted here. */
	public GitHubClient(String provisioningToken, String clientId, String clientSecret) {
		this(provisioningToken, clientId, clientSecret, URI.create("https://api.github.com/"), URI.create("https://github.com/"));
	}

	GitHubClient(String provisioningToken, String clientId, String clientSecret, URI api, URI oauth) {
		this.provisioningToken = provisioningToken;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.api = api;
		this.oauth = oauth;
	}

	@Override
	public User user(String login) throws IOException {
		User user = readUser(json(request(api.resolve("users/" + account(login)), "GET", provisioningToken(), null, 200)));
		if(!user.login().equalsIgnoreCase(login)) {
			throw new IOException("GitHub account identity did not match the requested login");
		}
		return user;
	}

	@Override
	public Repository repository(String fullName) throws IOException {
		return readRepository(json(request(api.resolve("repos/" + repositoryPath(fullName)), "GET", provisioningToken(), null, 200)), fullName);
	}

	@Override
	public Repository create(String template, String organization, String name) throws IOException {
		String fullName = repositoryPath(account(organization) + "/" + name);
		Map<String, Object> payload = Map.of("owner", organization, "name", name, "private", true, "include_all_branches", false);
		Repository repository = readRepository(json(request(api.resolve("repos/" + repositoryPath(template) + "/generate"), "POST", provisioningToken(), payload, 201)), fullName);
		if(!repository.privateRepository() || repository.fork()) {
			throw new IOException("GitHub did not return a private independent template copy");
		}
		return repository;
	}

	@Override
	public void grantStaff(String organization, String team, String repository) throws IOException {
		String fullName = repositoryPath(repository);
		if(!fullName.substring(0, fullName.indexOf('/')).equalsIgnoreCase(account(organization)) || team == null || !team.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")) {
			throw new IllegalArgumentException("Invalid staff team or repository organization");
		}
		request(api.resolve("orgs/" + organization + "/teams/" + team + "/repos/" + fullName), "PUT", provisioningToken(), Map.of("permission", "maintain"), 204);
	}

	@Override
	public boolean invite(String repository, String login) throws IOException {
		return request(api.resolve("repos/" + repositoryPath(repository) + "/collaborators/" + account(login)), "PUT", provisioningToken(), Map.of("permission", "push"), 201, 204).statusCode() == 201;
	}

	@Override
	public String authorizationUrl(String redirectUri, String state, String codeChallenge) {
		requireOAuth();
		return oauth.resolve("login/oauth/authorize") + "?" + form(Map.of("client_id", clientId, "redirect_uri", redirectUri,
				"state", state, "code_challenge", codeChallenge, "code_challenge_method", "S256", "scope", "", "prompt", "select_account"));
	}

	@Override
	public User authenticate(String code, String redirectUri, String codeVerifier) throws IOException {
		requireOAuth();
		String payload = form(Map.of("client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", redirectUri, "code_verifier", codeVerifier));
		JsonNode response = json(request(oauth.resolve("login/oauth/access_token"), "POST", null, payload, 200));
		String token = response.path("access_token").asString("");
		if(response.has("error") || token.isBlank() || token.length() > 1024 || !token.matches("[A-Za-z0-9_]+") || !"bearer".equalsIgnoreCase(response.path("token_type").asString(""))) {
			throw new IOException("GitHub sign-in could not be completed");
		}
		return readUser(json(request(api.resolve("user"), "GET", token, null, 200)));
	}

	private String provisioningToken() {
		if(provisioningToken == null || provisioningToken.isBlank()) {
			throw new IllegalStateException("CLASSROOM_GITHUB_TOKEN is required for GitHub repository and roster operations");
		}
		return provisioningToken;
	}

	private void requireOAuth() {
		if(clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
			throw new IllegalStateException("CLASSROOM_GITHUB_CLIENT_ID and CLASSROOM_GITHUB_CLIENT_SECRET are required for GitHub sign-in");
		}
	}

	private HttpResponse<byte[]> request(URI uri, String method, String token, Object payload, int... allowedStatuses) throws IOException {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json")
				.header("User-Agent", "finmath-classroom").header("X-GitHub-Api-Version", "2022-11-28");
		if(token != null) {
			try {
				request.header("Authorization", "Bearer " + token);
			} catch(IllegalArgumentException exception) {
				throw new IOException("Invalid GitHub credential format");
			}
		}
		if(payload == null) {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			boolean form = payload instanceof String;
			request.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
					.method(method, HttpRequest.BodyPublishers.ofString(form ? (String)payload : JsonFiles.MAPPER.writeValueAsString(payload)));
		}
		CompletableFuture<HttpResponse<byte[]>> response = http.sendAsync(request.build(), information -> new LimitedBody());
		try {
			HttpResponse<byte[]> result = response.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			for(int status : allowedStatuses) {
				if(result.statusCode() == status) {
					return result;
				}
			}
			throw new IOException("GitHub request failed (HTTP " + result.statusCode() + ")");
		} catch(InterruptedException exception) {
			response.cancel(true);
			Thread.currentThread().interrupt();
			throw new IOException("GitHub request was interrupted");
		} catch(ExecutionException | TimeoutException exception) {
			response.cancel(true);
			throw new IOException("GitHub request failed or timed out; check its state before retrying creation");
		}
	}

	private static JsonNode json(HttpResponse<byte[]> response) throws IOException {
		try {
			JsonNode value = JsonFiles.MAPPER.readTree(response.body());
			if(value != null && value.isObject()) {
				return value;
			}
		} catch(RuntimeException exception) {
			// Parser diagnostics can contain tokens or private GitHub response data.
		}
		throw new IOException("GitHub returned an invalid JSON response");
	}

	private static User readUser(JsonNode value) throws IOException {
		String login = value.path("login").asString("");
		if(!value.path("id").isIntegralNumber() || !value.path("id").canConvertToLong() || value.path("id").asLong() <= 0
				|| !login.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") || !"User".equals(value.path("type").asString(""))) {
			throw new IOException("GitHub returned an invalid student account");
		}
		return new User(value.path("id").asLong(), login);
	}

	private static Repository readRepository(JsonNode value, String expectedName) throws IOException {
		String fullName = value.path("full_name").asString("");
		String branch = value.path("default_branch").asString("");
		if(!value.path("id").isIntegralNumber() || !value.path("id").canConvertToLong() || value.path("id").asLong() <= 0
				|| !fullName.equalsIgnoreCase(expectedName) || !value.path("private").isBoolean() || !value.path("fork").isBoolean()
				|| !value.path("is_template").isBoolean() || branch.isBlank() || branch.length() > 255) {
			throw new IOException("GitHub returned an invalid or mismatched repository identity");
		}
		return new Repository(value.path("id").asLong(), fullName, value.path("private").asBoolean(), value.path("fork").asBoolean(), value.path("is_template").asBoolean(), branch);
	}

	private static String account(String login) {
		if(login == null || !login.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) {
			throw new IllegalArgumentException("Invalid GitHub account name");
		}
		return login;
	}

	private static String repositoryPath(String repository) {
		if(repository == null || !repository.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}") || repository.endsWith("/.") || repository.endsWith("/..")) {
			throw new IllegalArgumentException("Invalid GitHub repository name; expected owner/name");
		}
		return repository;
	}

	private static String form(Map<String, String> fields) {
		return fields.entrySet().stream().map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
	}

	/** Cancels an oversized response before an unbounded buffer can be allocated. */
	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
		private Flow.Subscription subscription;
		private int bytes;

		@Override
		public CompletionStage<byte[]> getBody() {
			return delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			for(ByteBuffer buffer : buffers) {
				if(buffer.remaining() > RESPONSE_LIMIT - bytes) {
					subscription.cancel();
					delegate.onError(new IOException("GitHub response is too large"));
					return;
				}
				bytes += buffer.remaining();
			}
			delegate.onNext(buffers);
		}

		@Override
		public void onError(Throwable throwable) {
			delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			delegate.onComplete();
		}
	}
}
