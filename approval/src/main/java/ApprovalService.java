import java.util.logging.Logger;

/**
 * Faza 5 - Approval servis. Konzumira invoices.enriched. Za svaku fakturu:
 *   - totalBAM <= 100  -> automatski odobrava, status = "APPROVED", publish na Topics.APPROVED
 *   - totalBAM > 100   -> status = "PENDING_APPROVAL", publish na Topics.PENDING_APPROVAL
 *     (odluku donosi poseban Approval GUI klijent koji slusa PENDING_APPROVAL i sam
 *     objavljuje konacan ishod na Topics.APPROVED ili Topics.REJECTED - to je ODVOJENA
 *     komponenta, ne pise se ovdje)
 *

 */
public class ApprovalService {

    private static final Logger log = Logs.get(ApprovalService.class);


    //postavljanje limitima da je 100
    private static final double AUTO_APPROVE_LIMIT = 100.0;


    private final MessageBroker broker;
    public ApprovalService(MessageBroker broker) {
        this.broker=broker;
    }

    public static void main(String[] args) {
        MessageBroker broker = new MessageBroker();
        new ApprovalService(broker).start();
        log.info("Approval service started, waiting for messages on " + Topics.ENRICHED);
    }

    public void start() {

        broker.subscribe((topic, message) -> onEnriched(JsonUtil.toInvoice(message)), Topics.ENRICHED);
    }

    private void onEnriched(Invoice invoice) {
        //  provjeriti invoice.totalBAM (setovan od strane Enrichment servisa).
        // ako totalBAM <= APPROVAL_THRESHOLD:
        //           - invoice.status = "APPROVED"
        //           - broker.publish(Topics.APPROVED, JsonUtil.toJson(invoice))
        //  ako totalBAM > APPROVAL_THRESHOLD:
        //           - invoice.status = "PENDING_APPROVAL"
        //           - broker.publish(Topics.PENDING_APPROVAL, JsonUtil.toJson(invoice))

        double totalBAM = invoice.totalBAM == null ? 0 : invoice.totalBAM;
        if (totalBAM <= AUTO_APPROVE_LIMIT) {
            invoice.status = "APPROVED";
            broker.publish(Topics.APPROVED, JsonUtil.toJson(invoice));
            log.info("Invoice " + invoice.id + " auto-approved (totalBAM=" + totalBAM + ")");
        } else {
            invoice.status = "PENDING_APPROVAL";
            broker.publish(Topics.PENDING_APPROVAL, JsonUtil.toJson(invoice));
            log.info("Invoice " + invoice.id + " waiting for manual approval (totalBAM=" + totalBAM + ")");
        }
    }

}
