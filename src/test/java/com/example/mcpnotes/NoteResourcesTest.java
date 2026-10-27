package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NoteResourcesTest {

    private NoteService service;
    private NoteResources resources;

    @BeforeEach
    void setUp() {
        service = TestSupport.seededService(new MutableClock());
        resources = new NoteResources(service, new NoteJson(TestSupport.MAPPER));
    }

    private static TextResourceContents only(ReadResourceResult result) {
        assertThat(result.contents()).hasSize(1);
        return (TextResourceContents) result.contents().get(0);
    }

    @Nested
    class AllNotes {

        @Test
        void usesTheAllUriAndJsonMimeType() {
            TextResourceContents contents = only(resources.allNotes());

            assertThat(contents.uri()).isEqualTo("notes://all");
            assertThat(contents.mimeType()).isEqualTo("application/json");
        }

        @Test
        void bodyIsAJsonArrayOfEveryNoteWithTheExpectedFields() throws Exception {
            JsonNode array = TestSupport.MAPPER.readTree(TestSupport.text(resources.allNotes()));

            assertThat(array.isArray()).isTrue();
            assertThat(array).hasSize(3);
            assertThat(array).extracting(n -> n.get("id").asText()).containsExactly("1", "2", "3");
            JsonNode first = array.get(0);
            assertThat(first.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("id", "title", "body", "tags", "createdAt");
            assertThat(first.get("tags").isArray()).isTrue();
            assertThat(first.get("createdAt").asText()).isEqualTo("2026-01-13T10:00:00Z");
        }

        @Test
        void emptyStoreGivesEmptyArray() {
            service.list().forEach(n -> service.delete(n.id()));

            assertThat(TestSupport.text(resources.allNotes())).isEqualTo("[]");
        }
    }

    @Nested
    class Stats {

        @Test
        void plainTextWithCountAndTopTags() {
            TextResourceContents contents = only(resources.stats());

            assertThat(contents.uri()).isEqualTo("notes://stats");
            assertThat(contents.mimeType()).isEqualTo("text/plain");
            assertThat(contents.text()).isEqualTo("Notes: 3\nTop tags: mcp (2), intro (1), personal (1), spring (1)");
        }

        @Test
        void emptyStoreSaysNone() {
            service.list().forEach(n -> service.delete(n.id()));

            assertThat(TestSupport.text(resources.stats())).isEqualTo("Notes: 0\nTop tags: none");
        }

        @Test
        void listsAtMostFiveTags() {
            service.add("many", "", List.of("a", "b", "c", "d", "e", "f", "g"));

            String text = TestSupport.text(resources.stats());

            assertThat(text.split("Top tags: ")[1].split(", ")).hasSize(5);
        }

        @Test
        void notesWithoutTagsReportNone() {
            service.list().forEach(n -> service.delete(n.id()));
            service.add("untagged", "", null);

            assertThat(TestSupport.text(resources.stats())).isEqualTo("Notes: 1\nTop tags: none");
        }
    }

    @Nested
    class NoteById {

        @ParameterizedTest
        @CsvSource({"1,Welcome to MCP notes", "2,Spring AI MCP annotations", "3,Groceries"})
        void returnsTheNoteForTheIdWithItsOwnUri(String id, String title) throws Exception {
            TextResourceContents contents = only(resources.noteById(id));

            assertThat(contents.uri()).isEqualTo("notes://" + id);
            assertThat(contents.mimeType()).isEqualTo("application/json");
            JsonNode node = TestSupport.MAPPER.readTree(contents.text());
            assertThat(node.isObject()).isTrue();
            assertThat(node.get("id").asText()).isEqualTo(id);
            assertThat(node.get("title").asText()).isEqualTo(title);
        }

        @Test
        void tagsAndBodyAreIncluded() throws Exception {
            JsonNode node = TestSupport.MAPPER.readTree(TestSupport.text(resources.noteById("2")));

            assertThat(node.get("tags")).extracting(JsonNode::asText).containsExactly("spring", "mcp");
            assertThat(node.get("body").asText()).contains("@McpTool");
        }

        @Test
        void unknownIdIsAResourceNotFoundError() {
            assertThatThrownBy(() -> resources.noteById("99"))
                    .isInstanceOfSatisfying(McpError.class, e -> {
                        assertThat(e.getJsonRpcError().code()).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND);
                        assertThat(e.getJsonRpcError().message())
                                .isEqualTo("No note found with id '99'. Read notes://all to list valid ids.");
                    });
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "abc"})
        void nullBlankOrNonNumericIdsAreNotFound(String id) {
            assertThatThrownBy(() -> resources.noteById(id))
                    .isInstanceOfSatisfying(McpError.class, e ->
                            assertThat(e.getJsonRpcError().code()).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND));
        }

        @Test
        void deletedNoteIsNoLongerFound() {
            service.delete("1");

            assertThatThrownBy(() -> resources.noteById("1")).isInstanceOf(McpError.class);
        }
    }
}
