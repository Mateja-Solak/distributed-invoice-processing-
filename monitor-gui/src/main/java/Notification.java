
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload UDP multicast notifikacije koju salje Aggregator servis. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Notification {

    private String invoiceId;
    private String clientName;
    private Double totalBAM;
    private String status;
    private String timestamp;

    public String getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(String invoiceId) {
        this.invoiceId = invoiceId;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Double getTotalBAM() {
        return totalBAM;
    }

    public void setTotalBAM(Double totalBAM) {
        this.totalBAM = totalBAM;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
