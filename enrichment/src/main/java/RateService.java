import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Dohvata kurs valute prema BAM, uz Redis cache (rate:{currency}:{date}).
 * Koristi se i iz glavnog toka i iz retry mehanizma u EnrichmentService.
 *
 * Common klase potrebne - SVE VEC POSTOJE, nije potrebno praviti nista novo:
 *   - Config - Config.exchangeApiBase(), Config.redisHost/redisPort
 *   - Logs
 *

 */
public class RateService {

    private static final Logger log = Logs.get(RateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JedisPool redisPool;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public RateService(JedisPool redisPool) {
        this.redisPool = redisPool;
    }


    public record RateResult(double rate, String source) {

    }

    public RateResult fetchRate(String currency) {
        //  specijalan slucaj za "BAM" (rate 1.0, source "API").
        if("BAM".equals(currency)){return new RateResult(1.0,"API");}
        // provjeri Redis cache po kljucu "rate:{currency}:{date}".
        String cacheKey = "rate:" + currency + ":" + LocalDate.now();
        try (var jedis = redisPool.getResource()) {
            String cached = jedis.get(cacheKey);
            if (cached != null) {
                return new RateResult(Double.parseDouble(cached), "CACHE");
            }
        }
        // ako nema u cache-u, pozovi callExchangeApi(currency).
        Double rate = callExchangeApi(currency);
        if (rate == null) {
            return null;
        }
        //  ako je API uspio, upisi u cache (jedis.setex, TTL 1h) i vrati rezultat.
        try (var jedis = redisPool.getResource()) {
            jedis.setex(cacheKey, 3600, String.valueOf(rate));
        }
        return new RateResult(rate, "API");

    }

    private Double callExchangeApi(String currency) {
        // TGET poziv na Config.exchangeApiBase(), parsiraj JSON odgovor


      try{
            HttpRequest request=HttpRequest.newBuilder(URI.create(Config.exchangeApiBase()))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response=httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() != 200) {
              log.warning("Exchange API returned status " + response.statusCode());
              return null;
          }
          Map<?, ?> body = MAPPER.readValue(response.body(), Map.class);
          if (!"success".equals(body.get("result"))) {
              return null;
          }
          Object ratesObj = body.get("rates");
          if (!(ratesObj instanceof Map<?, ?> rates)) {
              return null;
          }
          Object rateVal = rates.get(currency);
          if (rateVal == null) {
              log.warning("Rate for currency " + currency + " not found in API response");
              return null;
          }
          return ((Number) rateVal).doubleValue();
      }
      catch(Exception e)
      {
          log.warning("Exchange API call failed"+e.getMessage());
          return null;
      }
    }
}
