package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

/**
 * Boots the real application context. The stdio transport is switched off so the test JVM's
 * System.in/System.out are left alone; the annotation scanner still builds every specification.
 */
@SpringBootTest(properties = "spring.ai.mcp.server.stdio=false")
class McpServerContextTest {

    static final Instant FIXED = Instant.parse("2026-03-01T08:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    Environment environment;
    @Autowired
    NoteService noteService;
    @Autowired
    McpServerProperties serverProperties;
    @Autowired
    List<SyncToolSpecification> toolSpecs;
    @Autowired
    List<SyncResourceSpecification> resourceSpecs;
    @Autowired
    List<SyncResourceTemplateSpecification> resourceTemplateSpecs;
    @Autowired
    List<SyncPromptSpecification> promptSpecs;

    private Tool tool(String name) {
        return toolSpecs.stream().map(SyncToolSpecification::tool)
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private Prompt prompt(String name) {
        return promptSpecs.stream().map(SyncPromptSpecification::prompt)
                .filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void contextLoadsAsANonWebApplication() {
        assertThat(context).isNotNull();
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
    }

    @Test
    void loggingIsConfiguredForFileOnly() {
        assertThat(environment.getProperty("logging.pattern.console")).isEmpty();
        assertThat(environment.getProperty("logging.file.name")).isNotBlank();
    }

    @Test
    void serverIdentityMatchesConfiguration() {
        assertThat(serverProperties.getName()).isEqualTo("notes-mcp-server");
        assertThat(serverProperties.getVersion()).isEqualTo("0.0.1");
        assertThat(serverProperties.getType()).isEqualTo(McpServerProperties.ApiType.SYNC);
        assertThat(serverProperties.getInstructions()).contains("notes", "summarize_notes", "weekly_review");
    }

    @Test
    void servicesAreSeededUsingTheInjectedClock() {
        assertThat(noteService.list()).hasSize(3);
        assertThat(noteService.list().get(2).createdAt()).isEqualTo(FIXED);
    }

    @Test
    void exactlyTheThreeToolsAreRegistered() {
        assertThat(toolSpecs).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("add_note", "search_notes", "delete_note");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addNoteSchemaDescribesAllParameters() {
        Tool tool = tool("add_note");

        assertThat(tool.description()).contains("Create a new note");
        assertThat(tool.inputSchema().type()).isEqualTo("object");
        Map<String, Object> props = tool.inputSchema().properties();
        assertThat(props).containsOnlyKeys("title", "body", "tags");
        assertThat((Map<String, Object>) props.get("title")).containsEntry("type", "string");
        assertThat((Map<String, Object>) props.get("body")).containsEntry("type", "string");
        Map<String, Object> tags = (Map<String, Object>) props.get("tags");
        assertThat(tags).containsEntry("type", "array");
        assertThat(((Map<String, Object>) tags.get("items"))).containsEntry("type", "string");
        props.forEach((name, schema) ->
                assertThat(((Map<String, Object>) schema).get("description")).as(name + " description").isNotNull());
        assertThat(tool.inputSchema().required()).containsExactlyInAnyOrder("title", "body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAndDeleteSchemas() {
        Tool search = tool("search_notes");
        assertThat(search.description()).contains("case-insensitive");
        assertThat(search.inputSchema().properties()).containsOnlyKeys("query");
        assertThat(((Map<String, Object>) search.inputSchema().properties().get("query")).get("description"))
                .asString().contains("Must not be blank");
        assertThat(search.inputSchema().required()).containsExactly("query");

        Tool delete = tool("delete_note");
        assertThat(delete.description()).contains("Permanently delete");
        assertThat(delete.inputSchema().properties()).containsOnlyKeys("id");
        assertThat(((Map<String, Object>) delete.inputSchema().properties().get("id")).get("description"))
                .asString().isNotBlank();
        assertThat(delete.inputSchema().required()).containsExactly("id");
    }

    @Test
    void staticResourcesAreRegisteredWithUriNameAndMimeType() {
        assertThat(resourceSpecs).extracting(s -> s.resource().uri())
                .containsExactlyInAnyOrder("notes://all", "notes://stats");
        resourceSpecs.forEach(s -> assertThat(s.resource().description()).isNotBlank());

        var all = resourceSpecs.stream().map(SyncResourceSpecification::resource)
                .filter(r -> r.uri().equals("notes://all")).findFirst().orElseThrow();
        assertThat(all.name()).isEqualTo("all_notes");
        assertThat(all.mimeType()).isEqualTo("application/json");

        var stats = resourceSpecs.stream().map(SyncResourceSpecification::resource)
                .filter(r -> r.uri().equals("notes://stats")).findFirst().orElseThrow();
        assertThat(stats.name()).isEqualTo("notes_stats");
        assertThat(stats.mimeType()).isEqualTo("text/plain");
    }

    @Test
    void noteByIdIsRegisteredAsAUriTemplate() {
        assertThat(resourceTemplateSpecs).hasSize(1);
        var template = resourceTemplateSpecs.get(0).resourceTemplate();
        assertThat(template.uriTemplate()).isEqualTo("notes://{id}");
        assertThat(template.name()).isEqualTo("note_by_id");
        assertThat(template.mimeType()).isEqualTo("application/json");
        assertThat(template.description()).contains("single note");
    }

    @Test
    void exactlyTheTwoPromptsAreRegistered() {
        assertThat(promptSpecs).extracting(s -> s.prompt().name())
                .containsExactlyInAnyOrder("summarize_notes", "weekly_review");
    }

    @Test
    void summarizeNotesHasOneOptionalTagArgument() {
        Prompt prompt = prompt("summarize_notes");

        assertThat(prompt.description()).contains("summary of the notes");
        assertThat(prompt.arguments()).hasSize(1);
        PromptArgument tag = prompt.arguments().get(0);
        assertThat(tag.name()).isEqualTo("tag");
        assertThat(tag.description()).contains("Only summarize notes with this tag");
        assertThat(tag.required()).isFalse();
    }

    @Test
    void weeklyReviewTakesNoArguments() {
        Prompt prompt = prompt("weekly_review");

        assertThat(prompt.description()).contains("weekly-review");
        assertThat(prompt.arguments()).isNullOrEmpty();
    }

    @Test
    void clockConfigProvidesAUtcSystemClockByDefault() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
