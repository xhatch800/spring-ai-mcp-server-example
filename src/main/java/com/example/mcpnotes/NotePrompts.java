package com.example.mcpnotes;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

@Component
public class NotePrompts {

    static final Duration REVIEW_WINDOW = Duration.ofDays(7);

    private final NoteService noteService;

    public NotePrompts(NoteService noteService) {
        this.noteService = noteService;
    }

    @McpPrompt(name = "summarize_notes",
            description = "Ask for a summary of the notes, optionally limited to one tag. The notes are inlined.")
    public GetPromptResult summarizeNotes(
            @McpArg(name = "tag", description = "Only summarize notes with this tag. Omit to summarize all notes.",
                    required = false) String tag) {
        boolean filtered = tag != null && !tag.isBlank();
        List<Note> notes = noteService.listByTag(tag);
        String scope = filtered ? "the notes tagged \"" + tag.strip() + "\"" : "all of my notes";
        String text = "Please summarize " + scope + " below. Group related items, "
                + "highlight anything that looks actionable, and keep it brief.\n\n" + format(notes);
        return new GetPromptResult("Summary of " + scope, List.of(user(text)));
    }

    @McpPrompt(name = "weekly_review",
            description = "Set up a weekly-review workflow over the notes created in the last 7 days.")
    public GetPromptResult weeklyReview() {
        List<Note> recent = noteService.createdWithin(REVIEW_WINDOW);
        return new GetPromptResult("Weekly review of my notes", List.of(
                user("I want to run a weekly review of my notes. Walk me through it step by step: "
                        + "1) what I captured this week, 2) open loops and follow-ups, "
                        + "3) what to drop, 4) priorities for next week. Ask me one question at a time."),
                new PromptMessage(Role.ASSISTANT, new TextContent(
                        "Happy to. I will look at the notes from the last 7 days first, then guide you "
                        + "through each step. Share the notes when you are ready.")),
                user("Here are my notes from the last 7 days:\n\n" + format(recent)
                        + "\n\nStart the review.")));
    }

    private static PromptMessage user(String text) {
        return new PromptMessage(Role.USER, new TextContent(text));
    }

    private static String format(List<Note> notes) {
        if (notes.isEmpty()) {
            return "(no notes)";
        }
        return notes.stream()
                .map(n -> "- [" + n.id() + "] " + n.title() + " (tags: "
                        + (n.tags().isEmpty() ? "none" : String.join(", ", n.tags())) + ", created "
                        + n.createdAt() + ")\n  " + n.body())
                .collect(Collectors.joining("\n"));
    }
}
