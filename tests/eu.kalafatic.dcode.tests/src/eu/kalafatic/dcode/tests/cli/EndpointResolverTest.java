package eu.kalafatic.dcode.tests.cli;

import eu.kalafatic.dcode.cli.EndpointResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Endpoint discovery for the thin CLI: the persisted actual endpoint
 * ({@code server-endpoint.json}) wins, then the configured {@code httpPort}, then the
 * family default port.
 */
class EndpointResolverTest {

    @TempDir
    Path temp;

    @Test
    void prefersPersistedEndpointThenConfigThenDefault() throws Exception {
        Files.writeString(temp.resolve("server-endpoint.json"),
                "{\"host\":\"127.0.0.1\",\"port\":48123,\"pid\":1,\"boundAt\":0}");
        Files.writeString(temp.resolve("dcode-config.json"), "{\"httpPort\": 48099}");
        assertEquals(List.of("http://127.0.0.1:48123",
                        "http://127.0.0.1:48099", "http://127.0.0.1:48080"),
                EndpointResolver.candidates(temp));
    }

    @Test
    void fallsBackToConfigThenDefaultWithoutEndpointFile() throws Exception {
        Files.writeString(temp.resolve("dcode-config.json"), "{\"httpPort\": 48099}");
        assertEquals(List.of("http://127.0.0.1:48099", "http://127.0.0.1:48080"),
                EndpointResolver.candidates(temp));
    }

    @Test
    void usesDefaultWhenNothingPersisted() {
        assertEquals(List.of("http://127.0.0.1:48080"),
                EndpointResolver.candidates(temp));
    }

    @Test
    void malformedEndpointFileIsIgnored() throws Exception {
        Files.writeString(temp.resolve("server-endpoint.json"), "not-json");
        assertEquals(List.of("http://127.0.0.1:48080"),
                EndpointResolver.candidates(temp));
    }

    @Test
    void endpointFileWithNoPortIsIgnored() throws Exception {
        Files.writeString(temp.resolve("server-endpoint.json"),
                "{\"host\":\"127.0.0.1\"}");
        assertEquals(List.of("http://127.0.0.1:48080"),
                EndpointResolver.candidates(temp));
    }
}
