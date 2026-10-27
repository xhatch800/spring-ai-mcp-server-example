package com.example.mcpnotes;

import java.util.List;
import java.util.stream.Collectors;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class NoteResources {

    static final String ALL_URI = "notes://all";
    static final String STATS_URI = "notes://stats";
    static final String NOTE_URI_PREFIX = "notes://";
    private static final int TOP_TAGS = 5;

    private final NoteService noteService;
    private final NoteJson json;

    public NoteResources(NoteService noteService, NoteJson json) {
        this.noteService = noteService;
        this.json = json;
    }

    @McpResource(uri = ALL_URI, name = "all_notes", mimeType = "application/json",
            description = "Every note as a JSON array, oldest first.")
    public ReadResourceResult allNotes() {
        return text(ALL_URI, "application/json", json.write(noteService.list()));
    }

    @McpResource(uri = STATS_URI, name = "notes_stats", mimeType = "text/plain",
            description = "Plain-text summary: how many notes exist and the most used tags.")
    public ReadResourceResult stats() {
        List<TagCount> top = noteService.topTags(TOP_TAGS);
        String tags = top.isEmpty()
                ? "none"
                : top.stream().map(t -> t.tag() + " (" + t.count() + ")").collect(Collectors.joining(", "));
        return text(STATS_URI, "text/plain", "Notes: " + noteService.count() + "\nTop tags: " + tags);
    }

    @McpResource(uri = "notes://{id}", name = "note_by_id", mimeType = "application/json",
            description = "A single note as JSON, addressed by id, for example notes://1.")
    public ReadResourceResult noteById(String id) {
        Note note = noteService.find(id).orElseThrow(() -> McpError
                .builder(ErrorCodes.RESOURCE_NOT_FOUND)
                .message("No note found with id '" + id + "'. Read notes://all to list valid ids.")
                .build());
        return text(NOTE_URI_PREFIX + note.id(), "application/json", json.write(note));
    }

    private static ReadResourceResult text(String uri, String mimeType, String body) {
        return new ReadResourceResult(List.of(new TextResourceContents(uri, mimeType, body)));
    }
}
