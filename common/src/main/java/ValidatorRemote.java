
import java.rmi.Remote;
import java.rmi.RemoteException;
    //RMI interfejs za Validation
public interface ValidatorRemote extends Remote {
    ValidationResult validate(Invoice invoice) throws RemoteException;
}
