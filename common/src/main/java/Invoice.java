import java.io.Serializable;
import java.util.List;

public class Invoice implements Serializable {
    public String id;
    public ClientInfo client;
    public String date;
    public String currency;
    public List<Item> items;


    public String status;
    public List<String> errors;
    public Integer retryCount;
    public String rejectReason;
    public String failReason;
    public Double totalBAM;
    public String rateSource;

    //calculating total amount of price
    public double totalAmount() {
        double sum = 0;
        if (items != null) {
            for (Item item : items) {
                sum += item.quantity * item.unitPrice;
            }
        }
        return sum;
    }

}
