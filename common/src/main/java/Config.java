
public final class Config {

    public static String mqUrl() {
        return env("MQ_URL", "amqp://guest:guest@localhost:5672/%2F");
    }

    public static String redisHost() {
        return env("REDIS_HOST", "localhost");
    }

    public static int redisPort() {
        return envInt("REDIS_PORT", 6379);
    }

    public static String inboxDir() {
        return env("WATCHER_INBOX", "invoices/inbox");
    }

    public static String processedDir() {
        return env("WATCHER_PROCESSED", "invoices/processed");
    }

    public static String errorDir() {
        return env("WATCHER_ERROR", "invoices/error");
    }

    public static String parserHost() {
        return env("PARSER_HOST", "localhost");
    }

    public static int parserPort() {
        return envInt("PARSER_PORT", 5001);
    }

    public static String rmiHost() {
        return env("RMI_HOST", "localhost");
    }

    public static int rmiPort() {
        return envInt("RMI_PORT", 1099);
    }

    public static int retryDelaySeconds() {
        return envInt("RETRY_DELAY_SECONDS", 30);
    }

    public static int retryMax() {
        return envInt("RETRY_MAX", 3);
    }

    public static int httpPort() {
        return envInt("HTTP_PORT", 8080);
    }

    public static String multicastGroup() {
        return env("MULTICAST_GROUP", "239.0.0.1");
    }

    public static int multicastPort() {
        return envInt("MULTICAST_PORT", 4446);
    }

    public static String exchangeApiBase() {
        return env("EXCHANGE_API", "https://open.er-api.com/v6/latest/BAM");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int envInt(String key, int def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
    }

    private Config() {
    }
}
