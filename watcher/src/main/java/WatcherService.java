import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Faza 1 - Watcher servis.
 * Prati folder /invoices/inbox, i za svaki novi XML fajl salje sadrzaj Parser servisu
 * preko TCP-a: [2B filename len][filename][4B N big-endian][N bajtova payload][32B SHA-256].
 * Nakon ACK-a fajl se premjesta u /invoices/processed.
 *
 * Novi fajlovi se stavljaju u bounded queue koju obradjuje fiksan broj radnih niti,
 * cime se izbjegava gubitak dogadjaja i preopterecenje Parser servisa kada se
 * odjednom kreira veliki broj fajlova.
 */
public class WatcherService {
    private static final Logger log = Logs.get(WatcherService.class);
    private static final int WORKER_COUNT=8;
    private static final int QUEUE_CAPACITY=2000;
    private static final int MAX_SEND_ATTEMPTS=3;
    private static final int RESCAN_INTERVAL_SECONDS = 10;

    private final Path inboxDir;
    private final Path processedDir;
    private final Path errorDir;
    private final String parserHost;
    private final int parserPort;
    private final BlockingQueue<Path> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Set<Path> inFlight = ConcurrentHashMap.newKeySet();

    public WatcherService(Path inboxDir, Path processedDir, Path errorDir, String parserHost, int parserPort) {
        this.inboxDir = inboxDir;
        this.processedDir = processedDir;
        this.errorDir = errorDir;
        this.parserHost = parserHost;
        this.parserPort = parserPort;
    }


    public static void main(String[] args) throws Exception {
        Path inbox=Path.of(Config.inboxDir());
        Path processed=Path.of(Config.processedDir());
        Path error=Path.of(Config.errorDir());
        Files.createDirectories(inbox);
        Files.createDirectories(processed);
        Files.createDirectories(error);

        WatcherService service=new WatcherService(inbox,processed,error,Config.parserHost(),Config.parserPort());
        service.start();


    }
    public void start() throws IOException
    {
        ExecutorService workers=Executors.newFixedThreadPool(WORKER_COUNT);
        for(int i=0;i<WORKER_COUNT;i++)
        {
            workers.submit(this::workerLoop);
        }
        enqueueExistingFiles();
        // Dodatna mreza sigurnosti pored WatchService-a: ako se propusti neki
        // filesystem event (npr. OVERFLOW), fajl ce svakako biti pokupljen ovdje.
        ScheduledExecutorService rescanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "inbox-rescan");
            t.setDaemon(true);
            return t;
        });
        rescanner.scheduleAtFixedRate(this::rescanQuietly, RESCAN_INTERVAL_SECONDS, RESCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);

        watchLoop();
    }
    private void rescanQuietly() {
        try {
            enqueueExistingFiles();
        } catch (IOException e) {
            log.warning("Periodic inbox rescan failed: " + e.getMessage());
        }
    }
    private void enqueueExistingFiles() throws IOException {
        try (var stream = Files.list(inboxDir)) {
            List<Path> existing = stream.filter(this::isXml).sorted().toList();
            int enqueued = 0;
            for (Path p : existing) {
                if (offer(p)) {
                    enqueued++;
                }
            }
            if (enqueued > 0) {
                log.info("Enqueued " + enqueued + " pre-existing files from inbox");
            }
        }
    }
    private void watchLoop() throws IOException
    {
        WatchService watchService=FileSystems.getDefault().newWatchService();
        inboxDir.register(watchService,StandardWatchEventKinds.ENTRY_CREATE);
        log.info("Watching " + inboxDir.toAbsolutePath() + " for new invoices...");
        while (true) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    log.warning("Watch event overflow - some filesystem events may have been lost");
                    continue;
                }
                Path fileName = (Path) event.context();
                Path fullPath = inboxDir.resolve(fileName);
                if (isXml(fullPath)) {
                    offer(fullPath);
                }
            }

            if (!key.reset()) {
                log.severe("Watch key no longer valid, stopping watcher loop");
                return;
            }
        }


    }
    private boolean isXml(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".xml") && Files.isRegularFile(path);
    }
    private boolean offer(Path path) {
        if (!inFlight.add(path)) {
            return false;
        }
        try {
            queue.put(path);
            return true;
        } catch (InterruptedException e) {
            inFlight.remove(path);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    private void workerLoop() {
        while (true) {
            Path path;
            try {
                path = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            processFile(path);
        }
    }
    private void processFile(Path path) {
        try {
            processFileInternal(path);
        } finally {
            inFlight.remove(path);
        }
    }
    private void processFileInternal(Path path)
    {
        if(!Files.exists(path))
        {
            return;
        }
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            log.warning("Could not read " + path + ": " + e.getMessage());
            return;
        }
        byte[] hash = HashUtil.sha256(content);
        String fileName = path.getFileName().toString();
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                String ackName = sendToParser(fileName, content, hash);
                log.info("Received ACK for " + ackName + ", moving to processed/");
                Files.move(path, processedDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                log.warning("Attempt " + attempt + " failed sending " + fileName + " to parser: " + e.getMessage());
                sleepQuietly(500L * attempt);
            }
        }
        log.severe("Giving up on " + fileName + " after " + MAX_SEND_ATTEMPTS + " attempts, moving to error/");
        try {
            Files.move(path, errorDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("Could not move " + fileName + " to error/: " + e.getMessage());
        }
    }
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String sendToParser(String fileName, byte[] content, byte[] hash) throws IOException {
        try(Socket socket=new Socket(parserHost,parserPort);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream())) {
            // Poruka je tacno header(4B) + payload(N) + footer(32B), bez dodatnih polja
            out.writeInt(content.length);
            out.write(content);
            out.write(hash);
            out.flush();
            // ACK je cisto transportna potvrda da su bajtovi stigli (spec Faza 2, prije
            // provjere integriteta) - parser jos nije parsirao XML pa ne moze vratiti
            // <id>, zato je ACK ovdje samo uspjeh/neuspjeh, ne ime fajla.
            boolean ack=in.readBoolean();
            if(!ack)
            {
                throw new IOException("Parser nije potvrdio prijem za " + fileName);

            }

            return fileName;
        }
    }

}
