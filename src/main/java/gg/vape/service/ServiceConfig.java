package gg.vape.service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record ServiceConfig(String bindAddress, int httpPort, int zeusPort, Path dataFile) {
    public static ServiceConfig fromEnvironment() {
        return fromArguments(new String[0]);
    }

    public static ServiceConfig fromArguments(String[] args) {
        return fromArguments(args, System.getenv());
    }

    static ServiceConfig fromArguments(String[] args, Map<String, String> environment) {
        Map<String, String> options = parseOptions(args);
        String bindAddress = option(options, "bind-address", environment, "VAPE_BIND_ADDRESS", "127.0.0.1");
        int httpPort = port(option(options, "http-port", environment, "VAPE_HTTP_PORT", "8080"), "http-port");
        int zeusPort = port(option(options, "zeus-port", environment, "VAPE_ZEUS_PORT", "8091"), "zeus-port");
        Path dataFile = Path.of(option(options, "data-file", environment, "VAPE_DATA_FILE", "data/vape-service.json"));
        return new ServiceConfig(bindAddress, httpPort, zeusPort, dataFile);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }

            String option = argument.substring(2);
            String value;
            int separator = option.indexOf('=');
            if (separator >= 0) {
                value = option.substring(separator + 1);
                option = option.substring(0, separator);
            } else {
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for --" + option);
                }
                value = args[++index];
            }

            if (!isSupported(option)) {
                throw new IllegalArgumentException("Unknown option: --" + option);
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("Empty value for --" + option);
            }
            if (options.put(option, value) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + option);
            }
        }
        return options;
    }

    private static boolean isSupported(String option) {
        return switch (option) {
            case "bind-address", "http-port", "zeus-port", "data-file" -> true;
            default -> false;
        };
    }

    private static String option(Map<String, String> options, String option, Map<String, String> environment,
                                 String environmentName, String fallback) {
        String optionValue = options.get(option);
        if (optionValue != null) {
            return optionValue;
        }
        String value = environment.get(environmentName);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int port(String value, String option) {
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid value for --" + option + ": " + value);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Value for --" + option + " must be between 1 and 65535");
        }
        return port;
    }
}
