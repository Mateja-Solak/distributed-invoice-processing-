import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Logger;

/**
 * Faza 3 - Validator servis, izlozen kao RMI objekat.
 *
 * ParserService (Faza 2) trazi ovaj servis preko:
 *   Registry registry = LocateRegistry.getRegistry(Config.rmiHost(), Config.rmiPort());
 *   ValidatorRemote validator = (ValidatorRemote) registry.lookup("ValidatorService");
 *
 * Ime "ValidatorService" mora biti identicno onome sto se ovdje registruje preko
 * registry.rebind(...), inace ce lookup u ParserService-u stalno pucati.
 */
public class ValidatorServer {

    private static final Logger log = Logs.get(ValidatorServer.class);

    public static void main(String[] args) throws Exception {
        // procitati port preko Config.rmiPort().
        int port=Config.rmiPort();
        Registry registry=LocateRegistry.createRegistry(port);
        ValidatorServiceImpl impl=new ValidatorServiceImpl();
        registry.rebind("ValidatorService", impl);

        log.info("Validator RMI service started on port " + port);

        // RMI export ne drzi JVM zivim sam po sebi na svim JDK verzijama - main mora
        // ostati blokiran, inace se JVM ugasi odmah nakon rebind-a.
        Thread.currentThread().join();
    }
}
