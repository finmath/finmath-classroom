package org.finmath.classroom.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoodleExportCliTest {
    @TempDir Path directory;

    @Test void defaultColumnsScaleScoresAndSkipMissingMarks() throws Exception {
        Path input = Files.writeString(directory.resolve("points.csv"), """
                username,points
                alice,15
                bob,0
                charlie,
                """);
        Path output = directory.resolve("moodle.csv");

        Result result = run(command(input, output));

        assertEquals(0, result.code(), result.error());
        List<String> rows = Files.readAllLines(output);
        assertEquals("username,Interpolation", rows.get(0));
        assertGrade(rows.get(1), "alice", "7.5");
        assertGrade(rows.get(2), "bob", "0");
        assertEquals(3, rows.size(), "A missing mark must not be imported as zero");
        String summary = result.output().toLowerCase(Locale.ROOT);
        assertTrue(summary.contains("2"), summary);
        assertTrue(summary.contains("1"), summary);
        assertTrue(summary.contains("skip"), summary);
        assertTrue(summary.contains("override"), summary);
    }

    @Test void customColumnNamesOffsetAndPrecisionReachExporter() throws Exception {
        Path input = Files.writeString(directory.resolve("points.csv"), """
                Moodle User Name,GitHub points,Comment
                alice,5,Reviewed
                bob,20,Reviewed
                """);
        Path output = directory.resolve("moodle.csv");
        String[] args = append(command(input, output), "--username-column", "Moodle User Name",
                "--points-column", "GitHub points", "--offset", "0.75", "--decimals", "1");

        Result result = run(args);

        assertEquals(0, result.code(), result.error());
        List<String> rows = Files.readAllLines(output);
        assertGrade(rows.get(1), "alice", "3.3");
        assertGrade(rows.get(2), "bob", "10");
    }

    @Test void requiredOptionsAndUnsupportedCommandsFailWithoutAnExport() throws Exception {
        Path input = Files.writeString(directory.resolve("points.csv"), "username,points\nalice,10\n");
        Path output = directory.resolve("moodle.csv");
        for (String missing : List.of("--input", "--output", "--source-max", "--moodle-max", "--grade-item")) {
            List<String> args = new ArrayList<>(Arrays.asList(command(input, output)));
            int index = args.indexOf(missing);
            args.remove(index + 1);
            args.remove(index);
            Result result = run(args.toArray(String[]::new));
            assertEquals(2, result.code(), "Missing " + missing);
            assertFalse(Files.exists(output));
        }
        assertEquals(2, run("moodle").code());
        assertEquals(2, run("moodle", "sync").code());
        assertEquals(2, run(append(command(input, output), "--unknown", "value")).code());
        assertEquals(2, run(append(command(input, output), "--source-max", "20")).code());
        assertFalse(Files.exists(output));
    }

    @Test void malformedNumericOptionsDoNotCreateAnExport() throws Exception {
        Path input = Files.writeString(directory.resolve("points.csv"), "username,points\nalice,10\n");
        Path output = directory.resolve("moodle.csv");
        for (String[] option : List.of(new String[]{"--offset", "NaN"},
                new String[]{"--decimals", "many"}, new String[]{"--decimals", "-1"},
                new String[]{"--decimals", "7"})) {
            assertEquals(2, run(append(command(input, output), option)).code());
            assertFalse(Files.exists(output));
        }
        for (String option : List.of("--source-max", "--moodle-max")) {
            String[] args = command(input, output);
            args[Arrays.asList(args).indexOf(option) + 1] = "0";
            assertEquals(2, run(args).code());
            assertFalse(Files.exists(output));
        }
    }

    @Test void existingExportAndInputAreNeverReplaced() throws Exception {
        String original = "username,points\nalice,10\n";
        Path input = Files.writeString(directory.resolve("points.csv"), original);
        Path output = Files.writeString(directory.resolve("moodle.csv"), "Previously reviewed export\n");

        assertEquals(2, run(command(input, output)).code());
        assertEquals("Previously reviewed export\n", Files.readString(output));
        assertEquals(2, run(command(input, input)).code());
        assertEquals(original, Files.readString(input));
    }

    private static String[] command(Path input, Path output) {
        return new String[]{"moodle", "export", "--input", input.toString(), "--output", output.toString(),
                "--source-max", "20", "--moodle-max", "10", "--grade-item", "Interpolation"};
    }

    private static String[] append(String[] args, String... extra) {
        String[] result = Arrays.copyOf(args, args.length + extra.length);
        System.arraycopy(extra, 0, result, args.length, extra.length);
        return result;
    }

    private static void assertGrade(String row, String username, String expected) {
        String[] columns = row.split(",", -1);
        assertEquals(2, columns.length);
        assertEquals(username, columns[0]);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(columns[1])));
    }

    private static Result run(String... args) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code = ClassroomCli.run(args, new PrintStream(output), new PrintStream(error));
        return new Result(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String output, String error) { }
}
