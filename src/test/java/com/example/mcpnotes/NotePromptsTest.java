package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NotePromptsTest {

    private MutableClock clock;
    private NoteService service;
    private NotePrompts prompts;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = TestSupport.seededService(clock);
        prompts = new NotePrompts(service);
    }

    @Nested
    class SummarizeNotes {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void withoutTagInlinesEveryNote(String tag) {
            GetPromptResult result = prompts.summarizeNotes(tag);

            assertThat(result.messages()).hasSize(1);
            assertThat(result.messages().get(0).role()).isEqualTo(Role.USER);
            String text = TestSupport.text(result.messages().get(0));
            assertThat(text).startsWith("Please summarize all of my notes below.");
            assertThat(text).contains("Welcome to MCP notes", "Spring AI MCP annotations", "Groceries");
            assertThat(text).contains("- [1] Welcome to MCP notes (tags: mcp, intro, created 2026-01-13T10:00:00Z)");
            assertThat(text).contains("Milk, eggs, coffee beans.");
            assertThat(result.description()).isEqualTo("Summary of all of my notes");
        }

        @Test
        void withTagInlinesOnlyMatchingNotes() {
            GetPromptResult result = prompts.summarizeNotes("MCP");

            assertThat(result.messages()).hasSize(1);
            String text = TestSupport.text(result.messages().get(0));
            assertThat(text).startsWith("Please summarize the notes tagged \"MCP\" below.");
            assertThat(text).contains("Welcome to MCP notes", "Spring AI MCP annotations");
            assertThat(text).doesNotContain("Groceries");
            assertThat(result.description()).isEqualTo("Summary of the notes tagged \"MCP\"");
        }

        @Test
        void unknownTagStillProducesAPromptSayingThereAreNoNotes() {
            String text = TestSupport.text(prompts.summarizeNotes("nope").messages().get(0));

            assertThat(text).contains("the notes tagged \"nope\"").endsWith("(no notes)");
        }

        @Test
        void emptyStoreSaysNoNotes() {
            service.list().forEach(n -> service.delete(n.id()));

            assertThat(TestSupport.text(prompts.summarizeNotes(null).messages().get(0))).endsWith("(no notes)");
        }

        @Test
        void untaggedNotesAreShownWithTagsNone() {
            service.add("Loose", "thought", null);

            assertThat(TestSupport.text(prompts.summarizeNotes(null).messages().get(0)))
                    .contains("- [4] Loose (tags: none,");
        }
    }

    @Nested
    class WeeklyReview {

        @Test
        void isAThreeMessageConversationStartingAndEndingWithTheUser() {
            GetPromptResult result = prompts.weeklyReview();

            assertThat(result.messages()).extracting(m -> m.role())
                    .containsExactly(Role.USER, Role.ASSISTANT, Role.USER);
            assertThat(result.description()).isEqualTo("Weekly review of my notes");
        }

        @Test
        void firstMessageSetsUpTheWorkflowSteps() {
            String setup = TestSupport.texts(prompts.weeklyReview()).get(0);

            assertThat(setup).contains("weekly review", "what I captured", "open loops", "what to drop",
                    "priorities for next week", "one question at a time");
        }

        @Test
        void lastMessageInlinesRecentNotesAndAsksToStart() {
            String last = TestSupport.texts(prompts.weeklyReview()).get(2);

            assertThat(last).contains("notes from the last 7 days");
            assertThat(last).contains("Welcome to MCP notes", "Spring AI MCP annotations", "Groceries");
            assertThat(last).endsWith("Start the review.");
        }

        @Test
        void notesOlderThanSevenDaysAreLeftOut() {
            // Seeds are 2 days, 1 day and 0 days old at the start; the window is inclusive of its boundary.
            clock.advance(Duration.ofDays(5));
            assertThat(TestSupport.texts(prompts.weeklyReview()).get(2))
                    .contains("Welcome to MCP notes", "Spring AI MCP annotations", "Groceries");

            clock.advance(Duration.ofDays(1));
            assertThat(TestSupport.texts(prompts.weeklyReview()).get(2))
                    .doesNotContain("Welcome to MCP notes")
                    .contains("Spring AI MCP annotations", "Groceries");

            clock.advance(Duration.ofDays(1));
            assertThat(TestSupport.texts(prompts.weeklyReview()).get(2))
                    .doesNotContain("Welcome to MCP notes", "Spring AI MCP annotations")
                    .contains("Groceries");
        }

        @Test
        void noRecentNotesSaysNoNotes() {
            clock.advance(Duration.ofDays(30));

            assertThat(TestSupport.texts(prompts.weeklyReview()).get(2)).contains("(no notes)");
        }

        @Test
        void assistantMessageIsAnAcknowledgement() {
            List<String> texts = TestSupport.texts(prompts.weeklyReview());

            assertThat(texts.get(1)).startsWith("Happy to.");
        }
    }
}
