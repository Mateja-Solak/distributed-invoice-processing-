import javafx.application.Application;

/**
 * Ulazna tacka za jednu instancu Monitor klijenta. Vise instanci = vise pokretanja
 * ove main() metode (npr. vise terminala) - svaka nezavisno prima isti multicast.
 *
 * Ne nasljedjuje Application namjerno: kada glavna (main) klasa direktno nasljedjuje
 * Application, java launcher pokrenut sa obicnog classpath-a (bez --module-path)
 * baca "JavaFX runtime components are missing" - zato se ovdje samo poziva
 * Application.launch(...) sa stvarnom Application klasom kao parametrom (ista
 * tehnika kao ApprovalGuiClient/ApprovalGuiApp).
 */
public class MonitorClient {

    public static void main(String[] args) {
        Application.launch(MonitorApp.class, args);
    }

}
