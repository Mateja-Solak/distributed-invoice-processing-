
import javafx.application.Application;

/**
 * GUI klijent za Fazu 5. Prati invoices.pending_approval i korisniku prikazuje
 * podatke o fakturi uz mogucnost odobravanja ili odbijanja (sa obaveznim razlogom).
 *
 * Ne nasljedjuje Application namjerno: kada glavna klasa direktno nasljedjuje
 * Application, java launcher zahtijeva --module-path i odbija pokretanje sa
 * obicnog classpath-a ("JavaFX runtime components are missing").
 */
public class ApprovalGuiClient {

    public static void main(String[] args) {
        Application.launch(ApprovalGuiApp.class, args);
    }
}
