package gg.vape.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceConfigTest {
    @Test
    void commandLineOptionsOverrideEnvironment() {
        ServiceConfig config = ServiceConfig.fromArguments(new String[]{
                "--bind-address=0.0.0.0",
                "--http-port", "9080",
                "--zeus-port", "9091",
                "--data-file", "var/service.json"
        }, Map.of(
                "VAPE_BIND_ADDRESS", "127.0.0.1",
                "VAPE_HTTP_PORT", "8080",
                "VAPE_ZEUS_PORT", "8091",
                "VAPE_DATA_FILE", "data/vape-service.json"
        ));

        assertEquals("0.0.0.0", config.bindAddress());
        assertEquals(9080, config.httpPort());
        assertEquals(9091, config.zeusPort());
        assertEquals(Path.of("var/service.json"), config.dataFile());
    }

    @Test
    void usesEnvironmentAndDefaultsWhenOptionsAreAbsent() {
        ServiceConfig config = ServiceConfig.fromArguments(new String[0], Map.of("VAPE_HTTP_PORT", "9080"));

        assertEquals("127.0.0.1", config.bindAddress());
        assertEquals(9080, config.httpPort());
        assertEquals(8091, config.zeusPort());
        assertEquals(Path.of("data/vape-service.json"), config.dataFile());
    }

    @Test
    void rejectsUnknownMissingAndInvalidOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceConfig.fromArguments(new String[]{"--unknown", "value"}, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceConfig.fromArguments(new String[]{"--http-port"}, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceConfig.fromArguments(new String[]{"--http-port", "70000"}, Map.of()));
    }
}
