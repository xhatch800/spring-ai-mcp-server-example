package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end protocol test: the official MCP client drives the real built jar over stdio.
 * (The SDK ships no in-process server transport, so this is the practical equivalent.)
 * One server is shared by every test; tests that write clean up after themselves and
 * assertions about shared state only look at the seeded notes, so order does not matter.
 */
class McpProtocolTest {

    private static McpSyncClient client;
    private static InitializeResult initResult;

    @BeforeAll
    static void startServer(@TempDir Path logs) {
        List<String> command = TestSupport.serverCommand(logs.resolve("mcp-server.log"));
        ServerParameters params = new ServerParameters.Builder(command.get(0))
                .args(command.subList(1, command.size()))
                .build();
        client = McpClient.sync(new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        initResult = client.initialize();
    }

    @AfterAll
    static void stopServer() {
        if (client != null) {
            client.close();
        }
    }

    private static String text(CallToolResult result) {
        return TestSupport.text(result);
    }

    @Test
    void initializeReportsServerInfoCapabilitiesAndInstructions() {
        assertThat(initResult.serverInfo().name()).isEqualTo("notes-mcp-server");
        assertThat(initResult.serverInfo().version()).isEqualTo("0.0.1");
        assertThat(initResult.instructions()).containsIgnoringCase("in-memory notes service");
        assertThat(initResult.capabilities().tools()).isNotNull();
        assertThat(initResult.capabilities().resources()).isNotNull();
        assertThat(initResult.capabilities().prompts()).isNotNull();
    }

    @Test
    void toolsList() {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder("add_note", "search_notes", "delete_note");
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.description()).isNotBlank();
            assertThat(t.inputSchema().type()).isEqualTo("object");
        });
    }

    @Test
    void toolsCallAddSearchAndDeleteRoundTrip() throws Exception {
        CallToolResult added = client.callTool(new CallToolRequest("add_note",
                Map.of("title", "Protocol test note", "body", "created over stdio", "tags", List.of("Proto", "e2e"))));
        assertThat(added.isError()).isFalse();
        JsonNode note = TestSupport.MAPPER.readTree(text(added).substring("Note added: ".length()));
        String id = note.get("id").asText();
        try {
            assertThat(note.get("tags")).extracting(JsonNode::asText).containsExactly("proto", "e2e");
            // createdAt must be an ISO-8601 string, not a numeric timestamp.
            assertThat(note.get("createdAt").isTextual()).isTrue();

            CallToolResult found = client.callTool(new CallToolRequest("search_notes", Map.of("query", "PROTO")));
            assertThat(found.isError()).isFalse();
            assertThat(text(found)).contains("Protocol test note");

            JsonNode viaResource = TestSupport.MAPPER.readTree(TestSupport.text(
                    client.readResource(new ReadResourceRequest("notes://" + id))));
            assertThat(viaResource.get("title").asText()).isEqualTo("Protocol test note");
        } finally {
            CallToolResult deleted = client.callTool(new CallToolRequest("delete_note", Map.of("id", id)));
            assertThat(deleted.isError()).isFalse();
            assertThat(text(deleted)).startsWith("Deleted note " + id);
        }
        assertThat(client.callTool(new CallToolRequest("delete_note", Map.of("id", id))).isError()).isTrue();
    }

    @Test
    void toolsCallReportsInvalidInputAsErrorResults() {
        CallToolResult blankTitle = client.callTool(
                new CallToolRequest("add_note", Map.of("title", "  ", "body", "x")));
        assertThat(blankTitle.isError()).isTrue();
        assertThat(text(blankTitle)).isEqualTo("Error: Title must not be blank.");

        CallToolResult blankQuery = client.callTool(new CallToolRequest("search_notes", Map.of("query", " ")));
        assertThat(blankQuery.isError()).isTrue();
        assertThat(text(blankQuery)).isEqualTo("Error: Search query must not be blank.");

        CallToolResult unknown = client.callTool(new CallToolRequest("delete_note", Map.of("id", "does-not-exist")));
        assertThat(unknown.isError()).isTrue();
        assertThat(text(unknown)).contains("No note found with id 'does-not-exist'");
    }

    @Test
    void resourcesList() {
        List<Resource> resources = client.listResources().resources();

        assertThat(resources).extracting(Resource::uri).containsExactlyInAnyOrder("notes://all", "notes://stats");
        assertThat(client.listResourceTemplates().resourceTemplates())
                .extracting(t -> t.uriTemplate()).containsExactly("notes://{id}");
    }

    @Test
    void resourcesReadAll() throws Exception {
        var result = client.readResource(new ReadResourceRequest("notes://all"));

        JsonNode array = TestSupport.MAPPER.readTree(TestSupport.text(result));
        assertThat(array.isArray()).isTrue();
        assertThat(array).extracting(n -> n.get("title").asText())
                .contains("Welcome to MCP notes", "Spring AI MCP annotations", "Groceries");
    }

    @Test
    void resourcesReadStats() {
        String stats = TestSupport.text(client.readResource(new ReadResourceRequest("notes://stats")));

        assertThat(stats).startsWith("Notes: ").contains("Top tags: ");
    }

    @Test
    void resourcesReadByIdUsesTheTemplate() throws Exception {
        JsonNode note = TestSupport.MAPPER.readTree(
                TestSupport.text(client.readResource(new ReadResourceRequest("notes://1"))));

        assertThat(note.get("id").asText()).isEqualTo("1");
        assertThat(note.get("title").asText()).isEqualTo("Welcome to MCP notes");
    }

    @Test
    void resourcesReadUnknownIdFailsWithAClearError() {
        assertThatThrownBy(() -> client.readResource(new ReadResourceRequest("notes://424242")))
                .hasMessageContaining("No note found with id '424242'");
    }

    @Test
    void promptsList() {
        List<Prompt> prompts = client.listPrompts().prompts();

        assertThat(prompts).extracting(Prompt::name).containsExactlyInAnyOrder("summarize_notes", "weekly_review");
        Prompt summarize = prompts.stream().filter(p -> p.name().equals("summarize_notes")).findFirst().orElseThrow();
        assertThat(summarize.arguments()).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("tag");
            assertThat(a.required()).isFalse();
        });
    }

    @Test
    void promptsGetSummarizeWithAndWithoutTag() {
        GetPromptResult all = client.getPrompt(new GetPromptRequest("summarize_notes", Map.of()));
        assertThat(all.messages()).hasSize(1);
        assertThat(all.messages().get(0).role()).isEqualTo(Role.USER);
        assertThat(TestSupport.text(all.messages().get(0))).contains("all of my notes", "Groceries");

        GetPromptResult tagged = client.getPrompt(new GetPromptRequest("summarize_notes", Map.of("tag", "personal")));
        String text = TestSupport.text(tagged.messages().get(0));
        assertThat(text).contains("tagged \"personal\"", "Groceries").doesNotContain("Welcome to MCP notes");
    }

    @Test
    void promptsGetWeeklyReviewIsMultiMessage() {
        GetPromptResult result = client.getPrompt(new GetPromptRequest("weekly_review", Map.of()));

        assertThat(result.messages().stream().map(m -> m.role()).collect(Collectors.toList()))
                .containsExactly(Role.USER, Role.ASSISTANT, Role.USER);
        assertThat(TestSupport.texts(result).get(2)).contains("notes from the last 7 days");
    }
}
