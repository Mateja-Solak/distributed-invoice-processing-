public final class Topics {

    //imena MQ topicsa
    public static final String VALIDATED = "invoices.validated";
    public static final String REJECTED = "invoices.rejected";
    public static final String ENRICHED = "invoices.enriched";
    public static final String RETRY = "invoices.retry";
    public static final String FAILED = "invoices.failed";
    public static final String PENDING_APPROVAL = "invoices.pending_approval";
    public static final String APPROVED = "invoices.approved";

    public static final String[] ALL = {
            VALIDATED, REJECTED, ENRICHED, RETRY, FAILED, PENDING_APPROVAL, APPROVED
    };

    private Topics() {
    }
}
