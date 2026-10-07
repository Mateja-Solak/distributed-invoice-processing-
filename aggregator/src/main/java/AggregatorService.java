import redis.clients.jedis.JedisPool;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.*;
/**
 * Faza 6 - Aggregator servis. Konzumira poruke sa SVIH MQ topic-a (Topics.ALL) i
 * azurira statuse u Redis-u. Za svaku fakturu, bez obzira na topic:
 *   - cuva kompletan JSON zapis fakture: invoice:{id} -> JSON string
 *   - dodaje id u skup invoices:all (SADD) - REST API i Approval GUI (backfill nakon
 *     restarta) prolaze kroz ovaj skup da nadju sve poznate fakture, bez njega ih
 *     ne mogu naci
 *
 * Samo za fakture koje stignu na Topics.APPROVED, dodatno:
 *   - azurira Redis hash za klijenta: client:{jib} -> polja totalBAM (kumulativno),
 *     count, lastInvoiceId, lastUpdated
 *   - salje UDP multicast notifikaciju na grupu/port iz Config-a, JSON oblika
 *     {invoiceId, clientName, totalBAM, status, timestamp}
 */
public class AggregatorService {

    private static final Logger log = Logs.get(AggregatorService.class);


    private final JedisPool redisPool;
    private final MessageBroker broker;
    public AggregatorService( JedisPool redisPool, MessageBroker broker ) {
        this.redisPool = redisPool;
        this.broker = broker;
    }

    public static void main(String[] args) {

        JedisPool redisPool = RedisClientFactory.create();
        MessageBroker broker = new MessageBroker();
        new AggregatorService(redisPool, broker).start();
        log.info("Aggregator service started, listening on all topics");
    }

    public void start() {

        broker.subscribe(this::onMessage,Topics.ALL);
    }

    private void onMessage(String topic, String message) {

        Invoice invoice=JsonUtil.toInvoice(message);

        if (invoice.id == null) {
            return;
        }
        try (var jedis = redisPool.getResource()) {
            jedis.set("invoice:" + invoice.id, message);
            jedis.sadd("invoices:all", invoice.id);
        }

        if (Topics.APPROVED.equals(topic)) {
            updateClientAggregate(invoice);
            sendMulticastNotification(invoice);
        }
    }

    private void updateClientAggregate(Invoice invoice) {
        //hincryBy automatsko uvecavanje
        //  key = "client:" + invoice.client.jib
        //       - jedis.hincrBy(key, "count", 1)
        //       - jedis.hincrByFloat(key, "totalBAM", invoice.totalBAM)
        //       - jedis.hset(key, "lastInvoiceId", invoice.id)
        //       - jedis.hset(key, "lastUpdated", Instant.now().toString())
        String jib = invoice.client == null ? "unknown" : invoice.client.jib;
        String key = "client:" + jib;
        double totalBAM = invoice.totalBAM == null ? 0 : invoice.totalBAM;

        try (var jedis = redisPool.getResource()) {
            jedis.hincrBy(key, "count", 1);
            jedis.hincrByFloat(key, "totalBAM", totalBAM);
            jedis.hset(key, "lastInvoiceId", invoice.id);
            jedis.hset(key, "lastUpdated", Instant.now().toString());
        }
    }

    private void sendMulticastNotification(Invoice invoice) {
        //  napraviti Map<String,Object> sa poljima invoiceId, clientName,
        //         totalBAM, status, timestamp (Instant.now().toString()).
        // JsonUtil.toJson(map) -> bytes (UTF-8).
        // DatagramSocket + DatagramPacket na InetAddress.getByName(Config.multicastGroup())
        //         i Config.multicastPort() - socket.send(packet).

        Map<String, Object>notification=new LinkedHashMap<>();
        notification.put("invoiceId", invoice.id);
        notification.put("clientName", invoice.client == null ? null : invoice.client.name);
        notification.put("totalBAM", invoice.totalBAM);
        notification.put("status", invoice.status);
        notification.put("timestamp", Instant.now().toString());

        byte[]data=JsonUtil.toJson(notification).getBytes(StandardCharsets.UTF_8);
        try(DatagramSocket socket=new DatagramSocket())
        {
            InetAddress group=InetAddress.getByName(Config.multicastGroup());
            DatagramPacket packet=new DatagramPacket(data,data.length,group,Config.multicastPort());
            socket.send(packet);
            log.info("UDP multicast notification sent for invoice " + invoice.id);
        } catch (Exception e) {
            log.warning("Failed to send UDP multicast notification: " + e.getMessage());
        }

    }
}
