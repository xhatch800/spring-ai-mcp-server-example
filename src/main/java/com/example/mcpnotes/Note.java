package com.example.mcpnotes;

import java.time.Instant;
import java.util.List;

public record Note(String id, String title, String body, List<String> tags, Instant createdAt) {
}
