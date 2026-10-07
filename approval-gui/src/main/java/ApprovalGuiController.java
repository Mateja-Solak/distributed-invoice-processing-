
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ApprovalGuiController {

    private static final Logger log = Logs.get(ApprovalGuiController.class);

    @FXML
    private ListView<String> invoiceList;
    @FXML
    private TextArea detailsArea;
    @FXML
    private TextArea reasonArea;
    @FXML
    private Button approveButton;
    @FXML
    private Button rejectButton;

    private final Map<String, Invoice> pending = new ConcurrentHashMap<>();
    private final ObservableList<String> listItems = FXCollections.observableArrayList();
    private MessageBroker broker;

    @FXML
    private void initialize() {
        invoiceList.setItems(listItems);
        invoiceList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldId, newId) -> showDetails(newId));
        approveButton.setOnAction(e -> approveSelected());
        rejectButton.setOnAction(e -> rejectSelected());
    }

    public void start(MessageBroker broker, JedisPool redisPool) {
        this.broker = broker;
        loadPendingFromRedis(redisPool);
        broker.subscribe((topic, message) -> onPending(JsonUtil.toInvoice(message)), Topics.PENDING_APPROVAL);
    }

    /**
     * Backfill: bez ovoga bi zahtjevi objavljeni na Redis Pub/Sub prije nego sto je
     * GUI pokrenut i subscribe-ovan bili trajno izgubljeni (Pub/Sub ne pamti poruke).
     */
    private void loadPendingFromRedis(JedisPool redisPool) {
        int loaded = 0;
        try (var jedis = redisPool.getResource()) {
            Set<String> ids = jedis.smembers("invoices:all");
            for (String id : ids) {
                String json = jedis.get("invoice:" + id);
                if (json == null) {
                    continue;
                }
                Invoice invoice = JsonUtil.toInvoice(json);
                if ("PENDING_APPROVAL".equals(invoice.status)) {
                    onPending(invoice);
                    loaded++;
                }
            }
        } catch (Exception e) {
            log.warning("Loading pending invoices from Redis failed: " + e.getMessage());
        }
        log.info("Loaded " + loaded + " pending invoices from Redis");
    }

    private void onPending(Invoice invoice) {
        pending.put(invoice.id, invoice);
        Platform.runLater(() -> {
            if (!listItems.contains(invoice.id)) {
                listItems.add(invoice.id);
            }
        });
    }

    private void showDetails(String id) {
        if (id == null) {
            detailsArea.setText("");
            return;
        }
        Invoice invoice = pending.get(id);
        if (invoice == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Faktura: ").append(invoice.id).append('\n');
        sb.append("Klijent: ").append(invoice.client.name).append(" (JIB: ").append(invoice.client.jib).append(")\n");
        sb.append("Email: ").append(invoice.client.email).append('\n');
        sb.append("Datum: ").append(invoice.date).append('\n');
        sb.append("Valuta: ").append(invoice.currency).append('\n');
        sb.append("Stavke:\n");
        for (Item item : invoice.items) {
            sb.append("  - ").append(item.description).append(": ")
                    .append(item.quantity).append(" x ").append(item.unitPrice).append('\n');
        }
        sb.append("Ukupno (BAM): ").append(invoice.totalBAM).append('\n');
        sb.append("Izvor kursa: ").append(invoice.rateSource).append('\n');
        detailsArea.setText(sb.toString());
    }

    private void approveSelected() {
        String id = invoiceList.getSelectionModel().getSelectedItem();
        if (id == null) {
            showAlert("Izaberite fakturu iz liste");
            return;
        }
        Invoice invoice = pending.remove(id);
        invoice.status = "APPROVED";
        broker.publish(Topics.APPROVED, JsonUtil.toJson(invoice));
        log.info("Invoice " + id + " APPROVED by operator (totalBAM=" + invoice.totalBAM + ")");
        removeFromList(id);
    }

    private void rejectSelected() {
        String id = invoiceList.getSelectionModel().getSelectedItem();
        if (id == null) {
            showAlert("Izaberite fakturu iz liste");
            return;
        }
        String reason = reasonArea.getText().trim();
        if (reason.isEmpty()) {
            showAlert("Razlog odbijanja je obavezan");
            return;
        }
        Invoice invoice = pending.remove(id);
        invoice.status = "REJECTED";
        invoice.rejectReason = reason;
        broker.publish(Topics.REJECTED, JsonUtil.toJson(invoice));
        log.info("Invoice " + id + " REJECTED by operator (reason: " + reason + ")");
        reasonArea.setText("");
        removeFromList(id);
    }

    private void removeFromList(String id) {
        Platform.runLater(() -> {
            listItems.remove(id);
            detailsArea.setText("");
        });
    }

    private void showAlert(String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }
}
