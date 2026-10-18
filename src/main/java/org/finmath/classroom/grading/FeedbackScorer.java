package org.finmath.classroom.grading;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;
import org.finmath.classroom.io.SafeXml;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, fail-closed scoring of JUnit reports for student feedback, not final marks. */
public final class FeedbackScorer {
    static final int MAX_RUBRIC_BYTES = 256 * 1024;
    static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;
    static final int MAX_TOTAL_REPORT_BYTES = 16 * 1024 * 1024;
    static final int MAX_REPORT_FILES = 512;
    static final int MAX_EXPECTED_TESTS = 500;
    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final Pattern CLASS_NAME = Pattern.compile(IDENTIFIER + "(?:\\." + IDENTIFIER + ")*");
    private static final Pattern METHOD_NAME = Pattern.compile(IDENTIFIER);
    private static final Pattern XML_DECLARATION = Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE);

    private FeedbackScorer() {
    }

    /** Surefire selectors for one Maven invocation, using the same validated rubric as scoring. */
    public static String testSelection(Path rubric) throws IOException {
        return String.join(",", loadRubric(rubric).stream()
                .map(test -> test.key().className() + "#" + test.key().method()).toList());
    }

    /** Invalid rubrics are rejected; invalid reports produce an error result. */
    public static JsonNode score(Path rubric, Path reports) throws IOException {
        List<ExpectedCase> expected = loadRubric(rubric);
        Reports collected = collectReports(reports);
        ObjectNode result = JsonFiles.MAPPER.createObjectNode();
        result.put("schema_version", 1);
        result.put("authoritative", false);
        result.put("purpose", "student feedback");
        ArrayNode cases = result.putArray("cases");
        Set<String> states = new HashSet<>();
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal maximum = BigDecimal.ZERO;
        for (ExpectedCase test : expected) {
            List<String> occurrences = collected.found().getOrDefault(test.key(), List.of());
            String state;
            if (!collected.errors().isEmpty()) {
                state = "error";
            } else if (occurrences.isEmpty()) {
                state = "missing";
            } else if (occurrences.size() != 1) {
                state = "duplicate";
            } else {
                state = occurrences.get(0);
            }
            BigDecimal points = state.equals("passed") ? test.points() : BigDecimal.ZERO;
            ObjectNode entry = cases.addObject();
            entry.put("class_name", test.key().className());
            entry.put("method", test.key().method());
            entry.put("status", state);
            entry.put("occurrences", occurrences.size());
            entry.put("points", points);
            entry.put("max_points", test.points());
            earned = earned.add(points);
            maximum = maximum.add(test.points());
            states.add(state);
        }
        String status;
        if (!collected.errors().isEmpty() || states.contains("error")) {
            status = "error";
        } else if (states.contains("missing") || states.contains("duplicate") || states.contains("skipped")) {
            status = "incomplete";
        } else if (states.contains("failed")) {
            status = "failed";
        } else {
            status = "passed";
        }
        result.put("status", status);
        result.put("score", earned);
        result.put("max_score", maximum);
        ArrayNode errors = result.putArray("errors");
        collected.errors().forEach(errors::add);
        return result;
    }

    /** Formats an appendable GitHub job summary without displaying untrusted XML errors. */
    public static String summary(JsonNode result) {
        StringBuilder text = new StringBuilder("## Assignment feedback\n\n");
        text.append("Status: **").append(result.path("status").asString()).append("** — ")
                .append(number(result.path("score"))).append('/')
                .append(number(result.path("max_score"))).append(" points.\n\n")
                .append("Feedback only. Final marks require instructor-controlled grading.\n\n")
                .append("| Test | Result | Points |\n| --- | --- | ---: |\n");
        for (JsonNode test : result.path("cases")) {
            text.append("| `").append(test.path("method").asString()).append("` | ")
                    .append(test.path("status").asString()).append(" | ")
                    .append(number(test.path("points"))).append('/')
                    .append(number(test.path("max_points"))).append(" |\n");
        }
        if (!result.path("errors").isEmpty()) {
            text.append("\nReport input could not be validated; see feedback JSON for details.\n");
        }
        return text.toString();
    }

    /** Passed = 0, failed/incomplete = 1, invalid input/report errors = 2. */
    public static int exitCode(JsonNode result) {
        return switch (result.path("status").asString()) {
            case "passed" -> 0;
            case "failed", "incomplete" -> 1;
            default -> 2;
        };
    }

    private static String number(JsonNode value) {
        return value.decimalValue().stripTrailingZeros().toPlainString();
    }

    private static List<ExpectedCase> loadRubric(Path path) throws IOException {
        JsonNode rubric = JsonFiles.read(path, MAX_RUBRIC_BYTES);
        if (!rubric.isObject() || rubric.size() != 1 || !rubric.has("tests")) {
            throw new IllegalArgumentException("rubric must be an object containing only 'tests'");
        }
        JsonNode tests = rubric.get("tests");
        if (!tests.isArray() || tests.isEmpty() || tests.size() > MAX_EXPECTED_TESTS) {
            throw new IllegalArgumentException("tests must contain 1 to " + MAX_EXPECTED_TESTS + " cases");
        }
        List<ExpectedCase> result = new ArrayList<>();
        Set<CaseKey> seen = new HashSet<>();
        for (JsonNode test : tests) {
            if (!test.isObject() || test.size() != 3 || !test.has("class_name")
                    || !test.has("method") || !test.has("points")) {
                throw new IllegalArgumentException("each case requires exactly class_name, method, and points");
            }
            String className = identifier(test.get("class_name"), CLASS_NAME, "class_name must be a Java class name");
            String method = identifier(test.get("method"), METHOD_NAME, "method must be a Java method name");
            JsonNode numeric = test.get("points");
            if (!numeric.isNumber() || !Double.isFinite(numeric.doubleValue())) {
                throw new IllegalArgumentException("points must be a positive finite number");
            }
            BigDecimal points = numeric.decimalValue();
            if (points.signum() <= 0 || points.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
                throw new IllegalArgumentException("points must be positive, finite, and at most 1000000");
            }
            CaseKey key = new CaseKey(className, method);
            if (!seen.add(key)) {
                throw new IllegalArgumentException("duplicate expected case: " + className + "#" + method);
            }
            result.add(new ExpectedCase(key, points));
        }
        return result;
    }

    private static String identifier(JsonNode node, Pattern pattern, String message) {
        if (!node.isTextual() || node.stringValue().length() > 300 || !pattern.matcher(node.stringValue()).matches()) {
            throw new IllegalArgumentException(message);
        }
        return node.stringValue();
    }

    private static Reports collectReports(Path directory) {
        Map<CaseKey, List<String>> found = new HashMap<>();
        List<String> errors = new ArrayList<>();
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new Reports(found, errors);
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new Reports(found, List.of("reports path must be a non-symlink directory"));
        }
        List<Path> paths = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory, "TEST-*.xml")) {
            for (Path path : stream) {
                paths.add(path);
                if (paths.size() > MAX_REPORT_FILES) {
                    return new Reports(found, List.of("too many report files"));
                }
            }
        } catch (IOException | DirectoryIteratorException exception) {
            return new Reports(found, List.of("unable to read reports directory: " + exception.getMessage()));
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        int totalBytes = 0;
        for (Path path : paths) {
            try {
                byte[] data = SafeFiles.read(path, MAX_REPORT_BYTES);
                totalBytes += data.length;
                if (totalBytes > MAX_TOTAL_REPORT_BYTES) {
                    return new Reports(found, List.of("reports exceed combined size limit"));
                }
                String source = utf8(data);
                if (XML_DECLARATION.matcher(source).find()) {
                    throw new IllegalArgumentException("DTD and entity declarations are forbidden");
                }
                Element root = SafeXml.parse(source.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
                if (!Set.of("testsuite", "testsuites").contains(localName(root))) {
                    throw new IllegalArgumentException("expected a testsuite or testsuites root");
                }
                var elements = root.getElementsByTagName("*");
                for (int i = 0; i < elements.getLength(); i++) {
                    Element element = (Element) elements.item(i);
                    if (!localName(element).equals("testcase")) {
                        continue;
                    }
                    CaseKey key = new CaseKey(element.getAttribute("classname"), element.getAttribute("name"));
                    Set<String> markers = new HashSet<>();
                    var children = element.getChildNodes();
                    for (int child = 0; child < children.getLength(); child++) {
                        if (children.item(child).getNodeType() == Node.ELEMENT_NODE) {
                            markers.add(localName(children.item(child)));
                        }
                    }
                    String state = markers.contains("error") ? "error"
                            : markers.contains("failure") ? "failed"
                            : markers.contains("skipped") ? "skipped" : "passed";
                    found.computeIfAbsent(key, unused -> new ArrayList<>()).add(state);
                }
            } catch (Exception exception) {
                // A single unreadable or malformed report invalidates every case.
                errors.add(path.getFileName() + ": " + exception.getMessage());
            }
        }
        return new Reports(found, errors);
    }

    private static String utf8(byte[] data) throws CharacterCodingException {
        String source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        if (source.startsWith("\ufeff")) {
            source = source.substring(1);
        }
        // UTF-16 without a BOM decodes as UTF-8 containing NULs. Prevent the XML
        // byte parser from reinterpreting that input as UTF-16 afterwards.
        if (source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("reports must contain UTF-8 XML");
        }
        return source;
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private record CaseKey(String className, String method) {
    }

    private record ExpectedCase(CaseKey key, BigDecimal points) {
    }

    private record Reports(Map<CaseKey, List<String>> found, List<String> errors) {
    }
}
