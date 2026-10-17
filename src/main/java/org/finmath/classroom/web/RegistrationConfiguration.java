package org.finmath.classroom.web;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.EnrollmentStore;
import org.finmath.classroom.registration.GitHubClient;
import org.finmath.classroom.registration.GitHubGateway;
import org.finmath.classroom.registration.RegistrationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Registration is enabled only with an explicitly configured private data directory. */
@Configuration(proxyBeanMethods = false)
@Conditional(RegistrationConfiguration.Enabled.class)
public class RegistrationConfiguration {
	@Bean
	ServletContextInitializer sessionCookies(Environment environment) {
		boolean secure = publicUrl(environment.getRequiredProperty("classroom.public-url")).startsWith("https://");
		return context -> context.getSessionCookieConfig().setSecure(secure);
	}

	@Bean
	ClassroomConfig classroomConfig(Environment environment) throws IOException {
		publicUrl(environment.getRequiredProperty("classroom.public-url"));
		return ClassroomConfig.load(Path.of(environment.getRequiredProperty("classroom.data")));
	}

	@Bean
	EnrollmentStore enrollmentStore(Environment environment, ClassroomConfig config) throws IOException {
		EnrollmentStore store = new EnrollmentStore(Path.of(environment.getRequiredProperty("classroom.data")).resolve("classroom.db"), false);
		store.verifyConfiguration(config);
		return store;
	}

	@Bean
	GitHubGateway githubGateway(Environment environment) {
		return new GitHubClient(required(environment, "classroom.github-token"),
				required(environment, "classroom.github-client-id"), required(environment, "classroom.github-client-secret"));
	}

	@Bean
	RegistrationService registrationService(ClassroomConfig config, EnrollmentStore store, GitHubGateway github) {
		return new RegistrationService(config, store, github);
	}

	@Bean
	@Conditional(MoodleEnabled.class)
	MoodleLaunch moodleLaunch(Environment environment, ClassroomConfig config) {
		String issuer = required(environment, "classroom.moodle.issuer");
		String publicUrl = publicUrl(required(environment, "classroom.public-url"));
		if(!publicUrl.startsWith("https://")) {
			throw new IllegalArgumentException("Moodle launches require an HTTPS public URL");
		}
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(required(environment, "classroom.moodle.jwks-url")).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
		return new MoodleLaunch(config, publicUrl, issuer, required(environment, "classroom.moodle.client-id"),
				required(environment, "classroom.moodle.deployment-id"), required(environment, "classroom.moodle.auth-url"), decoder);
	}

	static String publicUrl(String value) {
		URI uri = URI.create(value);
		boolean loopback = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
		if(uri.getHost() == null || !("https".equals(uri.getScheme()) || loopback) || uri.getUserInfo() != null
				|| uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().matches("/?")) {
			throw new IllegalArgumentException("CLASSROOM_PUBLIC_URL must be an HTTPS origin (HTTP loopback is allowed for development)");
		}
		return value.replaceAll("/$", "");
	}

	private static String required(Environment environment, String property) {
		String value = environment.getProperty(property, "");
		if(value.isBlank()) {
			throw new IllegalArgumentException("Missing configuration: " + property);
		}
		return value;
	}

	static class Enabled implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return !context.getEnvironment().getProperty("classroom.data", "").isBlank();
		}
	}

	static class MoodleEnabled implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return !context.getEnvironment().getProperty("classroom.moodle.issuer", "").isBlank();
		}
	}
}
