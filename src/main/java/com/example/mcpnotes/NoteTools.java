package com.example.mcpnotes;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class NoteTools {

    private final NoteService noteService;
    private final NoteJson json;

    public NoteTools(NoteService noteService, NoteJson json) {
        this.noteService = noteService;
        this.json = json;
    }

    @McpTool(name = "add_note",
            description = "Create a new note. Returns the saved note as JSON, including its generated id.")
    public CallToolResult addNote(
            @McpToolParam(description = "Short title of the note. Must not be blank.", required = true) String title,
            @McpToolParam(description = "Full text of the note.", required = true) String body,
            @McpToolParam(description = "Optional tags for the note, for example [\"work\", \"idea\"]. "
                    + "Tags are lower-cased and de-duplicated; blank tags are ignored.", required = false)
            List<String> tags) {
        try {
            Note note = noteService.add(title, body, tags);
            return ok("Note added: " + json.write(note));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @McpTool(name = "search_notes",
            description = "Find notes whose title, body or any tag contains the query (case-insensitive). "
                    + "Returns a JSON array of matching notes, oldest first.")
    public CallToolResult searchNotes(
            @McpToolParam(description = "Text to look for in titles, bodies and tags. Must not be blank.",
                    required = true) String query) {
        try {
            List<Note> matches = noteService.search(query);
            if (matches.isEmpty()) {
                return ok("No notes matched '" + query.strip() + "'.");
            }
            return ok(json.write(matches));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @McpTool(name = "delete_note",
            description = "Permanently delete a note by id. Use search_notes or the notes://all resource to find ids.")
    public CallToolResult deleteNote(
            @McpToolParam(description = "Id of the note to delete, for example \"2\".", required = true) String id) {
        if (id == null || id.isBlank()) {
            return error("Note id must not be blank.");
        }
        return noteService.find(id)
                .filter(note -> noteService.delete(id))
                .map(note -> ok("Deleted note " + note.id() + " (\"" + note.title() + "\")."))
                .orElseGet(() -> error("No note found with id '" + id.strip()
                        + "'. Use search_notes or the notes://all resource to find valid ids."));
    }

    private static CallToolResult ok(String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent("Error: " + text).isError(true).build();
    }
}
