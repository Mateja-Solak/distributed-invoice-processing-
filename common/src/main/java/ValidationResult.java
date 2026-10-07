
import java.io.Serializable;
import java.util.List;

public class ValidationResult implements Serializable {
    //predstavlja rezultat validacije
    public boolean valid;
    public List<String> errors;
    public String validatorTimestamp;

    public ValidationResult() {
    }

    public ValidationResult(boolean valid, List<String> errors, String validatorTimestamp) {
        this.valid = valid;
        this.errors = errors;
        this.validatorTimestamp = validatorTimestamp;
    }
}
