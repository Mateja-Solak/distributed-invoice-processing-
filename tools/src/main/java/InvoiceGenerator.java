import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Generise skup XML faktura (validnih i namjerno neispravnih) za testiranje
 * cijelog pipeline-a. Pokrenuti kao: java ... InvoiceGenerator [izlazni-direktorij] [broj-faktura]
 * Podrazumijevano puni Config.inboxDir() (invoices/inbox) sa 110 faktura.
 *
 * Namjerno neispravne fakture (radi testiranja Validator servisa - Faza 3):
 *   - neispravan JIB (nije 13 cifara)
 *   - neispravan format email-a
 *   - nepodrzana valuta (nije BAM/EUR/USD/CHF/GBP)
 *   - datum u buducnosti
 *   - datum stariji od 365 dana
 *   - negativna kolicina stavke
 *   - iznos preko dozvoljenog limita (1.000.000)
 * Na kraju se dodatno upisuje JEDNA faktura sa vec iskoristenim <id> (drugo ime
 * fajla) - radi testiranja duplicate-detection logike u Parser servisu (Faza 2).
 */
public class InvoiceGenerator {

    private record Client(String name, String jib, String email) {
    }

    private static final Client[] CLIENTS = {
            new Client("ETF Banja Luka", "1234567891012", "fakture@etf.com"),
            new Client("Telekom Srpske", "2345678901234", "racuni@telekomsrpske.ba"),
            new Client("Boska Trade", "3456789012345", "kontakt@boskatrade.com"),
            new Client("Vrbas Doo", "4567890123456", "info@vrbas.rs"),
            new Client("Krajina Impex", "5678901234567", "office@krajinaimpex.com"),
            new Client("Una Systems", "6789012345678", "sales@unasystems.ba"),
            new Client("Sava Logistika", "7890123456789", "office@savalogistika.rs"),
            new Client("Drina Petrol", "8901234567890", "info@drinapetrol.ba"),
            new Client("Motajica Agro", "9012345678901", "agro@motajica.rs"),
            new Client("Kozara Tekstil", "0123456789012", "tekstil@kozara.ba")
    };

    private static final String[] CURRENCIES = {"BAM", "EUR", "USD", "CHF", "GBP"};
    private static final String[] DESCRIPTIONS = {
            "Web development", "Hosting", "Konsalting", "Licenca softvera", "Odrzavanje sistema",
            "Dizajn", "Marketing usluge", "Racunovodstvene usluge", "Transport", "Oprema"
    };

    private final Random random = new Random(42);

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of(args.length > 0 ? args[0] : Config.inboxDir());
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 110;

        Files.createDirectories(outputDir);
        new InvoiceGenerator().generate(outputDir, count);
    }

    public void generate(Path outputDir, int count) throws IOException {
        int generated = 0;
        String duplicateId = null;

        for (int i = 1; i <= count; i++) {
            String id = "INV-2026-" + String.format("%04d", i);
            Client client = CLIENTS[random.nextInt(CLIENTS.length)];
            String currency = CURRENCIES[random.nextInt(CURRENCIES.length)];
            String date = randomDate(i);
            List<Item> items = randomItems(i);

            // Namjerno unesene greske u odredjenom procentu faktura, radi testiranja Validator servisa.
            String jib = client.jib();
            String email = client.email();
            if (i % 17 == 0) {
                jib = "123"; // neispravan JIB
            }
            if (i % 23 == 0) {
                email = "nevalidan-email"; // neispravan format email-a
            }
            if (i % 29 == 0) {
                currency = "XYZ"; // nepodrzana valuta
            }

            writeInvoice(outputDir, id + ".xml", id, client.name(), jib, email, date, currency, items);
            generated++;

            if (i == count / 2) {
                duplicateId = id;
            }
        }

        // Jedna faktura se salje dva puta pod istim <id> radi testiranja Parser duplicate-detection logike.
        if (duplicateId != null) {
            Client client = CLIENTS[0];
            writeInvoice(outputDir, duplicateId + "-dup.xml", duplicateId, client.name(), client.jib(),
                    client.email(), LocalDate.now().toString(), "BAM", randomItems(1));
            generated++;
        }

        System.out.println("Generated " + generated + " XML invoices in " + outputDir.toAbsolutePath());
    }

    private record Item(String description, int quantity, double unitPrice) {
    }

    private String randomDate(int i) {
        LocalDate today = LocalDate.now();
        if (i % 31 == 0) {
            return today.plusDays(5).toString(); // u buducnosti - neispravno
        }
        if (i % 37 == 0) {
            return today.minusDays(400).toString(); // starije od 365 dana - neispravno
        }
        return today.minusDays(random.nextInt(300)).toString();
    }

    private List<Item> randomItems(int i) {
        int itemCount = 1 + random.nextInt(3);
        List<Item> items = new ArrayList<>();
        for (int j = 0; j < itemCount; j++) {
            String description = DESCRIPTIONS[random.nextInt(DESCRIPTIONS.length)];
            int quantity = 1 + random.nextInt(10);
            double unitPrice = 10 + random.nextInt(500);

            if (i % 19 == 0 && j == 0) {
                quantity = -1; // neispravna kolicina
            }
            if (i % 41 == 0 && j == 0) {
                unitPrice = 2_000_000; // ukupan iznos prelazi dozvoljeni limit
            }
            items.add(new Item(description, quantity, unitPrice));
        }
        return items;
    }

    private void writeInvoice(Path dir, String fileName, String id, String clientName, String jib, String email,
                               String date, String currency, List<Item> items) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<invoice>\n");
        xml.append("  <id>").append(id).append("</id>\n");
        xml.append("  <client>\n");
        xml.append("    <name>").append(clientName).append("</name>\n");
        xml.append("    <jib>").append(jib).append("</jib>\n");
        xml.append("    <email>").append(email).append("</email>\n");
        xml.append("  </client>\n");
        xml.append("  <date>").append(date).append("</date>\n");
        xml.append("  <currency>").append(currency).append("</currency>\n");
        xml.append("  <items>\n");
        for (Item item : items) {
            xml.append("    <item>\n");
            xml.append("      <description>").append(item.description()).append("</description>\n");
            xml.append("      <quantity>").append(item.quantity()).append("</quantity>\n");
            xml.append("      <unitPrice>").append(String.format(Locale.US, "%.2f", item.unitPrice())).append("</unitPrice>\n");
            xml.append("    </item>\n");
        }
        xml.append("  </items>\n");
        xml.append("</invoice>\n");

        Files.writeString(dir.resolve(fileName), xml.toString(), StandardCharsets.UTF_8);
    }
}
