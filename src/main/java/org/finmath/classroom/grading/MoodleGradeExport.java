package org.finmath.classroom.grading;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.finmath.classroom.io.SafeFiles;

/** Converts instructor-supplied points to a Moodle gradebook import without remote writes. */
public final class MoodleGradeExport {
    private static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    private static final BigDecimal NUMBER_LIMIT = new BigDecimal("1000000000");

    private MoodleGradeExport() { }

    public record Summary(int exported, int skipped) { }
    public record Grade(String username, BigDecimal points, BigDecimal sourceMax) { }

    /**
     * Applies points / sourceMax * moodleMax + offset, clamped to the Moodle range,
     * with HALF_UP rounding. A final cap preserves an exact maximum finer than decimals.
     * Blank scores are omitted; all other invalid input rejects the entire export.
     */
    public static Summary export(Path input, Path output, String usernameColumn, String pointsColumn,
            String gradeItem, BigDecimal sourceMax, BigDecimal moodleMax, BigDecimal offset, int decimals)
            throws IOException {
        Path destination = SafeFiles.validateOutput(output, false);
        validateNumber(sourceMax, "Source maximum");
        if (sourceMax.signum() <= 0) throw invalid("Source maximum must be positive");
        if (usernameColumn == null || usernameColumn.equals(pointsColumn)) {
            throw invalid("Username and points columns must be distinct");
        }

        String content = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(SafeFiles.read(input, MAX_INPUT_BYTES))).toString();
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        List<List<String>> rows = parseCsv(content);
        if (rows.isEmpty()) throw invalid("CSV must contain a header");
        List<String> header = rows.get(0);
        Set<String> headers = new HashSet<>();
        for (String field : header) {
            if (field.isBlank() || !headers.add(field)) throw invalid("CSV headers must be nonblank and unique");
        }
        int usernameIndex = header.indexOf(usernameColumn);
        int pointsIndex = header.indexOf(pointsColumn);
        if (usernameIndex < 0 || pointsIndex < 0) {
            throw invalid("CSV must contain selected username and points columns");
        }

        List<Grade> grades = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String location = "CSV record " + (index + 1);
            if (row.size() != header.size()) throw invalid(location + " has the wrong number of columns");
            String username = row.get(usernameIndex);
            String score = row.get(pointsIndex).strip();
            grades.add(new Grade(username, score.isEmpty() ? null : decimal(score, location + " points"), sourceMax));
        }
        return export(grades, destination, gradeItem, moodleMax, offset, decimals);
    }

    /** Scales already identified grades using each result's source maximum, without an intermediate file. */
    public static Summary export(List<Grade> grades, Path output, String gradeItem,
            BigDecimal moodleMax, BigDecimal offset, int decimals) throws IOException {
        Path destination = SafeFiles.validateOutput(output, false);
        validateNumber(moodleMax, "Moodle maximum");
        validateNumber(offset, "Offset");
        if (moodleMax.signum() <= 0) throw invalid("Moodle maximum must be positive");
        if (decimals < 0 || decimals > 6) throw invalid("Decimal places must be between 0 and 6");
        validateText(gradeItem, "Grade item");
        if (gradeItem.equalsIgnoreCase("username")) throw invalid("Grade item must differ from username");
        StringBuilder csv = new StringBuilder();
        appendRow(csv, "username", gradeItem);
        Set<String> usernames = new HashSet<>();
        int exported = 0;
        int skipped = 0;
        for (int index = 0; index < grades.size(); index++) {
            Grade entry = grades.get(index);
            String location = "Grade record " + (index + 1);
            validateText(entry.username(), location + " username");
            if (!usernames.add(entry.username().toLowerCase(Locale.ROOT))) {
                throw invalid(location + " has a duplicate username");
            }
            BigDecimal sourceMax = entry.sourceMax();
            validateNumber(sourceMax, location + " source maximum");
            if (sourceMax.signum() <= 0) throw invalid(location + " source maximum must be positive");
            BigDecimal points = entry.points();
            if (points == null) {
                skipped++;
                continue;
            }
            validateNumber(points, location + " points");
            if (points.signum() < 0 || points.compareTo(sourceMax) > 0) {
                throw invalid(location + " points must be between zero and the source maximum");
            }
            BigDecimal numerator = points.multiply(moodleMax).add(offset.multiply(sourceMax))
                    .max(BigDecimal.ZERO).min(moodleMax.multiply(sourceMax));
            BigDecimal grade = numerator.divide(sourceMax, decimals, RoundingMode.HALF_UP).min(moodleMax);
            appendRow(csv, entry.username(), grade.toPlainString());
            exported++;
        }
        if (exported == 0) throw invalid("CSV contains no numeric points to export");
        SafeFiles.writeAtomic(destination, csv.toString().getBytes(StandardCharsets.UTF_8), false);
        return new Summary(exported, skipped);
    }

    private static BigDecimal decimal(String value, String label) {
        if (value.length() > 64) throw invalid(label + " is too long");
        try {
            BigDecimal number = new BigDecimal(value);
            validateNumber(number, label);
            return number;
        } catch (NumberFormatException exception) {
            throw invalid(label + " must be a decimal number");
        }
    }

    static void validateNumber(BigDecimal value, String label) {
        if (value == null || value.precision() > 32 || value.scale() < -9 || value.scale() > 12
                || value.abs().compareTo(NUMBER_LIMIT) > 0) {
            throw invalid(label + " must have magnitude at most 1e9 and at most 12 decimal places");
        }
    }

    static void validateText(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 1024
                || "=+-@".indexOf(value.charAt(0)) >= 0
                || value.codePoints().anyMatch(character -> Character.isISOControl(character)
                    || Character.getType(character) == Character.FORMAT
                    || Character.getType(character) == Character.LINE_SEPARATOR
                    || Character.getType(character) == Character.PARAGRAPH_SEPARATOR)) {
            throw invalid(label + " must be nonblank, without surrounding whitespace, controls or a formula prefix");
        }
    }

    private static void appendRow(StringBuilder output, String first, String second) {
        output.append(quote(first)).append(',').append(quote(second)).append('\n');
    }

    private static String quote(String value) {
        return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /** Strict comma CSV: quoted fields may contain separators, line breaks and doubled quotes. */
    private static List<List<String>> parseCsv(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < input.length() && input.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(character);
                }
            } else if (character == ',' || character == '\n' || character == '\r') {
                row.add(field.toString());
                field.setLength(0);
                closedQuote = false;
                if (character != ',') {
                    rows.add(row);
                    row = new ArrayList<>();
                    if (character == '\r' && index + 1 < input.length() && input.charAt(index + 1) == '\n') index++;
                }
            } else if (character == '"' && field.length() == 0 && !closedQuote) {
                quoted = true;
            } else if (closedQuote || character == '"') {
                throw invalid("Malformed CSV quoting in record " + (rows.size() + 1));
            } else {
                field.append(character);
            }
        }
        if (quoted) throw invalid("Unclosed CSV quote in record " + (rows.size() + 1));
        if (!row.isEmpty() || field.length() > 0 || closedQuote) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
