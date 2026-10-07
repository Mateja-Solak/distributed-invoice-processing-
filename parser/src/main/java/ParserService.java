import redis.clients.jedis.JedisPool;
import redis.clients.jedis.RedisClient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Faza 2 - Parser servis. TCP server koji prima fakture od Watcher servisa,
 * provjerava integritet (SHA-256), provjerava duplikate u Redis-u, poziva
 * Validator servis putem RMI i rezultat stavlja na odgovarajuci MQ topic.
 * Common klase koje treba napisati PRIJE ove klase (istim redoslijedom zavisnosti):
 *   1. ClientInfo      - polja fakture za klijenta (name, jib, email)
 *   2. Item             - stavka fakture (description, quantity, unitPrice)
 *   3. Invoice          - model fakture (id, client, date, currency, items, status, errors, ...)
 *   4. ValidationResult - rezultat validacije (valid, errors, validatorTimestamp)
 *   5. ValidatorRemote  - RMI interfejs: ValidationResult validate(Invoice) throws RemoteException
 *   6. JsonUtil         - Jackson (de)serijalizacija Invoice <-> JSON, koristi se za MQ poruke
 *   7. Topics           - imena MQ topic-a (VALIDATED, REJECTED, ...)
 *   8. RedisClientFactory - JedisPool factory (Config.redisHost/redisPort) za dedup provjeru
 *   9. MessageBroker    - RabbitMQ publish/subscribe wrapper (Config.mqUrl)
 *
 * Vec postoje u common-u i mogu se koristiti odmah: Config, HashUtil, Logs.
 */
public class ParserService {

    private static final Logger log = Logs.get(ParserService.class);
    private static final int MAX_PAYLOAD = 20_000_000; //maksimalna velicina fakture koja se prihavata je 20MB

    private final JedisPool redisPool;
    private final MessageBroker broker;
    private final ValidatorRemote validator;

    public ParserService(JedisPool redisPool,MessageBroker broker,ValidatorRemote validator)
    {
        this.redisPool = redisPool;
        this.broker = broker;
        this.validator = validator;
    }

    public static void main(String[] args) throws Exception {

            JedisPool redisPool= RedisClientFactory.create();
        //   napraviti new MessageBroker()
            MessageBroker broker=new MessageBroker();
        //   pronaci ValidatorRemote preko RMI
            ValidatorRemote validator=lookupValidatorWithRetry();
        //  new ParserService(...).start(Config.parserPort())
            ParserService service = new ParserService(redisPool, broker, validator);
            service.start(Config.parserPort());


    }
    private  static ValidatorRemote lookupValidatorWithRetry() throws InterruptedException
    {
        while(true)
        {
            try {
                Registry registry = LocateRegistry.getRegistry(Config.rmiHost(), Config.rmiPort());
                ValidatorRemote validator = (ValidatorRemote) registry.lookup("ValidatorService");
                log.info("Connected to Validator RMI service at " + Config.rmiHost() + ":" + Config.rmiPort());
                return validator;
            }
            catch(Exception e)
            {
                log.warning("Validator RMI service not available yet, retrying in 3s: " + e.getMessage());
                Thread.sleep(3000);
            }
        }
    }

    public void start(int port) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log.info("Parser service listening on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handleConnection(socket));
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // procitati header - in.readInt() - i validirati da je u opsegu
            //         (0, MAX_PAYLOAD], u suprotnom zatvoriti konekciju bez ACK-a.
            int payloadLen=in.readInt();
            if(payloadLen<=0 || payloadLen> MAX_PAYLOAD)
            {
                log.severe("Invalid payload length (" + payloadLen + "), closing connection");
                return;
            }
            //  alocirati byte[] payload odgovarajuce duzine i procitati ga
            //         u potpunosti (in.readFully).
            byte[] payload=new byte[payloadLen];
            in.readFully(payload);
            //  procitati footer - byte[32] hash - u potpunosti (in.readFully).
            byte[] receivedHash=new byte[32];
            in.readFully(receivedHash);
            // T odmah poslati ACK (out.writeBoolean(true); out.flush()) - transportna
            //         potvrda da su bajtovi stigli, ide PRIJE provjere integriteta.

            out.writeBoolean(true);out.flush();
            log.info("Received " + payloadLen + " bytes from " + socket.getRemoteSocketAddress());
            //  izracunati HashUtil.sha256(payload) i uporediti sa primljenim hash-om
            byte[] computedHash=HashUtil.sha256(payload);
            if(!Arrays.equals(computedHash,receivedHash))
            {
                log.severe("Hash mismatch, invoice discarded");
                return;
            }
          ///pozvati processInvoice(payload).
            processInvoice(payload);

        } catch (IOException e) {
            log.warning("Connection error: " + e.getMessage());
        }
    }

    private void processInvoice(byte[] payload) {
        // invoice invoice = InvoiceXmlParser.parse(payload); uhvatiti gresku
        //         parsiranja i odbaciti fakturu ako XML nije validan.
        Invoice invoice;
        try {
            invoice=InvoiceXmlParser.parse(payload);
        }
        catch (Exception e)
        {
            log.severe("Failed to parse XML: " + e.getMessage());
            return;
        }
        // provjeriti da invoice.id postoji (nije null/blank), u suprotnom odbaciti.
        //zato sto se kroz api dohvata faktura po kljucu id , faktura bez id-a se ne moze smilsleno pratiti kroz ostatak pipleine-a
        if(invoice.id==null || invoice.id.isBlank())
        {
            log.severe("Invoice has no <id>, discarding");
            return;
        }
        //  dedup provjera u Redis-u - jedis.setnx("invoice:dedup:" + invoice.id, "1");
        //         ako je vec postojao (setnx vraca 0), odbaciti kao duplikat.
        try(var jedis=redisPool.getResource())
        {
            long added = jedis.setnx("invoice:dedup:" + invoice.id, "1");
            if (added == 0) {
                log.warning("Invoice " + invoice.id + " is a duplicate, discarding");
                return;
            }
        }

        // stpozvati validator.validate(invoice) preko RMI.
        //         - ako je valid: invoice.status = "VALIDATED", objaviti na Topics.VALIDATED
        //         - ako nije: invoice.status = "REJECTED", invoice.errors = result.errors,
        //           objaviti na Topics.REJECTED
        //         Objava ide preko broker.publish(topic, JsonUtil.toJson(invoice)).
        try {
            ValidationResult result=validator.validate(invoice);
            if(result.valid)
            {
                invoice.status = "VALIDATED";
                broker.publish(Topics.VALIDATED, JsonUtil.toJson(invoice));
                log.info("Invoice " + invoice.id + " validated, published to " + Topics.VALIDATED);
            }
            else {
                invoice.status = "REJECTED";
                invoice.errors = result.errors;
                broker.publish(Topics.REJECTED, JsonUtil.toJson(invoice));
                log.warning("Invoice " + invoice.id + " rejected: " + result.errors);
            }
        }
        catch (Exception e) {
            log.severe("Validator RMI call failed for " + invoice.id + ": " + e.getMessage());
        }
    }
}
