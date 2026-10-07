import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.net.URI;
import java.util.logging.Logger;

/**
 * Pokrece REST API na JDK HttpServer preko Jersey-a (JAX-RS) i registruje
 * InvoiceResource/ClientResource/StatsResource. Bazna putanja je "/api" da
 * krajnji endpointi ostanu isti kao ranije (/api/invoices, /api/clients/{jib}/summary,
 * /api/stats) - poslovna logika je u InvoiceQueryService, ovdje je samo bootstrap.
 */
public class RestApiApp {

    private static final Logger log = Logs.get(RestApiApp.class);

    public static void main(String[] args) {
        ResourceConfig config = new ResourceConfig();
        config.register(InvoiceResource.class);
        config.register(ClientResource.class);
        config.register(StatsResource.class);
        config.register(JacksonFeature.class);

        int port = Config.httpPort();
        JdkHttpServerFactory.createHttpServer(URI.create("http://0.0.0.0:" + port + "/api/"), config);
        log.info("REST API service started on port " + port);
    }
}
