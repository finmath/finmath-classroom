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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.util.UriComponentsBuilder;

class MoodleLaunchTest {
	private static final String ISSUER = "https://moodle.example";
	private static final String PUBLIC_URL = "https://class.example";
	private static final String TARGET = "https://class.example/accept/lecture/task";
	private static final String SHARED_TARGET = "https://class.example/lti/entry";
	private static final String CUSTOM = MoodleLaunch.CLAIM + "custom";
	private static KeyPair key;
	private MoodleLaunch moodle;
	private MoodleLaunch.Login login;
	private Map<String, Object> claims;

	@BeforeAll
	static void createSigningKey() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		key = generator.generateKeyPair();
	}

	@BeforeEach
	void setup() {
		ClassroomConfig config = new ClassroomConfig(List.of(
				lecture("lecture", List.of(assignment("task", "course-1", "resource-1"),
						assignment("second", "course-1", "resource-2"), assignment("unmapped", null, null))),
				lecture("other", List.of(assignment("task", "course-2", "resource-3")))));
		moodle = configured(config);
		claims = new HashMap<>();
		claims.put("iss", ISSUER);
		claims.put("sub", "student-42");
		claims.put("aud", List.of("client"));
		claims.put("iat", Date.from(Instant.now()));
		claims.put("exp", Date.from(Instant.now().plusSeconds(60)));
		claims.put("name", "Alex Student");
		claims.put(MoodleLaunch.CLAIM + "deployment_id", "deployment");
		claims.put(MoodleLaunch.CLAIM + "version", "1.3.0");
		claims.put(MoodleLaunch.CLAIM + "message_type", "LtiResourceLinkRequest");
		claims.put(MoodleLaunch.CLAIM + "roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner"));
		claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", "course-1"));
		claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", "resource-1"));
		begin(false);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void testSignedLaunchPinsStudentAndRejectsReplay(boolean shared) throws Exception {
		begin(shared);
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

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void testUntrustedSignatureAndUnsolicitedLaunchFail(boolean shared) throws Exception {
		begin(shared);
		String target = shared ? SHARED_TARGET : TARGET;
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String forged = signed(generator.generateKeyPair());
		assertThrows(JwtException.class, () -> moodle.verify(login.state(), forged));
		assertThrows(IllegalArgumentException.class, () -> moodle.verify("unrequested", signed(key)));
		assertThrows(IllegalArgumentException.class, () -> moodle.login("https://evil.example", "client", "hint", null, target));
		assertThrows(IllegalArgumentException.class, () -> moodle.login(ISSUER, "another-client", "hint", null, target));
		assertThrows(IllegalArgumentException.class, () -> moodle.login(ISSUER, "client", "hint", null, "https://evil.example/accept/lecture/task"));
	}

	@ParameterizedTest
	@MethodSource("invalidClaimsForTargets")
	void testSecurityAndAssignmentClaimsAreChecked(boolean shared, Consumer<Map<String, Object>> mutation) throws Exception {
		begin(shared);
		mutation.accept(claims);
		String token = signed(key);
		assertThrows(RuntimeException.class, () -> moodle.verify(login.state(), token));
		assertThrows(IllegalArgumentException.class, () -> moodle.complete(login.state()));
	}

	@ParameterizedTest
	@CsvSource({"lecture,task,course-1,resource-1", "lecture,second,course-1,resource-2", "other,task,course-2,resource-3",
			"lecture,unmapped,new-course,new-activity", "lecture,task,another-course,another-activity"})
	void testSharedTargetUsesSignedSelectorsForAnyConfiguredAssignment(String lectureId, String assignmentId, String courseId, String resourceId) throws Exception {
		begin(true);
		Map<String, Object> custom = selectors(lectureId, assignmentId);
		custom.put("unrelated", "preserved Moodle custom parameter");
		claims.put(CUSTOM, custom);
		claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", courseId));
		claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", resourceId));
		moodle.verify(login.state(), signed(key));
		MoodleLaunch.Launch result = moodle.complete(login.state());
		assertEquals(lectureId, result.lectureId());
		assertEquals(assignmentId, result.assignmentId());
	}

	@ParameterizedTest
	@CsvSource({"false,course-1,resource-1", "false,new-course,new-activity", "true,course-1,resource-1", "true,new-course,new-activity"})
	void testSignedSelectorsNeedNoServerMappingAndAllowDifferentMoodleActivities(boolean shared, String courseId, String resourceId) throws Exception {
		moodle = configured(new ClassroomConfig(List.of(lecture("lecture", List.of(assignment("task", null, null))))));
		begin(shared);
		claims.put(CUSTOM, selectors("lecture", "task"));
		claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", courseId));
		claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", resourceId));
		moodle.verify(login.state(), signed(key));
		MoodleLaunch.Launch result = moodle.complete(login.state());
		assertEquals("lecture", result.lectureId());
		assertEquals("task", result.assignmentId());
		assertEquals("Alex Student", result.identity().name());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidSharedSelectors")
	void testSharedTargetRequiresValidSignedSelectors(String description, Map<String, Object> custom) throws Exception {
		begin(true);
		if(custom == null) {
			claims.remove(CUSTOM);
		} else {
			claims.put(CUSTOM, custom);
		}
		assertRejected();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidDirectSelectors")
	void testDirectTargetRejectsPartialOrConflictingSelectors(String description, Map<String, Object> custom) throws Exception {
		claims.put(CUSTOM, custom);
		assertRejected();
	}

	@ParameterizedTest
	@MethodSource("validDirectCustomParameters")
	void testDirectTargetAllowsMatchingOrUnrelatedCustomParameters(Map<String, Object> custom) throws Exception {
		claims.put(CUSTOM, custom);
		moodle.verify(login.state(), signed(key));
		assertEquals("task", moodle.complete(login.state()).assignmentId());
	}

	@ParameterizedTest
	@MethodSource("malformedCustomClaimsForTargets")
	void testCustomClaimMustBeAMap(boolean shared, Object custom) throws Exception {
		begin(shared);
		claims.put(CUSTOM, custom);
		assertRejected();
	}

	@ParameterizedTest
	@CsvSource({"course-1,resource-2", "course-2,resource-3", "course-2,resource-1", "course-1,resource-3"})
	void testLegacyDirectTargetRequiresItsConfiguredCourseAndActivity(String courseId, String resourceId) throws Exception {
		claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", courseId));
		claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", resourceId));
		assertRejected();
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://class.example/lti/entry/", "https://class.example/lti/entry?classroom_lecture=lecture&classroom_assignment=task",
			"https://class.example/lti/entry#activity", "http://class.example/lti/entry", "https://evil.example/lti/entry",
			"https://class.example/lti/%65ntry", "/lti/entry"})
	void testSharedLoginTargetMustMatchExactly(String target) {
		assertThrows(IllegalArgumentException.class, () -> moodle.login(ISSUER, "client", "hint", null, target));
	}

	@ParameterizedTest
	@MethodSource("incompleteMappings")
	void testLegacyDirectLaunchWithoutSelectorsRequiresACompleteConfiguredMapping(String courseId, String resourceId) throws Exception {
		moodle = configured(new ClassroomConfig(List.of(lecture("lecture", List.of(assignment("task", courseId, resourceId))))));
		begin(false);
		assertRejected();
	}

	@Test
	void testLoginDoesNotRequireMappingsOrDecodeTokens() {
		ClassroomConfig config = new ClassroomConfig(List.of(lecture("lecture", List.of(assignment("task", null, null)))));
		MoodleLaunch unmapped = new MoodleLaunch(config, PUBLIC_URL, ISSUER, "client", "deployment", ISSUER + "/auth", token -> {
			throw new AssertionError("Login must not decode tokens");
		});
		assertEquals("/auth", unmapped.login(ISSUER, "client", "hint", null, SHARED_TARGET).authorizationUrl().getPath());
		assertEquals("/auth", unmapped.login(ISSUER, "client", "hint", null, TARGET).authorizationUrl().getPath());
	}

	static Stream<Arguments> invalidSharedSelectors() {
		return Stream.concat(Stream.of(Arguments.of("missing custom claim", null), Arguments.of("empty custom map", Map.of()),
				Arguments.of("unrelated custom parameters only", Map.of("unrelated", "value"))), invalidSelectors());
	}

	static Stream<Arguments> invalidSelectors() {
		return Stream.of(
				Arguments.of("missing lecture selector", Map.of("classroom_assignment", "task")),
				Arguments.of("missing assignment selector", Map.of("classroom_lecture", "lecture")),
				Arguments.of("null lecture selector", selectors(null, "task")),
				Arguments.of("null assignment selector", selectors("lecture", null)),
				Arguments.of("numeric lecture selector", selectors(123, "task")),
				Arguments.of("numeric assignment selector", selectors("lecture", 123)),
				Arguments.of("array selector", selectors("lecture", List.of("task"))),
				Arguments.of("object selector", selectors("lecture", Map.of("id", "task"))),
				Arguments.of("empty lecture selector", selectors("", "task")),
				Arguments.of("blank assignment selector", selectors("lecture", " ")),
				Arguments.of("padded lecture selector", selectors(" lecture", "task")),
				Arguments.of("padded assignment selector", selectors("lecture", "task ")),
				Arguments.of("unknown lecture", selectors("unknown", "task")),
				Arguments.of("unknown assignment", selectors("lecture", "unknown")));
	}

	static Stream<Arguments> invalidDirectSelectors() {
		return Stream.concat(invalidSelectors(), Stream.of(
				Arguments.of("different assignment without Moodle mapping", selectors("lecture", "unmapped")),
				Arguments.of("different assignment in the same lecture", selectors("lecture", "second")),
				Arguments.of("same assignment ID in another lecture", selectors("other", "task"))));
	}

	static Stream<Map<String, Object>> validDirectCustomParameters() {
		Map<String, Object> matching = selectors("lecture", "task");
		matching.put("unrelated", "value");
		return Stream.of(Map.of("unrelated", "value"), matching);
	}

	static Stream<Arguments> malformedCustomClaimsForTargets() {
		return Stream.of(false, true).flatMap(shared -> Stream.of(
				"not a map", 123, List.of("lecture", "task"), "{\"classroom_lecture\":\"lecture\",\"classroom_assignment\":\"task\"}")
				.map(custom -> Arguments.of(shared, custom)));
	}

	static Stream<Arguments> incompleteMappings() {
		return Stream.of(Arguments.of(null, null), Arguments.of("course-1", null), Arguments.of(null, "resource-1"));
	}

	static Stream<Arguments> invalidClaimsForTargets() {
		return Stream.of(false, true).flatMap(shared -> invalidClaims().map(mutation -> Arguments.of(shared, mutation)));
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
				claims -> claims.remove(MoodleLaunch.CLAIM + "context"),
				claims -> claims.remove(MoodleLaunch.CLAIM + "resource_link"),
				claims -> claims.put(MoodleLaunch.CLAIM + "context", "not a map"),
				claims -> claims.put(MoodleLaunch.CLAIM + "resource_link", List.of("resource-1")),
				claims -> claims.put(MoodleLaunch.CLAIM + "context", Map.of()),
				claims -> claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of()),
				claims -> claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", " ")),
				claims -> claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", "")),
				claims -> claims.put(MoodleLaunch.CLAIM + "context", Map.of("id", 123)),
				claims -> claims.put(MoodleLaunch.CLAIM + "resource_link", Map.of("id", 456)));
	}

	private void begin(boolean shared) {
		String target = shared ? SHARED_TARGET : TARGET;
		login = moodle.login(ISSUER, "client", "hint", "message", target);
		String nonce = UriComponentsBuilder.fromUri(login.authorizationUrl()).build().getQueryParams().getFirst("nonce");
		claims.put("nonce", nonce);
		claims.put(MoodleLaunch.CLAIM + "target_link_uri", target);
		if(shared) {
			claims.put(CUSTOM, selectors("lecture", "task"));
		} else {
			claims.remove(CUSTOM);
		}
	}

	private void assertRejected() throws Exception {
		String token = signed(key);
		assertThrows(IllegalArgumentException.class, () -> moodle.verify(login.state(), token));
		assertThrows(IllegalArgumentException.class, () -> moodle.complete(login.state()));
	}

	private static Map<String, Object> selectors(Object lecture, Object assignment) {
		Map<String, Object> custom = new HashMap<>();
		custom.put("classroom_lecture", lecture);
		custom.put("classroom_assignment", assignment);
		return custom;
	}

	private static Lecture lecture(String id, List<Assignment> assignments) {
		return new Lecture(id, "Lecture", "school", "staff", "lecture-key", List.of(), assignments);
	}

	private static Assignment assignment(String id, String courseId, String resourceId) {
		return new Assignment(id, "Task", "school/template", "prefix-" + id, "main", null, "assignment-key", courseId, resourceId);
	}

	private static MoodleLaunch configured(ClassroomConfig config) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return new MoodleLaunch(config, PUBLIC_URL, ISSUER, "client", "deployment", ISSUER + "/auth", decoder);
	}

	private String signed(KeyPair signingKey) throws Exception {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		claims.forEach(builder::claim);
		SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		token.sign(new RSASSASigner((RSAPrivateKey) signingKey.getPrivate()));
		return token.serialize();
	}
}
