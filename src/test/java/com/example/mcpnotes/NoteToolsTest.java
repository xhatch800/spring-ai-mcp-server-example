package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

class NoteToolsTest {

    private static final NoteJson JSON = new NoteJson(TestSupport.MAPPER);

    private NoteService service;
    private NoteTools tools;

    @BeforeEach
    void setUp() {
        service = TestSupport.seededService(new MutableClock());
        tools = new NoteTools(service, JSON);
    }

    private static void assertError(CallToolResult result, String message) {
        assertThat(result.isError()).isTrue();
        assertThat(TestSupport.text(result)).isEqualTo("Error: " + message);
    }

    @Nested
    class AddNote {

        @Test
        void returnsSavedNoteAsJson() throws Exception {
            CallToolResult result = tools.addNote("Plan", "Write tests", List.of("Work", "todo"));

            assertThat(result.isError()).isFalse();
            String text = TestSupport.text(result);
            assertThat(text).startsWith("Note added: ");
            JsonNode node = TestSupport.MAPPER.readTree(text.substring("Note added: ".length()));
            assertThat(node.get("id").asText()).isEqualTo("4");
            assertThat(node.get("title").asText()).isEqualTo("Plan");
            assertThat(node.get("body").asText()).isEqualTo("Write tests");
            assertThat(node.get("tags")).extracting(JsonNode::asText).containsExactly("work", "todo");
            assertThat(node.get("createdAt").asText()).isEqualTo("2026-01-15T10:00:00Z");
            assertThat(service.find("4")).isPresent();
        }

        @Test
        void nullTagsAreAcceptedAsNoTags() {
            CallToolResult result = tools.addNote("Plan", "body", null);

            assertThat(result.isError()).isFalse();
            assertThat(service.find("4").orElseThrow().tags()).isEmpty();
        }

        @Test
        void emptyTagsAreAcceptedAsNoTags() {
            CallToolResult result = tools.addNote("Plan", "body", List.of());

            assertThat(result.isError()).isFalse();
            assertThat(service.find("4").orElseThrow().tags()).isEmpty();
        }

        @Test
        void blankTagsInsideTheListAreDropped() {
            tools.addNote("Plan", "body", Arrays.asList("", null, "ok"));

            assertThat(service.find("4").orElseThrow().tags()).containsExactly("ok");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void blankTitleReturnsErrorResultAndStoresNothing(String title) {
            CallToolResult result = tools.addNote(title, "body", List.of("x"));

            assertError(result, "Title must not be blank.");
            assertThat(service.count()).isEqualTo(3);
        }
    }

    @Nested
    class SearchNotes {

        @Test
        void returnsMatchesAsJsonArray() throws Exception {
            CallToolResult result = tools.searchNotes("SPRING");

            assertThat(result.isError()).isFalse();
            JsonNode array = TestSupport.MAPPER.readTree(TestSupport.text(result));
            assertThat(array.isArray()).isTrue();
            assertThat(array).extracting(n -> n.get("title").asText()).containsExactly("Spring AI MCP annotations");
        }

        @Test
        void matchesOnTags() throws Exception {
            JsonNode array = TestSupport.MAPPER.readTree(TestSupport.text(tools.searchNotes("personal")));

            assertThat(array).extracting(n -> n.get("title").asText()).containsExactly("Groceries");
        }

        @Test
        void noMatchIsASuccessfulEmptyAnswerNotAnError() {
            CallToolResult result = tools.searchNotes("  zzz  ");

            assertThat(result.isError()).isFalse();
            assertThat(TestSupport.text(result)).isEqualTo("No notes matched 'zzz'.");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\n"})
        void blankQueryReturnsErrorResult(String query) {
            assertError(tools.searchNotes(query), "Search query must not be blank.");
        }
    }

    @Nested
    class DeleteNote {

        @Test
        void deletesExistingNote() {
            CallToolResult result = tools.deleteNote("3");

            assertThat(result.isError()).isFalse();
            assertThat(TestSupport.text(result)).isEqualTo("Deleted note 3 (\"Groceries\").");
            assertThat(service.find("3")).isEmpty();
        }

        @Test
        void idWithWhitespaceStillWorks() {
            assertThat(tools.deleteNote(" 1 ").isError()).isFalse();
            assertThat(service.find("1")).isEmpty();
        }

        @Test
        void unknownIdReturnsErrorResult() {
            CallToolResult result = tools.deleteNote("99");

            assertError(result, "No note found with id '99'. "
                    + "Use search_notes or the notes://all resource to find valid ids.");
            assertThat(service.count()).isEqualTo(3);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void blankIdReturnsErrorResult(String id) {
            assertError(tools.deleteNote(id), "Note id must not be blank.");
            assertThat(service.count()).isEqualTo(3);
        }

        @Test
        void deletingTwiceSecondTimeIsAnError() {
            tools.deleteNote("1");

            assertThat(tools.deleteNote("1").isError()).isTrue();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class WithMockedService {

        @Mock
        NoteService mockService;

        @Test
        void noteRemovedByAnotherCallerBetweenFindAndDeleteIsReportedAsNotFound() {
            Note note = new Note("5", "Gone", "", List.of(), MutableClock.START);
            when(mockService.find("5")).thenReturn(Optional.of(note));
            when(mockService.delete("5")).thenReturn(false);

            CallToolResult result = new NoteTools(mockService, JSON).deleteNote("5");

            assertThat(result.isError()).isTrue();
            assertThat(TestSupport.text(result)).startsWith("Error: No note found with id '5'");
        }

        @Test
        void blankIdNeverReachesTheService() {
            new NoteTools(mockService, JSON).deleteNote(" ");

            verify(mockService, never()).find(org.mockito.ArgumentMatchers.any());
            verify(mockService, never()).delete(org.mockito.ArgumentMatchers.any());
        }
    }
}
