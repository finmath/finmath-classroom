package org.finmath.classroom.registration;

import java.io.IOException;

/** GitHub identity and repository operations; implementations can be replaced with offline fixtures. */
public interface GitHubGateway {

	/** An account's stable numeric identity and current login. */
	record User(long id, String login) { }

	/** Repository identity and the properties needed to check ownership and template copies. */
	record Repository(long id, String fullName, boolean privateRepository, boolean fork, boolean template, String defaultBranch) {
		/** Returns the browser URL without trusting a URL supplied by an API response. */
		public String url() {
			return "https://github.com/" + fullName;
		}
	}

	/** Resolves a login to its current numeric account identity. */
	User user(String login) throws IOException;

	/** Reads a repository by owner/name. */
	Repository repository(String fullName) throws IOException;

	/** Creates a private independent copy of the template's default branch. Never retries creation. */
	Repository create(String template, String organization, String name) throws IOException;

	/** Grants the organization's staff team maintain access to the repository. */
	void grantStaff(String organization, String team, String repository) throws IOException;

	/** Grants student write access; returns true for a pending invitation, false for existing access. */
	boolean invite(String repository, String login) throws IOException;

	/** Exchanges the OAuth code, reads the authenticated identity and discards the student token. */
	User authenticate(String code, String redirectUri, String codeVerifier) throws IOException;

	/** Builds a GitHub authorization URL; the caller stores and verifies state and the PKCE verifier. */
	String authorizationUrl(String redirectUri, String state, String codeChallenge);
}
