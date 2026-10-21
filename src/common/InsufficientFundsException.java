package common;

/** Thrown when a withdrawal or transfer would overdraw an account. */
public class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String accountId, double requested, double available) {
        super(String.format("Account %s: requested %.2f but only %.2f available", accountId, requested, available));
    }
}
