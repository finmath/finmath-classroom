package org.finmath.classroom.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.util.UriComponentsBuilder;

class MoodleLaunchTest {
	private static final String ISSUER = "https://moodle.example";
	private static final String TARGET = "https://class.example/accept/lecture/task";
	private MoodleLaunch moodle;
	private KeyPair key;
	private MoodleLaunch.Login login;
	private Map<String, Object> claims;

	@BeforeEach
	void setup() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		key = generator.generateKeyPair();
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		ClassroomConfig config = new ClassroomConfig(List.of(new Lecture("lecture", "Lecture", "school", "staff", "lecture-key", List.of(),
				List.of(new Assignment("task", "Task", "school/template", "prefix", "main", null, "assignment-key", "course-1", "resource-1")))));
		moodle = new MoodleLaunch(config, "https://class.example", ISSUER, "client", "deployment", ISSUER + "/auth", decoder);
		login = moodle.login(ISSUER, "client", "hint", "message", TARGET);
		String nonce = UriComponentsBuilder.fromUri(login.authorizationUrl()).build().getQueryParams().getFirst("nonce");
		claims = new HashMap<>();
		claims.put("iss", ISSUER);
		claims.put("sub", "student-42");
		claims.put("aud", List.of("client"));
		claims.put("iat", Date.from(Instant.now()));
		claims.put("exp", Date.from(Instant.now().plusSeconds(60)));
		claims.put("nonce", nonce);
		claims.put("name", "Alex Student");
		claims.put(MoodleLaunch.CLAIM + "deployment_id", "deployment");
		claims.put(MoodleLaunch.CLAIM + "version", "1.3.0");
		claims.put(MoodleLaunch.CLAIM + "message_type", "LtiResourceLinkRequest");
		claims.put(MoodleLaunch.CLAIM + "target_link_uri", TARGET);
		claims.put(MoodleLaunch.CLAIM + "roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner"));
		claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", "course-1"));
		claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", "resource-1"));
	}

	@Test
	void testSignedLaunchPinsStudentAndRejectsReplay() throws Exception {
		String token = signed(key);
		moodle.verify(login.state(), token);
		assertThrows(IllegalArgumentException.class, () -> moodle.verify(login.state(), token));
		MoodleLaunch.Launch result = moodle.complete(login.state());
		assertEquals("lecture", result.lectureId());
		assertEquals("task", result.assignmentId());
		assertEquals("moodle:" + ISSUER, result.identity().source());
		assertEquals("student-42", result.identity().subject());
		assertEquals("Alex Student", result.identity().name());
		assertThrows(IllegalArgumentException.class, () -> moodle.complete(login.state()));
		assertThrows(IllegalArgumentException.class, () -> moodle.verify(login.state(), token));
	}

	@Test
	void testUntrustedSignatureAndUnsolicitedLaunchFail() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String forged = signed(generator.generateKeyPair());
		assertThrows(JwtException.class, () -> moodle.verify(login.state(), forged));
		assertThrows(IllegalArgumentException.class, () -> moodle.verify("unrequested", signed(key)));
		assertThrows(IllegalArgumentException.class, () -> moodle.login("https://evil.example", "client", "hint", null, TARGET));
		assertThrows(IllegalArgumentException.class, () -> moodle.login(ISSUER, "another-client", "hint", null, TARGET));
		assertThrows(IllegalArgumentException.class, () -> moodle.login(ISSUER, "client", "hint", null, "https://evil.example/accept/lecture/task"));
	}

	@ParameterizedTest
	@MethodSource("invalidClaims")
	void testSecurityAndAssignmentClaimsAreChecked(Consumer<Map<String, Object>> mutation) throws Exception {
		mutation.accept(claims);
		String token = signed(key);
		assertThrows(RuntimeException.class, () -> moodle.verify(login.state(), token));
		assertThrows(IllegalArgumentException.class, () -> moodle.complete(login.state()));
	}

	static Stream<Consumer<Map<String, Object>>> invalidClaims() {
		return Stream.of(
				claims -> claims.put("iss", "https://wrong.example"),
				claims -> claims.put("aud", List.of("wrong-client")),
				claims -> claims.remove("aud"),
				claims -> claims.put("aud", List.of("client", "other")),
				claims -> claims.put("azp", "wrong-client"),
				claims -> claims.put("nonce", "wrong-nonce"),
				claims -> claims.put("exp", Date.from(Instant.now().minusSeconds(120))),
				claims -> claims.remove("exp"),
				claims -> claims.remove("iat"),
				claims -> claims.put("iat", Date.from(Instant.now().plusSeconds(120))),
				claims -> claims.remove("sub"),
				claims -> claims.put(MoodleLaunch.CLAIM + "deployment_id", "other"),
				claims -> claims.put(MoodleLaunch.CLAIM + "version", "1.1.0"),
				claims -> claims.put(MoodleLaunch.CLAIM + "message_type", "LtiDeepLinkingRequest"),
				claims -> claims.put(MoodleLaunch.CLAIM + "target_link_uri", "https://class.example/accept/lecture/other"),
				claims -> claims.put(MoodleLaunch.CLAIM + "roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#Instructor")),
				claims -> claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", "another-course")),
				claims -> claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", "another-activity")));
	}

	private String signed(KeyPair signingKey) throws Exception {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		claims.forEach(builder::claim);
		SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		token.sign(new RSASSASigner((RSAPrivateKey) signingKey.getPrivate()));
		return token.serialize();
	}
}
