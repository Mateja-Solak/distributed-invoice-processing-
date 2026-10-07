import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Kontroler za monitor-gui.fxml - prima UDP multicast notifikacije i azurira
 * TableView. Logika je identicna onoj koja je ranije bila direktno u MonitorApp,
 * samo izdvojena u FXML kontroler.
 */
public class MonitorController {

    private static final Logger log = Logs.get(MonitorController.class);

    @FXML private TableView<Notification> table;
    @FXML private TableColumn<Notification, String> idCol;
    @FXML private TableColumn<Notification, String> clientCol;
    @FXML private TableColumn<Notification, Double> totalCol;
    @FXML private TableColumn<Notification, String> statusCol;
    @FXML private TableColumn<Notification, String> timeCol;
    @FXML private Label statusLabel;

    private final ObservableList<Notification> rows = FXCollections.observableArrayList();
    private int received;
    private MulticastSocket socket;

    @FXML
    private void initialize() {
        idCol.setCellValueFactory(new PropertyValueFactory<>("invoiceId"));
        clientCol.setCellValueFactory(new PropertyValueFactory<>("clientName"));
        totalCol.setCellValueFactory(new PropertyValueFactory<>("totalBAM"));
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        timeCol.setCellValueFactory(new PropertyValueFactory<>("timestamp"));
        table.setItems(rows);

        Thread receiver = new Thread(this::receiveLoop, "multicast-listener");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receiveLoop() {
        try {
            socket = new MulticastSocket(Config.multicastPort());
            socket.setReuseAddress(true);
            InetAddress group = InetAddress.getByName(Config.multicastGroup());
            socket.joinGroup(group);
            log.info("Joined multicast group " + group.getHostAddress() + ":" + Config.multicastPort());
            byte[] buffer = new byte[4096];
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                try {
                    Notification notification = JsonUtil.fromJson(json, Notification.class);
                    Platform.runLater(() -> addRow(notification));
                } catch (Exception e) {
                    log.warning("Failed to parse notification: " + json);
                }
            }
        } catch (Exception e) {
            if (socket == null || socket.isClosed()) {
                log.severe("Multicast listener failed to start: " + e.getMessage());
            } else {
                log.warning("Multicast listener interrupted: " + e.getMessage());
            }
        }
    }

    private void addRow(Notification notification) {
        rows.removeIf(n -> n.getInvoiceId() != null && n.getInvoiceId().equals(notification.getInvoiceId()));
        rows.add(0, notification);
        received++;
        statusLabel.setText("Faktura u tabeli: " + rows.size() + " | primljeno notifikacija: " + received);
    }

    public void shutdown() {
        if (socket != null) {
            socket.close();
        }
    }
}
