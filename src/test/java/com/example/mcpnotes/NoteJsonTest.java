package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoteJsonTest {

    @Test
    void writesNoteWithIsoTimestamp() {
        NoteJson json = new NoteJson(TestSupport.MAPPER);

        String result = json.write(new Note("1", "T", "B", List.of("a"), Instant.parse("2026-01-15T10:00:00Z")));

        assertThat(result).isEqualTo(
                "{\"id\":\"1\",\"title\":\"T\",\"body\":\"B\",\"tags\":[\"a\"],\"createdAt\":\"2026-01-15T10:00:00Z\"}");
    }

    @Test
    void wrapsSerializationFailures() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") { });
        NoteJson json = new NoteJson(broken);

        assertThatThrownBy(() -> json.write("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not serialize value to JSON")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }
}
