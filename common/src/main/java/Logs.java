
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class Logs {

    static {
        try (InputStream in = Logs.class.getResourceAsStream("/logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().readConfiguration(in);
            }
        } catch (IOException e) {
            System.err.println("Failed to load logging.properties: " + e.getMessage());
        }
    }

    public static Logger get(Class<?> owner) {
        return Logger.getLogger(owner.getSimpleName());
    }

    private Logs() {
    }
}
