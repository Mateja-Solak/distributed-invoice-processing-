import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/clients")
public class ClientResource {
    //pregled sumarnog izvjestaja za klijenta po JIB-u (ukupno BAM, broj faktura, zadnja faktura)
    private InvoiceQueryService service;

    public ClientResource() {
        service = new InvoiceQueryService();
    }

    @GET
    @Path("/{jib}/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getClientSummary(@PathParam("jib") String jib) {
        Map<String, Object> summary = service.getKlijentSummary(jib);
        if (summary != null) {
            return Response.status(200).entity(summary).build();
        } else {
            return Response.status(404).build();
        }
    }
}
