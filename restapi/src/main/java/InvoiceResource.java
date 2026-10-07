import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/invoices")
public class InvoiceResource {
    //pregled svih faktura (opciono ?status=approved|rejected|failed|pending)
    //pregled jedne fakture po id-u
    private InvoiceQueryService service;

    public InvoiceResource() {
        service = new InvoiceQueryService();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> getInvoices(@QueryParam("status") String status) {
        return service.pregledFaktura(status);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInvoiceById(@PathParam("id") String id) {
        Map<String, Object> invoice = service.getFakturaPoId(id);
        if (invoice != null) {
            return Response.status(200).entity(invoice).build();
        } else {
            return Response.status(404).build();
        }
    }
}
