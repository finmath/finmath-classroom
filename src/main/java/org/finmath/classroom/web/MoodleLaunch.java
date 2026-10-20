package org.finmath.classroom.web;

import java.net.URI;
import java.time.Instant;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jwt.JWTParser;

import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.util.UriComponentsBuilder;

/** Optional LTI 1.3 resource launches from one explicitly trusted Moodle deployment. */
public final class MoodleLaunch {
	static final String CLAIM = "https://purl.imsglobal.org/spec/lti/claim/";
	static final String ENTRY_PATH = "/lti/entry";
	private final ClassroomConfig config;
	private final String publicUrl;
	private final String issuer;
	private final String clientId;
	private final String deploymentId;
	private final String authUrl;
	private final JwtDecoder decoder;
	private final Map<String, Pending> pending = new HashMap<>();

	/** A verified identity is scoped to the assignment selected by the signed Moodle claims. */
	public record Launch(String lectureId, String assignmentId, Identity identity) { }

	/** The browser retains state in its session until the same-site completion request. */
	public record Login(String state, URI authorizationUrl) { }

	private record Pending(String nonce, String target, Instant expires, Launch launch) { }

	public MoodleLaunch(ClassroomConfig config, String publicUrl, String issuer, String clientId,
			String deploymentId, String authUrl, JwtDecoder decoder) {
		this.config = config;
		this.publicUrl = publicUrl;
		this.issuer = issuer;
		this.clientId = clientId;
		this.deploymentId = deploymentId;
		this.authUrl = authUrl;
		this.decoder = decoder;
	}

	/** Begin OIDC login for the shared tool entry or a configured direct assignment target. */
	public synchronized Login login(String platformIssuer, String platformClientId, String loginHint, String messageHint, String target) {
		if(!issuer.equals(platformIssuer) || (platformClientId != null && !clientId.equals(platformClientId)) || loginHint == null || loginHint.isBlank()) {
			throw new IllegalArgumentException("Unrecognized Moodle login");
		}
		boolean sharedTarget = (publicUrl + ENTRY_PATH).equals(target);
		boolean knownTarget = config.lectures().stream().anyMatch(lecture -> lecture.assignments().stream()
				.anyMatch(assignment -> sharedTarget || target(lecture.id(), assignment.id()).equals(target)));
		if(!knownTarget) {
			throw new IllegalArgumentException("Moodle target is not configured for an assignment");
		}
		pending.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
		if(pending.size() >= 1000) {
			throw new IllegalArgumentException("Too many pending Moodle logins; try again shortly");
		}
		String state = UUID.randomUUID().toString();
		String nonce = UUID.randomUUID().toString();
		pending.put(state, new Pending(nonce, target, Instant.now().plusSeconds(300), null));
		UriComponentsBuilder url = UriComponentsBuilder.fromUriString(authUrl)
				.queryParam("scope", "openid").queryParam("response_type", "id_token")
				.queryParam("response_mode", "form_post").queryParam("prompt", "none")
				.queryParam("client_id", clientId).queryParam("redirect_uri", publicUrl + "/lti/launch")
				.queryParam("login_hint", loginHint).queryParam("nonce", nonce).queryParam("state", state);
		if(messageHint != null) {
			url.queryParam("lti_message_hint", messageHint);
		}
		return new Login(state, url.build().encode().toUri());
	}

