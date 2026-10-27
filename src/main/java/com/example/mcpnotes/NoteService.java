package com.example.mcpnotes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

@Service
public class NoteService {

    private static final Comparator<Note> BY_AGE_THEN_ID = Comparator
            .comparing(Note::createdAt)
            .thenComparingLong(note -> Long.parseLong(note.id()));

    private final Clock clock;
    private final Map<String, Note> notes = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public NoteService(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    public void seedSamples() {
        Instant now = clock.instant();

        store("Welcome to MCP notes", "This server exposes tools, resources and prompts over MCP.",
                List.of("mcp", "intro"), now.minus(Duration.ofDays(2)));
        store("Spring AI MCP annotations", "Use @McpTool, @McpResource and @McpPrompt on Spring beans.",
                List.of("spring", "mcp"), now.minus(Duration.ofDays(1)));
        store("Groceries", "Milk, eggs, coffee beans.", List.of("personal"), now);
    }

    public Note add(String title, String body, List<String> tags) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title must not be blank.");
        }

        return store(title.strip(), body == null ? "" : body, normalizeTags(tags), clock.instant());
    }

    public List<Note> search(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank.");
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);

        return sorted(note -> contains(note.title(), needle)
                || contains(note.body(), needle)
                || note.tags().stream().anyMatch(tag -> tag.contains(needle)));
    }

    public Optional<Note> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(notes.get(id.strip()));
    }

    public boolean delete(String id) {
        return id != null && notes.remove(id.strip()) != null;
    }

    public List<Note> list() {
        return sorted(note -> true);
    }

    public List<Note> listByTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return list();
        }
        String wanted = tag.strip().toLowerCase(Locale.ROOT);
        return sorted(note -> note.tags().contains(wanted));
    }

    public List<Note> createdWithin(Duration window) {
        Instant cutoff = clock.instant().minus(window);
        return sorted(note -> !note.createdAt().isBefore(cutoff));
    }

    public List<TagCount> topTags(int limit) {
        return notes.values().stream()
                .flatMap(note -> note.tags().stream())
                .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new TagCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(TagCount::count).reversed().thenComparing(TagCount::tag))
                .limit(limit)
                .toList();
    }

    public int count() {
        return notes.size();
    }

    private Note store(String title, String body, List<String> tags, Instant createdAt) {
        String id = String.valueOf(sequence.incrementAndGet());
        Note note = new Note(id, title, body, tags, createdAt);
        notes.put(id, note);
        return note;
    }

    private List<Note> sorted(Predicate<Note> filter) {
        return notes.values().stream().filter(filter).sorted(BY_AGE_THEN_ID).toList();
    }

    private static boolean contains(String text, String lowerCaseNeedle) {
        return text.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                unique.add(tag.strip().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(unique);
    }
}
