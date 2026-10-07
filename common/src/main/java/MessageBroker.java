
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * "MQ topic" abstrakcija preko RabbitMQ-a. Svaki topic je durable fanout exchange
 * (Topics.*); svaki subscribe() poziv dobija svoju anonimnu exclusive queue koja se
 * bind-uje na sve trazene exchange-ove, tako da svaki subscriber dobija svoju kopiju
 * poruke (broadcast), nezavisno od ostalih subscriber-a.
 */
public final class MessageBroker implements AutoCloseable {

    private static final Logger log = Logs.get(MessageBroker.class);

    private final Connection connection;
    private final Channel publishChannel;

    public MessageBroker() {
        String url = Config.mqUrl();
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri(url);
            connection = factory.newConnection();
            publishChannel = connection.createChannel();
            log.info("Connected to RabbitMQ at " + url);
        } catch (Exception e) {
            throw new RuntimeException("Could not connect to RabbitMQ at " + url, e);
        }
    }

    public interface Listener {
        void onMessage(String topic, String message);
    }

    public synchronized void publish(String topic, String message) {
        try {
            publishChannel.exchangeDeclare(topic, BuiltinExchangeType.FANOUT, true);
            publishChannel.basicPublish(topic, "", null, message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to publish to topic '" + topic + "'", e);
        }
    }

    /**
     * Subscribes on a background consumer thread managed by the RabbitMQ client and
     * returns immediately.
     */
    public void subscribe(Listener listener, String... topics) {
        try {
            Channel channel = connection.createChannel();
            String queueName = channel.queueDeclare().getQueue();
            for (String topic : topics) {
                channel.exchangeDeclare(topic, BuiltinExchangeType.FANOUT, true);
                channel.queueBind(queueName, topic, "");
            }

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String topic = delivery.getEnvelope().getExchange();
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                try {
                    listener.onMessage(topic, message);
                } catch (Exception e) {
                    log.warning("Listener failed for message on topic '" + topic + "': " + e.getMessage());
                }
            };
            channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {
            });
            log.info("Subscribed to topic(s) " + String.join(", ", topics));
        } catch (IOException e) {
            throw new RuntimeException("Failed to subscribe to topic(s) " + String.join(", ", topics), e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (IOException ignored) {
        }
    }
}
