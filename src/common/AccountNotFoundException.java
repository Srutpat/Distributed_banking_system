package common;

/** Thrown when an operation references an account ID that does not exist on the server. */
public class AccountNotFoundException extends Exception {
    public AccountNotFoundException(String accountId) {
        super("No such account: " + accountId);
    }
}
