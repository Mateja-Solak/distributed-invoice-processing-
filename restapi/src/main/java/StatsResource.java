import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/stats")
public class StatsResource {
    //pregled agregatnih statistika nad svim fakturama (ukupno faktura, ukupno BAM, prosjecan iznos, broj pending)
    private InvoiceQueryService service;

    public StatsResource() {
        service = new InvoiceQueryService();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getStats() {
        return service.getStats();
    }
}
