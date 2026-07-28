package gg.vape.service.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.vape.service.store.FileStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyHttpServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void servesAndPersistsLegacySettingsContract() throws Exception {
        FileStore store = new FileStore(temporaryDirectory.resolve("state.json"));
        try (LegacyHttpServer server = new LegacyHttpServer("127.0.0.1", 0, store)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpResponse<String> accountResponse = client.send(HttpRequest.newBuilder(
                    base.resolve("/api/v1/0/authenticated")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject accountEnvelope = JsonParser.parseString(accountResponse.body()).getAsJsonObject();
            assertEquals(200, accountResponse.statusCode());
            assertTrue(accountEnvelope.get("successful").getAsBoolean());
            assertEquals(1L, accountEnvelope.getAsJsonObject("data").get("userId").getAsLong());

            String settings = "{\"cache\":true,\"firstRun\":false}";
            HttpResponse<String> saveResponse = client.send(HttpRequest.newBuilder(
                            base.resolve("/api/v1/0/settings/save/global"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(settings)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(settings, saveResponse.body());
        }

        FileStore reloaded = new FileStore(temporaryDirectory.resolve("state.json"));
        assertTrue(reloaded.globalSettings("0").get("cache").getAsBoolean());
    }
}
