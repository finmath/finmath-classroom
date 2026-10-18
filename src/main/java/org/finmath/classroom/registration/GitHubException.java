package org.finmath.classroom.registration;

import java.io.IOException;
import java.util.Objects;

/** GitHub failure diagnostics containing only fixed labels and an optional HTTP status. */
public final class GitHubException extends IOException {

	private static final long serialVersionUID = 1L;

	public enum Operation {
		ACCOUNT_LOOKUP, REPOSITORY_LOOKUP, CREATE_REPOSITORY, GRANT_STAFF, INVITE_STUDENT,
		OAUTH_TOKEN_EXCHANGE, OAUTH_USER_LOOKUP
	}

	public enum Category {
		HTTP_STATUS, TRANSPORT, TIMEOUT, INTERRUPTED, INVALID_JSON, INVALID_RESPONSE,
		CREDENTIAL_FORMAT, AUTHENTICATION_REJECTED
	}

	private final Operation operation;
	private final Category category;
	private final Integer httpStatus;

	public GitHubException(Operation operation, Category category) {
		this(operation, category, null);
	}

	public GitHubException(Operation operation, Category category, Integer httpStatus) {
		super("GitHub " + Objects.requireNonNull(operation) + " failed: " + Objects.requireNonNull(category)
				+ (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"));
		this.operation = operation;
		this.category = category;
		this.httpStatus = httpStatus;
	}

	public Operation operation() { return operation; }
	public Category category() { return category; }
	public Integer httpStatus() { return httpStatus; }
}
