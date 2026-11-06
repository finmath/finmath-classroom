package org.finmath.classroom.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoodleGradeExportTest {
    @TempDir Path directory;
    private Path input;
    private Path output;

    @BeforeEach void prepare() {
        input = directory.resolve("points.csv");
        output = directory.resolve("moodle.csv");
    }

    @Test void scalesAndRoundsPointsWhileKeepingMissingDistinctFromZero() throws IOException {
        Files.writeString(input, "username,points\nalice,15\nbob,0\ncarol,\ndave,22\n");
        var summary = export("22", "10", "0", 2);
        assertEquals(new MoodleGradeExport.Summary(3, 1), summary);
        assertEquals("username,Assignment\nalice,6.82\nbob,0.00\ndave,10.00\n", Files.readString(output));
        assertEquals("username,points\nalice,15\nbob,0\ncarol,\ndave,22\n", Files.readString(input));
        if (Files.getFileStore(output).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output));
        }
    }

    @Test void handlesBomCrLfUnicodeAndQuotedIgnoredColumns() throws IOException {
        Files.writeString(input, "\uFEFFMoodle User Name,GitHub Points,Notes\r\n"
                + "\"müller,\"\"a\"\"\",8,\"first line\r\nsecond line\"\r\n");
        var result = MoodleGradeExport.export(input, output, "Moodle User Name", "GitHub Points",
                "Task, \"one\"", new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ZERO, 1);
        assertEquals(new MoodleGradeExport.Summary(1, 0), result);
        assertEquals("username,\"Task, \"\"one\"\"\"\n\"müller,\"\"a\"\"\",4.0\n", Files.readString(output));
    }

    @Test void appliesOffsetsInMoodlePointsAndClampsBothEnds() throws IOException {
        Files.writeString(input, "username,points\nalice,10\nbob,0");
        export("10", "5", "1", 2);
        assertEquals("username,Assignment\nalice,5.00\nbob,1.00\n", Files.readString(output));
        Files.delete(output);
        export("10", "5", "-1", 2);
        assertEquals("username,Assignment\nalice,4.00\nbob,0.00\n", Files.readString(output));
    }

    @Test void roundsExactHalfUpWithoutDivisionPrecisionLoss() throws IOException {
        Files.writeString(input, "username,points\nalice,1\nbob,3\n");
        export("8", "1", "0", 2);
        assertEquals("username,Assignment\nalice,0.13\nbob,0.38\n", Files.readString(output));
    }

    @Test void neverRoundsAboveAnExactMoodleMaximum() throws IOException {
        Files.writeString(input, "username,points\nalice,1\n");
        export("1", "0.999", "0", 2);
        assertEquals("username,Assignment\nalice,0.999\n", Files.readString(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "username,points\nalice,1\nALICE,2\n",
            "username,points\nalice,\nalice,1\n",
            "username,points\n,1\n",
            "username,points\n alice,1\n",
            "username,points\nalice ,1\n",
            "username,points\n=SUM(1),1\n",
            "username,points\n+alice,1\n",
            "username,points\n-alice,1\n",
            "username,points\n@alice,1\n",
            "username,points\n\talice,1\n",
            "username,points\n\"ali\nce\",1\n",
            "username,points\nali\u202Ece,1\n",
            "username,points\nalice,-1\n",
            "username,points\nalice,11\n",
            "username,points\nalice,NaN\n",
            "username,points\nalice,Infinity\n",
            "username,points\nalice,unavailable\n",
            "username,points\nalice,1e-999999999\n",
            "username,points\nalice,1e999999999\n",
            "username,points\nalice,\n",
            "username,points\nalice\n",
            "username,points\nalice,1,extra\n",
            "username,points\nali\"ce,1\n",
            "username,points\n\"alice\"x,1\n",
            "username,points\n\"alice,1\n",
            "username,points\nalice,1\n\n",
            "username,username,points\nalice,alice,1\n",
            "username,,points\nalice,x,1\n",
            "username,score\nalice,1\n",
            "username,points\n",
            ""
    })
    void rejectsInvalidInputWithoutProducingPartialGrades(String csv) throws IOException {
        Files.writeString(input, csv);
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", 2));
        assertFalse(Files.exists(output));
    }

    @Test void lateInvalidRecordDoesNotLeaveEarlierGrades() throws IOException {
        Files.writeString(input, "username,points\nalice,1\nbob,NaN\n");
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", 2));
        assertFalse(Files.exists(output));
    }

    @Test void refusesExistingFilesAndAliasesIncludingTheInput() throws IOException {
        Files.writeString(input, "username,points\nalice,1\n");
        Files.writeString(output, "previous grades");
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", 2));
        assertEquals("previous grades", Files.readString(output));
        Files.delete(output);
        Files.createSymbolicLink(output, input);
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", 2));
        output = input;
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", 2));
        assertEquals("username,points\nalice,1\n", Files.readString(input));
    }

    @Test void rejectsMalformedUtf8RatherThanChangingIdentity() throws IOException {
        Files.write(input, new byte[]{(byte) 0xC3, 0x28});
        assertThrows(IOException.class, () -> export("10", "5", "0", 2));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsInvalidScaleAndColumnConfiguration() throws IOException {
        Files.writeString(input, "username,points\nalice,1\n");
        assertThrows(IllegalArgumentException.class, () -> export("0", "5", "0", 2));
        assertThrows(IllegalArgumentException.class, () -> export("10", "-5", "0", 2));
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", -1));
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "0", 7));
        assertThrows(IllegalArgumentException.class, () -> export("10", "5", "1e10", 2));
        for (String header : new String[]{"username", "Username", "", "=Assignment", " Assignment", "Task\n1"}) {
            assertThrows(IllegalArgumentException.class, () -> MoodleGradeExport.export(input, output,
                    "username", "points", header, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, 2));
        }
        assertThrows(IllegalArgumentException.class, () -> MoodleGradeExport.export(input, output,
                "username", "username", "Task", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, 2));
        assertFalse(Files.exists(output));
    }

    private MoodleGradeExport.Summary export(String sourceMax, String moodleMax, String offset, int decimals)
            throws IOException {
        return MoodleGradeExport.export(input, output, "username", "points", "Assignment",
                new BigDecimal(sourceMax), new BigDecimal(moodleMax), new BigDecimal(offset), decimals);
    }
}
