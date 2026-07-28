package gg.vape.service;

import java.nio.file.Path;

public record ServiceConfig(String bindAddress, int httpPort, int zeusPort, Path dataFile) {
    public static ServiceConfig fromEnvironment() {
        String bindAddress = value("VAPE_BIND_ADDRESS", "127.0.0.1");
        int httpPort = integer("VAPE_HTTP_PORT", 8080);
        int zeusPort = integer("VAPE_ZEUS_PORT", 8091);
        Path dataFile = Path.of(value("VAPE_DATA_FILE", "data/vape-service.json"));
        return new ServiceConfig(bindAddress, httpPort, zeusPort, dataFile);
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int integer(String name, int fallback) {
        return Integer.parseInt(value(name, Integer.toString(fallback)));
    }
}
