package org.finmath.classroom.web;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubGateway;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.registration.RegistrationService;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/** Small server-rendered acceptance flow; only an authenticated, confirmed POST provisions a repository. */
@RestController
@Conditional(RegistrationConfiguration.Enabled.class)
public class RegistrationController {
	private final RegistrationService registration;
	private final GitHubGateway github;
	private final String publicUrl;
	private final Optional<MoodleLaunch> moodle;
	private final SecureRandom random = new SecureRandom();
	private record OAuth(String state, String verifier, String path, Instant expires) { }

	public RegistrationController(RegistrationService registration, GitHubGateway github,
			@Value("${classroom.public-url}") String publicUrl, Optional<MoodleLaunch> moodle) {
		this.registration = registration;
		this.github = github;
		this.publicUrl = RegistrationConfiguration.publicUrl(publicUrl);
		this.moodle = moodle;
	}

	@GetMapping("/accept/{lectureId}/{assignmentId}")
	public ResponseEntity<String> assignment(@PathVariable String lectureId, @PathVariable String assignmentId, HttpSession session) throws IOException {
		Lecture lecture = registration.config().lecture(lectureId);
		Assignment assignment = registration.config().assignment(lectureId, assignmentId);
		MoodleLaunch.Launch launch = launch(session, lectureId, assignmentId);
		String title = escape(lecture.title()) + ": " + escape(assignment.title());
		if(launch == null && (lecture.registrationKey() == null || assignment.registrationKey() == null)) {
			return page(HttpStatus.FORBIDDEN, title, "<p>Open this assignment through its Moodle External tool activity.</p>");
		}
		String csrf = csrf(session);
		String path = "/accept/" + lectureId + "/" + assignmentId;
		StringBuilder body = new StringBuilder();
		User user = (User) session.getAttribute("github");
		if(user == null) {
			body.append("<p>Connect your GitHub account, then confirm your details to accept this assignment.</p>")
					.append("<form method='post' action='/auth/github/start'>").append(hidden("csrf", csrf))
					.append(hidden("lectureId", lectureId)).append(hidden("assignmentId", assignmentId))
					.append("<button>Connect GitHub account</button></form>");
		} else {
			Enrollment enrollment = registration.enrollment(lectureId, assignmentId, user);
			if(enrollment != null) {
				body.append("<p>Your acceptance is recorded.</p>");
				if(enrollment.repositoryId() != null) {
					body.append("<p><a href='").append(escape(enrollment.repositoryUrl())).append("'>Open your repository</a></p>");
				}
				if("invitation_pending".equals(enrollment.status())) {
					body.append("<p>Accept the GitHub repository invitation to access your assignment. Check your GitHub notifications or email.</p>");
				}
				if(enrollment.problem() != null) {
					body.append("<p>").append(escape(enrollment.problem())).append("</p>");
				}
			}
			body.append("<p>Connected GitHub account: <strong>").append(escape(user.login())).append("</strong></p>")
					.append("<form method='post' action='").append(path).append("'>").append(hidden("csrf", csrf));
			String name = launch == null ? null : launch.identity().name();
			if(name != null && !name.isBlank()) {
				body.append("<p>Name supplied by Moodle: <strong>").append(escape(name)).append("</strong></p>");
			} else {
				body.append("<label>Your full name (self-declared)<input name='name' autocomplete='name' maxlength='200' required></label>");
			}
			if(launch == null) {
				body.append("<label>Lecture registration key<input type='password' name='lectureKey' required></label>")
						.append("<label>Assignment registration key<input type='password' name='assignmentKey' required></label>");
			}
			body.append("<label><input type='checkbox' name='declaration' value='true' required> ")
					.append(escape(RegistrationService.DECLARATION)).append("</label><button>Accept assignment</button></form>")
					.append("<form method='post' action='/auth/github/logout'>").append(hidden("csrf", csrf))
					.append(hidden("lectureId", lectureId)).append(hidden("assignmentId", assignmentId))
					.append("<button>Disconnect GitHub account</button></form>");
		}
		return page(HttpStatus.OK, title, body.toString());
	}

