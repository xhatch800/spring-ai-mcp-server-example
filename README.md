# spring-ai-mcp-server-example

A [Model Context Protocol](https://modelcontextprotocol.io) server built with **Spring Boot 3.5** and **Spring AI 1.1**, talking over **stdio** so it can be plugged into Claude Desktop. It exposes an in-memory "Notes" service through the following:

| Primitive | Name | What it does |
|---|---|---|
| Tool | `add_note(title, body, tags)` | Create a note |
| Tool | `search_notes(query)` | Case-insensitive match on title, body or tag |
| Tool | `delete_note(id)` | Delete a note by id |
| Resource | `notes://all` | JSON list of all notes |
| Resource | `notes://{id}` | One note (URI template); unknown id is a `resource not found` error |
| Resource | `notes://stats` | Plain-text count and top tags |
| Prompt | `summarize_notes(tag?)` | One user message asking for a summary, notes inlined |
| Prompt | `weekly_review` | Multi-message weekly-review setup using notes from the last 7 days |

## Requirements

* **JDK 21**

## Build and run

```bash
./gradlew clean build
```
then...
```bash
java -jar build/libs/mcp-notes-server.jar
```

## Claude Desktop configuration

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (create it if missing; if `mcpServers` exists add the `notes` entry).   Claude desktop restart needed.

Example:

```json
{
  "mcpServers": {
    "notes": {
      "command": "/Users/jsmith/.sdkman/candidates/java/21.0.12-oracle/bin/java",
      "args": [
        "-Dlogging.file.name=/Users/jsmith/dev/spring-ai-mcp-server-example/logs/mcp-server.log",
        "-jar",
        "/Users/jsmith/dev/spring-ai-mcp-server-example/build/libs/mcp-notes-server.jar"
      ]
    }
  }
}
```

Notes:

## Verify with MCP Inspector

```bash
npx @modelcontextprotocol/inspector java -jar build/libs/mcp-notes-server.jar
```

## Claude Desktop Tests

1. **Tools (add):** "Add a note titled 'Book flights' with the body 'Compare prices for March' and the tags travel and todo."
2. **Tools (search + delete):** "Search my notes for 'spring', then delete the one about Groceries."
3. **Resources:** "Read `notes://stats` and tell me which tags I use most", or "Open `notes://2` and explain what that note says." 
4. **Prompt with an argument:**  `summarize_notes` prompt, send with `tag` to `mcp`
5. **Prompt workflow:**  `weekly_review` prompt and let Claude walk  through the review of the last 7 days of notes.
