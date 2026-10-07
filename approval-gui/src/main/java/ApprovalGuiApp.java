
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import redis.clients.jedis.JedisPool;

public class ApprovalGuiApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/approval-gui.fxml"));
        Parent root = loader.load();

        JedisPool redisPool = RedisClientFactory.create();
        MessageBroker broker = new MessageBroker();

        ApprovalGuiController controller = loader.getController();
        controller.start(broker, redisPool);

        stage.setTitle("Approval klijent");
        stage.setScene(new Scene(root));
        stage.show();
    }
}
