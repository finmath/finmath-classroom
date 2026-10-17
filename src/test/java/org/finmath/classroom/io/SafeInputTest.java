package org.finmath.classroom.io;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeInputTest {
    @TempDir Path directory;

    @Test void rejectsDuplicateKeysAndTrailingJson() throws Exception {
        Path input = directory.resolve("input.json");
        for (String text : new String[] {"{\"x\":1,\"x\":2}", "{} {}", ""}) {
            Files.writeString(input, text);
            assertThrows(IllegalArgumentException.class, () -> JsonFiles.read(input, 1024));
        }
    }

    @Test void boundedFilesAndLinksFailClosed() throws Exception {
        Path input = directory.resolve("input.json");
        Files.writeString(input, "{\"x\":1}");
        assertEquals(1, JsonFiles.read(input, 1024).get("x").intValue());
        assertThrows(IllegalArgumentException.class, () -> SafeFiles.read(input, 2));
        Path link = directory.resolve("link.json");
        Files.createSymbolicLink(link, input);
        assertThrows(IllegalArgumentException.class, () -> SafeFiles.read(link, 1024));
    }

    @Test void malformedUtf8IsRejected() throws Exception {
        Path input = directory.resolve("invalid.json");
        Files.write(input, new byte[] {(byte) 0xff});
        assertThrows(java.io.IOException.class, () -> JsonFiles.read(input, 100));
    }

    @Test void doctypeAndExternalEntitiesAreDisabled() {
        String xml = "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///should-never-be-read'>]><root>&x;</root>";
        assertThrows(IllegalArgumentException.class, () -> SafeXml.parse(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void atomicOutputDoesNotOverwriteUnlessRequested() throws Exception {
        Path output = directory.resolve("output.json");
        SafeFiles.writeAtomic(output, "first".getBytes(StandardCharsets.UTF_8), false);
        assertThrows(IllegalArgumentException.class,
                () -> SafeFiles.writeAtomic(output, "second".getBytes(StandardCharsets.UTF_8), false));
        assertEquals("first", Files.readString(output));
        SafeFiles.writeAtomic(output, "second".getBytes(StandardCharsets.UTF_8), true);
        assertEquals("second", Files.readString(output));
    }
}
