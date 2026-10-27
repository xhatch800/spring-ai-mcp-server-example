package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Starts the real jar and checks that every line on stdout is a JSON-RPC message and nothing else. */
class StdoutHygieneTest {

    private static final List<String> REQUESTS = List.of(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"hygiene-test\",\"version\":\"1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\"}");

    private static final int EXPECTED_RESPONSES = 4;

    @Test
    void stdoutContainsOnlyJsonRpcAndLogsGoToTheFile(@TempDir Path tmp) throws Exception {
        Path logFile = tmp.resolve("logs").resolve("mcp-server.log");
        Process process = new ProcessBuilder(TestSupport.serverCommand(logFile))
                .directory(tmp.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        List<String> stdout = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch responses = new CountDownLatch(EXPECTED_RESPONSES);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    stdout.add(line);
                    responses.countDown();
                }
            } catch (IOException ignored) {
                // stream closed when the process ends
            }
        });
        reader.start();

        try {
            OutputStream stdin = process.getOutputStream();
            for (String request : REQUESTS) {
                stdin.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            }
            stdin.flush();

            assertThat(responses.await(60, TimeUnit.SECONDS)).as("all responses arrived").isTrue();
            stdin.close();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("server exits when stdin closes").isTrue();
        } finally {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(10_000);
        }

        List<String> lines = List.copyOf(stdout);
        assertThat(lines).hasSize(EXPECTED_RESPONSES);
        Set<Integer> ids = lines.stream().map(StdoutHygieneTest::assertJsonRpc).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder(1, 2, 3, 4);

        assertThat(logFile).exists();
        assertThat(Files.readString(logFile)).contains("Started McpNotesApplication");
        assertThat(tmp.resolve("logs")).isDirectory();
    }

    private static int assertJsonRpc(String line) {
        JsonNode node;
        try {
            node = TestSupport.MAPPER.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("stdout line is not JSON: [" + line + "]", e);
        }
        assertThat(node.isObject()).as("JSON object: " + line).isTrue();
        assertThat(node.path("jsonrpc").asText()).as("jsonrpc field: " + line).isEqualTo("2.0");
        assertThat(node.has("result") || node.has("error")).as("is a response: " + line).isTrue();
        return node.get("id").asInt();
    }
}
