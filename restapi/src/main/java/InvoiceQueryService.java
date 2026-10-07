import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Citanje faktura/klijenata/statistike iz Redis-a za REST API. Ne slusa MQ, ne
 * poziva druge servise ni RMI - isto ogranicenje kao stari RestApiService, samo
 * je logika ovdje izdvojena iz HTTP handlera da bi je resurs-klase (InvoiceResource,
 * ClientResource, StatsResource) mogle pozivati direktno.
 *
 * Redis strukture koje puni AggregatorService (vidi aggregator/AggregatorService.java) -
 * ovdje se samo cita, nista ne upisuje:
 *   - invoice:{id}   (STRING) -> kompletan JSON zapis fakture
 *   - invoices:all   (SET)    -> svi poznati invoice id-jevi
 *   - client:{jib}   (HASH)   -> polja totalBAM, count, lastInvoiceId, lastUpdated
 */
public class InvoiceQueryService {

    private final JedisPool redisPool;

    public InvoiceQueryService() {
        this.redisPool = RedisClientFactory.create();
    }

    /**
     * Sve fakture, opciono filtrirane po statusu (query vrijednosti su malim
     * slovima: approved/rejected/failed/pending - "pending" se mapira na
     * "PENDING_APPROVAL").
     */
    public List<Map<String, Object>> pregledFaktura(String status) {
        List<Map<String, Object>> invoices = loadAllInvoices();
        if (status != null) {
            invoices = invoices.stream().filter(inv -> statusMatches(status, inv)).toList();
        }
        return invoices;
    }

    public Map<String, Object> getFakturaPoId(String id) {
        String json = getInvoiceJson(id);
        return json == null ? null : JsonUtil.toMap(json);
    }

    /**
     * Sumarni izvjestaj za klijenta po JIB-u, ili null ako klijent nema
     * odobrenih faktura (nikad nije stigao na Topics.APPROVED).
     */
    public Map<String, Object> getKlijentSummary(String jib) {
        Map<String, String> hash;
        try (var jedis = redisPool.getResource()) {
            hash = jedis.hgetAll("client:" + jib);
        }
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jib", jib);
        response.put("totalBAM", parseDoubleSafe(hash.get("totalBAM")));
        response.put("count", parseIntSafe(hash.get("count")));
        response.put("lastInvoiceId", hash.get("lastInvoiceId"));
        response.put("lastUpdated", hash.get("lastUpdated"));
        return response;
    }

    /**
     * Agregat preko SVIH faktura (invoices:all), ne samo odobrenih.
     */
    public Map<String, Object> getStats() {
        List<Map<String, Object>> invoices = loadAllInvoices();

        int totalInvoices = invoices.size();
        double totalBAM = 0;
        int withAmount = 0;
        int pendingCount = 0;

        for (Map<String, Object> invoice : invoices) {
            Object totalBamObj = invoice.get("totalBAM");
            if (totalBamObj instanceof Number number) {
                totalBAM += number.doubleValue();
                withAmount++;
            }
            if ("PENDING_APPROVAL".equals(invoice.get("status"))) {
                pendingCount++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalInvoices", totalInvoices);
        stats.put("totalBAM", round2(totalBAM));
        stats.put("averageAmount", withAmount == 0 ? 0 : round2(totalBAM / withAmount));
        stats.put("pendingCount", pendingCount);
        return stats;
    }

    private List<Map<String, Object>> loadAllInvoices() {
        Set<String> ids;
        try (var jedis = redisPool.getResource()) {
            ids = jedis.smembers("invoices:all");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            String json = getInvoiceJson(id);
            if (json != null) {
                result.add(JsonUtil.toMap(json));
            }
        }
        return result;
    }

    private String getInvoiceJson(String id) {
        try (var jedis = redisPool.getResource()) {
            return jedis.get("invoice:" + id);
        }
    }

    private boolean statusMatches(String queryStatus, Map<String, Object> invoice) {
        Object statusObj = invoice.get("status");
        String status = statusObj == null ? "" : statusObj.toString();
        String normalizedQuery = queryStatus.trim().toUpperCase();
        if (normalizedQuery.equals("PENDING")) {
            return status.equals("PENDING_APPROVAL");
        }
        return status.equalsIgnoreCase(normalizedQuery);
    }

    private double parseDoubleSafe(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int parseIntSafe(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
