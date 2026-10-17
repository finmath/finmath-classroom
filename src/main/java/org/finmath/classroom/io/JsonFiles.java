package org.finmath.classroom.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict, bounded JSON input; shared by the offline tools, not application HTTP binding. */
public final class JsonFiles {
    public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(50).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private JsonFiles() { }

    public static JsonNode read(Path path, int limit) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(SafeFiles.read(path, limit))).toString();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try {
            JsonNode value = MAPPER.readTree(text);
            if (value == null || value.isMissingNode()) {
                throw new IllegalArgumentException("Empty JSON document: " + path);
            }
            return value;
        } catch (RuntimeException exception) {
            // Avoid echoing arbitrary private input fragments from a parser diagnostic.
            throw new IllegalArgumentException("Invalid JSON document: " + path, exception);
        }
    }
}