	@PostMapping("/auth/github/start")
	public ResponseEntity<Void> githubStart(@RequestParam String lectureId, @RequestParam String assignmentId,
			@RequestParam String csrf, HttpSession session) {
		checkCsrf(session, csrf);
		registration.config().assignment(lectureId, assignmentId);
		String state = token();
		String verifier = token();
		session.setAttribute("oauth", new OAuth(state, verifier, "/accept/" + lectureId + "/" + assignmentId, Instant.now().plusSeconds(600)));
		try {
			String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			return redirect(URI.create(github.authorizationUrl(publicUrl + "/auth/github/callback", state, challenge)));
		} catch(NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@GetMapping("/auth/github/callback")
	public ResponseEntity<Void> githubCallback(@RequestParam String state, @RequestParam(required = false) String code,
			HttpServletRequest request) throws IOException {
		HttpSession session = request.getSession(false);
		OAuth oauth = session == null ? null : (OAuth) session.getAttribute("oauth");
		if(oauth == null || !oauth.state().equals(state) || !oauth.expires().isAfter(Instant.now()) || code == null || code.isBlank()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired GitHub login");
		}
		session.removeAttribute("oauth");
		User user = github.authenticate(code, publicUrl + "/auth/github/callback", oauth.verifier());
		request.changeSessionId();
		session.setAttribute("github", user);
		return redirect(URI.create(oauth.path()));
	}

	@PostMapping("/auth/github/logout")
	public ResponseEntity<Void> githubLogout(@RequestParam String lectureId, @RequestParam String assignmentId,
			@RequestParam String csrf, HttpSession session) {
		checkCsrf(session, csrf);
		registration.config().assignment(lectureId, assignmentId);
		session.removeAttribute("github");
		session.removeAttribute("oauth");
		return redirect(URI.create("/accept/" + lectureId + "/" + assignmentId));
	}

	@PostMapping("/accept/{lectureId}/{assignmentId}")
	public ResponseEntity<Void> accept(@PathVariable String lectureId, @PathVariable String assignmentId,
			@RequestParam String csrf, @RequestParam(defaultValue = "") String name,
			@RequestParam(defaultValue = "") String lectureKey, @RequestParam(defaultValue = "") String assignmentKey,
			@RequestParam(defaultValue = "false") boolean declaration, HttpSession session) throws IOException {
		checkCsrf(session, csrf);
		User user = (User) session.getAttribute("github");
		if(user == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connect your GitHub account first");
		}
		MoodleLaunch.Launch launch = launch(session, lectureId, assignmentId);
		Identity identity = launch == null ? new Identity("self", Long.toString(user.id()), name)
				: new Identity(launch.identity().source(), launch.identity().subject(),
						launch.identity().name() == null || launch.identity().name().isBlank() ? name : launch.identity().name());
		registration.accept(lectureId, assignmentId, identity, user, lectureKey, assignmentKey, declaration);
		return redirect(URI.create("/accept/" + lectureId + "/" + assignmentId));
	}

	@RequestMapping(value = "/lti/login", method = { RequestMethod.GET, RequestMethod.POST })
	public ResponseEntity<Void> moodleLogin(@RequestParam String iss, @RequestParam(name = "client_id", required = false) String clientId,
			@RequestParam(name = "login_hint") String loginHint, @RequestParam(name = "lti_message_hint", required = false) String messageHint,
			@RequestParam(name = "target_link_uri") String target, HttpSession session) {
		MoodleLaunch.Login login = moodle.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
				.login(iss, clientId, loginHint, messageHint, target);
		session.setAttribute("moodleState", login.state());
		return redirect(login.authorizationUrl());
	}

	@PostMapping("/lti/launch")
	public ResponseEntity<Void> moodleLaunch(@RequestParam String state, @RequestParam(name = "id_token") String token) {
		moodle.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).verify(state, token);
		// Moodle's cross-site form POST omits a SameSite=Lax cookie. Complete on a top-level GET,
		// where the original session is available, before trusting this identity in the browser.
		return redirect(URI.create("/lti/complete?state=" + state));
	}

	@GetMapping("/lti/complete")
	public ResponseEntity<Void> moodleComplete(@RequestParam String state, HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if(session == null || !state.equals(session.getAttribute("moodleState"))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Moodle login belongs to another browser session");
		}
		MoodleLaunch.Launch launch = moodle.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).complete(state);
		session.removeAttribute("moodleState");
		request.changeSessionId();
		session.setAttribute("moodleLaunch", launch);
		session.removeAttribute("github");
		session.removeAttribute("oauth");
		return redirect(URI.create("/accept/" + launch.lectureId() + "/" + launch.assignmentId()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<String> invalid(IllegalArgumentException exception) {
		return page(HttpStatus.BAD_REQUEST, "Unable to accept assignment", "<p>" + escape(exception.getMessage()) + "</p><p>Return to the assignment and try again.</p>");
	}

	@ExceptionHandler(JwtException.class)
	ResponseEntity<String> invalidLaunch() {
		return page(HttpStatus.FORBIDDEN, "Moodle login could not be verified", "<p>Open the activity in Moodle again.</p>");
	}

	@ExceptionHandler(IOException.class)
	ResponseEntity<String> unavailable() {
		return page(HttpStatus.SERVICE_UNAVAILABLE, "Registration could not finish",
				"<p>Return to the assignment and try again. If it still fails, contact the instructor.</p>");
	}

	private MoodleLaunch.Launch launch(HttpSession session, String lectureId, String assignmentId) {
		MoodleLaunch.Launch launch = (MoodleLaunch.Launch) session.getAttribute("moodleLaunch");
		return launch != null && launch.lectureId().equals(lectureId) && launch.assignmentId().equals(assignmentId) ? launch : null;
	}

	private String csrf(HttpSession session) {
		if(session.getAttribute("csrf") == null) {
			session.setAttribute("csrf", token());
		}
		return (String) session.getAttribute("csrf");
	}

	private void checkCsrf(HttpSession session, String csrf) {
		if(csrf == null || !csrf.equals(session.getAttribute("csrf"))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid form; reload the assignment page");
		}
	}

	private String token() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String escape(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value);
	}

	private static String hidden(String name, String value) {
		return "<input type='hidden' name='" + name + "' value='" + escape(value) + "'>";
	}

	private static ResponseEntity<Void> redirect(URI uri) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(uri).header("Cache-Control", "no-store")
				.header("Referrer-Policy", "no-referrer").build();
	}

	private static ResponseEntity<String> page(HttpStatus status, String title, String body) {
		return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store")
				.header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' https://github.com; frame-ancestors 'none'; base-uri 'none'")
				.body("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
						+ "<title>Assignment registration</title><style>body{font:1.1rem system-ui;max-width:42rem;margin:3rem auto;padding:0 1rem;line-height:1.5}"
						+ "label{display:block;margin:1rem 0}input:not([type=checkbox]){display:block;width:100%;box-sizing:border-box;padding:.5rem}"
						+ "button{padding:.6rem 1rem;cursor:pointer}form{margin:1.5rem 0}</style></head><body><h1>" + title + "</h1>" + body + "</body></html>");
	}
}
