import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Monitor klijent (spec: "Moze se pokrenuti kao N nezavisnih instanci"). Svaka
 * instanca nezavisno:
 *   - pridruzuje se UDP multicast grupi Config.multicastGroup():multicastPort()
 *     (239.0.0.1:4446 po default-u)
 *   - prima i parsira JSON notifikacije (Notification.class)
 *   - prikazuje ih u GUI tabeli (JavaFX TableView, definisanoj u monitor-gui.fxml)
 *
 * Ne smije se koristiti REST API polling umjesto multicast-a (eksplicitna zabrana
 * u zadatku) - jedini izvor podataka je MulticastSocket, upravljan iz MonitorController-a.
 */
public class MonitorApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/monitor-gui.fxml"));
        Parent root = loader.load();

        MonitorController controller = loader.getController();

        stage.setTitle("Monitor klijent");
        stage.setScene(new Scene(root, 700, 400));
        stage.setOnCloseRequest(e -> {
            controller.shutdown();
            Platform.exit();
            System.exit(0);
        });
        stage.show();
    }
}
