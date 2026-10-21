package common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Remote interface exposed by the central bank server.
 * All methods can throw RemoteException (network/RMI failure) plus
 * domain-specific checked exceptions for business rule violations.
 */
public interface BankService extends Remote {

    /** Creates a new account with the given holder name and opening balance. Returns generated account ID. */
    String createAccount(String holderName, double openingBalance) throws RemoteException, InvalidAmountException;

    /** Deposits amount into accountId. Returns new balance. */
    double deposit(String accountId, double amount) throws RemoteException, AccountNotFoundException, InvalidAmountException;

    /** Withdraws amount from accountId. Returns new balance. */
    double withdraw(String accountId, double amount) throws RemoteException, AccountNotFoundException, InvalidAmountException, InsufficientFundsException;

    /** Atomically transfers amount from fromId to toId. Returns new balance of fromId. */
    double transfer(String fromId, String toId, double amount) throws RemoteException, AccountNotFoundException, InvalidAmountException, InsufficientFundsException;

    /** Returns current balance. */
    double getBalance(String accountId) throws RemoteException, AccountNotFoundException;

    /** Returns the holder's name for an account. */
    String getHolderName(String accountId) throws RemoteException, AccountNotFoundException;

    /** Returns the chronological transaction history for an account. */
    List<String> getTransactionHistory(String accountId) throws RemoteException, AccountNotFoundException;

    /** Returns a snapshot summary line ("id, holder, balance") for every account. */
    List<String> listAccounts() throws RemoteException;

    /** Returns the sum of every account's balance — used to verify global consistency. */
    double getTotalSystemBalance() throws RemoteException;
}
