package server;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-side account record. Each account owns its own ReentrantLock so that
 * concurrent operations on DIFFERENT accounts never block each other, while
 * operations on the SAME account are serialized for correctness.
 */
public class Account {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final String id;
    private final String holderName;
    private volatile double balance;
    private final ReentrantLock lock = new ReentrantLock(true); // fair lock -> no starvation under load
    private final List<String> history = new CopyOnWriteArrayList<>();

    public Account(String id, String holderName, double openingBalance) {
        this.id = id;
        this.holderName = holderName;
        this.balance = openingBalance;
        history.add(stamp() + " OPEN      balance=" + money(openingBalance));
    }

    public String getId() { return id; }
    public String getHolderName() { return holderName; }
    public double getBalance() { return balance; }
    public ReentrantLock getLock() { return lock; }

    public void credit(double amount, String reason) {
        balance += amount;
        history.add(stamp() + " " + pad(reason) + " +" + money(amount) + "  balance=" + money(balance));
    }

    public void debit(double amount, String reason) {
        balance -= amount;
        history.add(stamp() + " " + pad(reason) + " -" + money(amount) + "  balance=" + money(balance));
    }

    /** Used only during journal replay, before the service accepts remote calls. */
    public void setBalanceForRecovery(double recoveredBalance, String reason, double delta) {
        balance = recoveredBalance;
        history.add(stamp() + " " + pad(reason) + " " + (delta >= 0 ? "+" : "") + money(delta)
                + "  balance=" + money(recoveredBalance));
    }

    public List<String> getHistory() {
        return Collections.unmodifiableList(history);
    }

    private static String pad(String s) {
        return String.format("%-9s", s);
    }

    private static String stamp() {
        return LocalDateTime.now().format(TS);
    }

    private static String money(double v) {
        return String.format("%.2f", v);
    }
}
