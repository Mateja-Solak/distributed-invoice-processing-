import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Faza 3 - Validator servis. Implementacija RMI interfejsa ValidatorRemote koja
 * provjerava tacnost podataka na fakturi (vidi projekat.pdf, Faza 3).
 *.
 */
public class ValidatorServiceImpl extends UnicastRemoteObject implements ValidatorRemote {

    //konstante za validaciju:
    //  - Set<String> VALID_CURRENCIES = Set.of("BAM", "EUR", "USD", "CHF", "GBP")
    //  - Pattern JIB_PATTERN - tacno 13 cifara
    //  - Pattern EMAIL_PATTERN - osnovna provjera email formata
    private static final Set<String> VALID_CURRENCIES = Set.of("BAM", "EUR", "USD", "CHF", "GBP");
    private static final Pattern JIB_PATTERN = Pattern.compile("\\d{13}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}$");
    public ValidatorServiceImpl() throws RemoteException {
        super();
    }

    @Override
    public ValidationResult validate(Invoice invoice) throws RemoteException {

        List<String> errors = new ArrayList<>();
        validateJib(invoice, errors);
        validateDate(invoice, errors);
        validateItems(invoice, errors);
        validateCurrency(invoice, errors);
        validateTotal(invoice, errors);
        validateEmail(invoice, errors);

        return new ValidationResult(errors.isEmpty(), errors, Instant.now().toString());
    }


    private void validateJib(Invoice invoice, List<String> errors)
    {
        String jib=invoice.client==null ? null:invoice.client.jib;
        if(jib==null || !JIB_PATTERN.matcher(jib).matches())
        {
            errors.add("JIB mora sadrzati tacno 13 cifara");
        }
    }


   private void validateDate(Invoice invoice, List<String> errors) {
        if (invoice.date == null) {
                errors.add("Datum fakture nije naveden");
                return;
            }
            try {
                LocalDate date = LocalDate.parse(invoice.date);
                LocalDate today = LocalDate.now();
                if (date.isAfter(today)) {
                    errors.add("Datum fakture ne moze biti u buducnosti");
                } else if (date.isBefore(today.minusDays(365))) {
                    errors.add("Datum fakture je stariji od 365 dana");
                }
            } catch (Exception e) {
                errors.add("Datum fakture nije u ispravnom formatu (yyyy-MM-dd)");
            }
        }

    private void validateItems(Invoice invoice, List<String> errors)
    {
    if(invoice.items==null || invoice.items.isEmpty())
    {
        errors.add("Faktura mora imati bar jednu stavku");
        return;
    }
    for(Item item : invoice.items)
    {
        if(item.quantity<=0)
        {
            errors.add("Kolicina za stavku '" + item.description + "' mora biti pozitivna");
        }
        if(item.unitPrice<=0)
        {
            errors.add("Jedinicna cijena za stavku '" + item.description + "' mora biti pozitivna");

        }
    }
    }
    private void validateCurrency(Invoice invoice,List<String> errors)
    {
        if(invoice.currency==null || !VALID_CURRENCIES.contains(invoice.currency))
        {
            errors.add("Nepodrzana valuta: " + invoice.currency);
        }
    }
    private void validateTotal(Invoice invoice,List<String> errors)
    {
        double total = invoice.totalAmount();
        if (total <= 0 || total >= 1_000_000) {
            errors.add("Ukupan iznos mora biti > 0 i < 1.000.000 (izracunato: " + total + ")");
        }
    }
    private void validateEmail(Invoice invoice, List<String> errors) {
        String email = invoice.client == null ? null : invoice.client.email;
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            errors.add("Neispravan format email adrese: " + email);
        }
    }
}