	/** Validate signature and claims before recording a launch, consuming its nonce once. */
	public void verify(String state, String token) {
		Pending login;
		synchronized(this) {
			login = pending.get(state);
		}
		if(login == null || login.launch() != null || !login.expires().isAfter(Instant.now()) || token.length() > 65536) {
			throw new IllegalArgumentException("Expired or invalid Moodle login; open the activity again");
		}
		Jwt jwt = decoder.decode(token);
		// Spring supplies a default iat for tokens that omit it; LTI requires the actual claim.
		try {
			if(JWTParser.parse(token).getJWTClaimsSet().getIssueTime() == null) {
				throw new IllegalArgumentException("Moodle launch must include its issue time");
			}
		} catch(ParseException exception) {
			throw new IllegalArgumentException("Invalid Moodle launch", exception);
		}
		Instant now = Instant.now();
		String authorizedParty = jwt.getClaimAsString("azp");
		if(!issuer.equals(jwt.getClaimAsString("iss")) || jwt.getAudience() == null || !jwt.getAudience().contains(clientId)
				|| (jwt.getAudience().size() > 1 && !clientId.equals(authorizedParty))
				|| (authorizedParty != null && !clientId.equals(authorizedParty))
				|| jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
				|| jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now.plusSeconds(60))
				|| !login.nonce().equals(jwt.getClaimAsString("nonce"))
				|| !deploymentId.equals(jwt.getClaimAsString(CLAIM + "deployment_id"))
				|| !"LtiResourceLinkRequest".equals(jwt.getClaimAsString(CLAIM + "message_type"))
				|| !"1.3.0".equals(jwt.getClaimAsString(CLAIM + "version"))
				|| !login.target().equals(jwt.getClaimAsString(CLAIM + "target_link_uri"))
				|| jwt.getSubject() == null || jwt.getSubject().isBlank()) {
			throw new IllegalArgumentException("Invalid Moodle launch claims");
		}
		List<String> roles = jwt.getClaimAsStringList(CLAIM + "roles");
		if(roles == null || !roles.contains("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner")) {
			throw new IllegalArgumentException("Open this activity as a student to accept the assignment");
		}
		String courseId = claimId(jwt, "context");
		String resourceId = claimId(jwt, "resource_link");
		boolean sharedTarget = (publicUrl + ENTRY_PATH).equals(login.target());
		Object rawCustom = jwt.getClaims().get(CLAIM + "custom");
		if(jwt.getClaims().containsKey(CLAIM + "custom") && !(rawCustom instanceof Map<?, ?>)) {
			throw new IllegalArgumentException("Invalid Moodle custom parameters.");
		}
		Map<?, ?> custom = rawCustom instanceof Map<?, ?> values ? values : Map.of();
		boolean hasSelector = custom.containsKey("classroom_lecture") || custom.containsKey("classroom_assignment");
		Object selectedLecture = custom.get("classroom_lecture");
		Object selectedAssignment = custom.get("classroom_assignment");
		boolean selectByCustom = sharedTarget || hasSelector;
		if(selectByCustom && (!(selectedLecture instanceof String lectureId) || lectureId.isBlank()
				|| !(selectedAssignment instanceof String assignmentId) || assignmentId.isBlank())) {
			throw new IllegalArgumentException("Moodle launch must supply classroom_lecture and classroom_assignment as custom parameters.");
		}
		Launch launch = null;
		for(Lecture lecture : config.lectures()) {
			for(Assignment assignment : lecture.assignments()) {
				boolean targetMatches = sharedTarget || target(lecture.id(), assignment.id()).equals(login.target());
				boolean selectorMatches = !selectByCustom
						|| lecture.id().equals(selectedLecture) && assignment.id().equals(selectedAssignment);
				// Signed selectors authorize routing within the trusted deployment. Frozen
				// activity mappings remain supported for older direct launches without selectors.
				boolean legacyMappingMatches = assignment.moodleCourseId() != null && assignment.moodleCourseId().equals(courseId)
						&& resourceId.equals(assignment.moodleResourceId());
				if(targetMatches && selectorMatches && (selectByCustom || legacyMappingMatches)) {
					String name = jwt.getClaimAsString("name");
					launch = new Launch(lecture.id(), assignment.id(), new Identity("moodle:" + issuer, jwt.getSubject(), name));
				}
			}
		}
		if(launch == null) {
			throw new IllegalArgumentException(selectByCustom ? "Moodle custom parameters do not select a configured assignment"
					: "Moodle course or activity does not match this assignment");
		}
		synchronized(this) {
			if(!pending.replace(state, login, new Pending(login.nonce(), login.target(), login.expires(), launch))) {
				throw new IllegalArgumentException("Moodle launch has already been used");
			}
		}
	}

	/** Called only after the controller matches state against the original browser session. */
	public synchronized Launch complete(String state) {
		Pending login = pending.remove(state);
		if(login == null || login.launch() == null || !login.expires().isAfter(Instant.now())) {
			throw new IllegalArgumentException("Expired or invalid Moodle login; open the activity again");
		}
		return login.launch();
	}

	private String target(String lectureId, String assignmentId) {
		return publicUrl + "/accept/" + lectureId + "/" + assignmentId;
	}

	private static String claimId(Jwt jwt, String claim) {
		Object value = jwt.getClaims().get(CLAIM + claim);
		if(!(value instanceof Map<?, ?> fields) || !(fields.get("id") instanceof String id) || id.isBlank()) {
			throw new IllegalArgumentException("Moodle launch must include a valid " + claim + " id");
		}
		return id;
	}
}
