package com.example.mcpnotes;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

final class TestSupport {

    /** Same shape as Spring Boot's auto-configured mapper: ISO-8601 dates. */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private TestSupport() {
    }

    static NoteService seededService(MutableClock clock) {
        NoteService service = new NoteService(clock);
        service.seedSamples();
        return service;
    }

    static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    static String text(ReadResourceResult result) {
        return ((TextResourceContents) result.contents().get(0)).text();
    }

    static String text(PromptMessage message) {
        return ((TextContent) message.content()).text();
    }

    static List<String> texts(GetPromptResult result) {
        return result.messages().stream().map(TestSupport::text).toList();
    }

    /** Command that starts the built jar with its log file redirected into a test-owned directory. */
    static List<String> serverCommand(Path logFile) {
        String jar = System.getProperty("mcp.server.jar");
        if (jar == null) {
            throw new IllegalStateException("System property mcp.server.jar is not set; run the tests through Gradle.");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-Dlogging.file.name=" + logFile, "-jar", jar);
    }
}
