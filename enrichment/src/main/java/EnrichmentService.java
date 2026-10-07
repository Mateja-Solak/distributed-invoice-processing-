import redis.clients.jedis.JedisPool;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Faza 4 - Enrichment servis. Konzumira invoices.validated, dodaje totalBAM i
 * rateSource, stavlja fakturu na invoices.enriched ili, ako kurs nije dostupan,
 * na invoices.retry.
 *
 * Faza 4b - Retry mehanizam je poseban listener u ovom servisu koji konzumira
 * invoices.retry i koristi ScheduledExecutorService da odlozi ponovni pokusaj
 * (Config.retryDelaySeconds()).
 */
public class EnrichmentService {

    private static final Logger log = Logs.get(EnrichmentService.class);


    private final MessageBroker broker;
    private final RateService rateService;
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(4);
    public EnrichmentService(MessageBroker broker, RateService rateService) {
        this.broker=broker;
        this.rateService=rateService;
    }

    public static void main(String[] args) {

        JedisPool redisPool=RedisClientFactory.create();
        MessageBroker broker=new MessageBroker();
        RateService rateService=new RateService(redisPool);

        EnrichmentService service=new EnrichmentService(broker,rateService);
        service.start();

        log.info("Enrichment service started, waiting for messages on " + Topics.VALIDATED + " and " + Topics.RETRY);

    }

    public void start() {

        broker.subscribe((topic, message) -> onValidated(JsonUtil.toInvoice(message)), Topics.VALIDATED);
        broker.subscribe((topic, message) -> onRetryMessage(JsonUtil.toInvoice(message)), Topics.RETRY);
    }

    private void onValidated(Invoice invoice) {
        //  pozovi rateService.fetchRate(invoice.currency).
        //       - ako null: invoice.retryCount = 0, publish na Topics.RETRY.
        //       - ako uspije: enrichAndPublish(invoice, result).
        RateService.RateResult result=rateService.fetchRate(invoice.currency);
        if(result==null)
        {
            log.warning("Rate for " + invoice.currency + " not available, invoice " + invoice.id + " going to retry");
            invoice.retryCount = 0;
            broker.publish(Topics.RETRY, JsonUtil.toJson(invoice));
            return;
        }
        enrichAndPublish(invoice,result);

    }

    private void onRetryMessage(Invoice invoice) {
        //  scheduler.schedule(() -> attemptRetry(invoice), Config.retryDelaySeconds(),
        //       TimeUnit.SECONDS) - ovo je "cekanje N sekundi" iz spec-a (Faza 4b).
        int delay = Config.retryDelaySeconds();
        log.info("Invoice " + invoice.id + " waiting " + delay + "s before retry (retryCount="
                + invoice.retryCount + ")");
        scheduler.schedule(() -> attemptRetry(invoice), delay, TimeUnit.SECONDS);
    }

    private void attemptRetry(Invoice invoice) {
        // onovo rateService.fetchRate(invoice.currency).
        //       - ako uspije: enrichAndPublish(invoice, result).
        //       - ako ne uspije i retryCount >= Config.retryMax(): status = "FAILED",
        //         failReason = "RATE_UNAVAILABLE", publish na Topics.FAILED.
        //       - ako ne uspije i retryCount < max: uvecaj retryCount, publish na
        //         Topics.RETRY (ide ponovo kroz onRetryMessage -> ceka opet).
        RateService.RateResult result = rateService.fetchRate(invoice.currency);
        if (result != null) {
            log.info("Retry succeeded for invoice " + invoice.id);
            enrichAndPublish(invoice, result);
            return;
        }

        int retryCount = invoice.retryCount == null ? 0 : invoice.retryCount;
        if (retryCount >= Config.retryMax()) {
            invoice.status = "FAILED";
            invoice.failReason = "RATE_UNAVAILABLE";
            broker.publish(Topics.FAILED, JsonUtil.toJson(invoice));
            log.severe("Invoice " + invoice.id + " permanently failed: RATE_UNAVAILABLE");
        } else {
            invoice.retryCount = retryCount + 1;
            broker.publish(Topics.RETRY, JsonUtil.toJson(invoice));
            log.warning("Retry failed for invoice " + invoice.id + ", retryCount=" + invoice.retryCount);
        }
    }

    private void enrichAndPublish(Invoice invoice, RateService.RateResult result) {
        //  totalBAM = invoice.totalAmount() / result.rate() (zaokruzi na 2 decimale),
        //       rateSource = result.source(), status = "ENRICHED", publish na Topics.ENRICHED.
        double totalBAM = invoice.totalAmount() / result.rate();
        invoice.totalBAM = Math.round(totalBAM * 100.0) / 100.0;
        invoice.rateSource = result.source();
        invoice.status = "ENRICHED";
        broker.publish(Topics.ENRICHED, JsonUtil.toJson(invoice));
        log.info("Invoice " + invoice.id + " enriched: totalBAM=" + invoice.totalBAM
                + " (source=" + result.source() + ")");
    }
}
