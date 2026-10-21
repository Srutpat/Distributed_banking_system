package common;

/** Thrown when an amount passed to a banking operation is zero, negative, or otherwise invalid. */
public class InvalidAmountException extends Exception {
    public InvalidAmountException(double amount) {
        super("Invalid amount: " + amount + " (must be > 0)");
    }
}
