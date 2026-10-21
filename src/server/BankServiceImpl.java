package server;

import common.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/**
 * RMI bank service with a durable, checksummed transaction journal.
 * A complete journal record is forced to disk before its mutation is applied
 * in memory. On startup, valid journal records newer than the snapshot sequence
 * are replayed. Mutating commits are serialized so snapshots cannot capture a
 * mixed state from different concurrent operations.
 */
public class BankServiceImpl extends UnicastRemoteObject implements BankService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String SNAPSHOT_MARKER = "#lastSeq=";

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1000);
    private final Path dataDir;
    private final Path snapshotFile;
    private final Path logFile;
    private final Object commitLock = new Object();
    private long lastSequence = 0;

    public BankServiceImpl(Path dataDir) throws RemoteException {
        super();
        this.dataDir = dataDir;
        this.snapshotFile = dataDir.resolve("accounts.csv");
        this.logFile = dataDir.resolve("transactions.log");
        try {
            Files.createDirectories(dataDir);
            loadSnapshot();
            replayJournal();
        } catch (IOException e) {
            throw new RemoteException("Could not initialize bank persistence", e);
        }
    }

    @Override
    public String createAccount(String holderName, double openingBalance) throws RemoteException, InvalidAmountException {
        if (holderName == null || holderName.isBlank()) throw new InvalidAmountException(openingBalance);
        if (!Double.isFinite(openingBalance) || openingBalance < 0) throw new InvalidAmountException(openingBalance);
        synchronized (commitLock) {
            String id = "ACC" + idSeq.getAndIncrement();
            long seq = lastSequence + 1;
            String encodedHolder = Base64.getUrlEncoder().withoutPadding().encodeToString(holderName.getBytes(StandardCharsets.UTF_8));
            appendJournal(seq + "|CREATE|" + id + "|" + encodedHolder + "|" + Double.toString(openingBalance));
            accounts.put(id, new Account(id, holderName, openingBalance));
            lastSequence = seq;
            persistSnapshot();
            System.out.println("[server] created " + id + " for " + holderName + " with opening balance " + openingBalance);
            return id;
        }
    }

    @Override
    public double deposit(String accountId, double amount) throws RemoteException, AccountNotFoundException, InvalidAmountException {
        validateAmount(amount);
        Account acc = require(accountId);
        acc.getLock().lock();
        try {
            synchronized (commitLock) {
                double newBalance = acc.getBalance() + amount;
                ensureFinite(newBalance);
                long seq = lastSequence + 1;
                appendJournal(seq + "|DEPOSIT|" + accountId + "|" + Double.toString(amount) + "|" + Double.toString(newBalance));
                acc.credit(amount, "DEPOSIT");
                lastSequence = seq;
                persistSnapshot();
                return acc.getBalance();
            }
        } finally { acc.getLock().unlock(); }
    }

    @Override
    public double withdraw(String accountId, double amount) throws RemoteException, AccountNotFoundException, InvalidAmountException, InsufficientFundsException {
        validateAmount(amount);
        Account acc = require(accountId);
        acc.getLock().lock();
        try {
            synchronized (commitLock) {
                double current = acc.getBalance();
                if (current < amount) throw new InsufficientFundsException(accountId, amount, current);
                double newBalance = current - amount;
                long seq = lastSequence + 1;
                appendJournal(seq + "|WITHDRAW|" + accountId + "|" + Double.toString(amount) + "|" + Double.toString(newBalance));
                acc.debit(amount, "WITHDRAW");
                lastSequence = seq;
                persistSnapshot();
                return acc.getBalance();
            }
        } finally { acc.getLock().unlock(); }
    }

    @Override
    public double transfer(String fromId, String toId, double amount) throws RemoteException, AccountNotFoundException, InvalidAmountException, InsufficientFundsException {
        validateAmount(amount);
        if (fromId.equals(toId)) throw new InvalidAmountException(amount);
        Account from = require(fromId), to = require(toId);
        Account first = fromId.compareTo(toId) < 0 ? from : to;
        Account second = fromId.compareTo(toId) < 0 ? to : from;
        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                synchronized (commitLock) {
                    double fromBalance = from.getBalance(), toBalance = to.getBalance();
                    if (fromBalance < amount) throw new InsufficientFundsException(fromId, amount, fromBalance);
                    double newFrom = fromBalance - amount, newTo = toBalance + amount;
                    ensureFinite(newFrom); ensureFinite(newTo);
                    long seq = lastSequence + 1;
                    appendJournal(seq + "|TRANSFER|" + fromId + "|" + toId + "|" + Double.toString(amount)
                            + "|" + Double.toString(newFrom) + "|" + Double.toString(newTo));
                    from.debit(amount, "XFER-OUT");
                    to.credit(amount, "XFER-IN");
                    lastSequence = seq;
                    persistSnapshot();
                    return from.getBalance();
                }
            } finally { second.getLock().unlock(); }
        } finally { first.getLock().unlock(); }
    }

    @Override public double getBalance(String accountId) throws RemoteException, AccountNotFoundException {
        synchronized (commitLock) { return require(accountId).getBalance(); }
    }
    @Override public String getHolderName(String accountId) throws RemoteException, AccountNotFoundException { return require(accountId).getHolderName(); }
    @Override public List<String> getTransactionHistory(String accountId) throws RemoteException, AccountNotFoundException { return require(accountId).getHistory(); }

    @Override
    public List<String> listAccounts() {
        List<String> out = new ArrayList<>();
        for (Account a : accounts.values()) out.add(String.format("%s | %-20s | %.2f", a.getId(), a.getHolderName(), a.getBalance()));
        Collections.sort(out);
        return out;
    }

    @Override
    public double getTotalSystemBalance() {
        synchronized (commitLock) {
            double total = 0;
            for (Account a : accounts.values()) total += a.getBalance();
            return total;
        }
    }

    private Account require(String accountId) throws AccountNotFoundException {
        Account a = accounts.get(accountId);
        if (a == null) throw new AccountNotFoundException(accountId);
        return a;
    }

    private static void validateAmount(double amount) throws InvalidAmountException {
        if (!Double.isFinite(amount) || amount <= 0) throw new InvalidAmountException(amount);
    }
    private static void ensureFinite(double value) throws RemoteException {
        if (!Double.isFinite(value)) throw new RemoteException("Balance overflow; operation rejected");
    }

    /** Append one checksummed record and force it to stable storage before changing memory. */
    private void appendJournal(String payload) throws RemoteException {
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        String line = "TX|" + payload + "|" + String.format(Locale.ROOT, "%08x", crc.getValue()) + "\n";
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(line);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (IOException e) {
            throw new RemoteException("Transaction was not applied because its journal could not be made durable", e);
        }
    }

    /** Atomically replace the snapshot. The journal remains the recovery source if this fails. */
    private void persistSnapshot() {
        Path temp = dataDir.resolve("accounts.csv.tmp");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(SNAPSHOT_MARKER).append(lastSequence).append('\n');
            sb.append("id,holderName,balance\n");
            List<Account> ordered = new ArrayList<>(accounts.values());
            ordered.sort(Comparator.comparing(Account::getId));
            for (Account a : ordered) {
                String holder = "b64:" + Base64.getUrlEncoder().withoutPadding().encodeToString(a.getHolderName().getBytes(StandardCharsets.UTF_8));
                sb.append(a.getId()).append(',').append(holder).append(',').append(Double.toString(a.getBalance())).append('\n');
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temp, snapshotFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, snapshotFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            System.err.println("[server] WARNING: snapshot update failed; durable journal will recover it on restart: " + e.getMessage());
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    private void loadSnapshot() throws IOException {
        if (!Files.exists(snapshotFile)) return;
        List<String> lines = Files.readAllLines(snapshotFile, StandardCharsets.UTF_8);
        int start = 0;
        if (!lines.isEmpty() && lines.get(0).startsWith(SNAPSHOT_MARKER)) {
            try { lastSequence = Long.parseLong(lines.get(0).substring(SNAPSHOT_MARKER.length())); }
            catch (NumberFormatException e) { throw new IOException("Invalid snapshot sequence", e); }
            start = 1;
        }
        int maxSeq = 999;
        for (int i = start + 1; i < lines.size(); i++) { // skip CSV header
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",", 3);
            if (parts.length != 3) continue;
            String id = parts[0], holder = parts[1];
            if (holder.startsWith("b64:")) {
                try { holder = new String(Base64.getUrlDecoder().decode(holder.substring(4)), StandardCharsets.UTF_8); }
                catch (IllegalArgumentException ignored) {}
            }
            double balance;
            try { balance = Double.parseDouble(parts[2]); } catch (NumberFormatException e) { continue; }
            accounts.put(id, new Account(id, holder, balance));
            try { maxSeq = Math.max(maxSeq, Integer.parseInt(id.replace("ACC", ""))); } catch (NumberFormatException ignored) {}
        }
        idSeq.set(maxSeq + 1);
        System.out.println("[server] restored " + accounts.size() + " accounts from " + snapshotFile + " (snapshot seq=" + lastSequence + ")");
    }

    private void replayJournal() throws IOException {
        if (!Files.exists(logFile)) return;
        long maxSeq = lastSequence;
        int recovered = 0;
        for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            if (!line.startsWith("TX|")) continue; // safely ignore legacy human-readable log lines
            int checksumSeparator = line.lastIndexOf('|');
            if (checksumSeparator < 0) continue;
            String payload = line.substring(3, checksumSeparator);
            String suppliedChecksum = line.substring(checksumSeparator + 1).trim();
            CRC32 crc = new CRC32(); crc.update(payload.getBytes(StandardCharsets.UTF_8));
            if (!String.format(Locale.ROOT, "%08x", crc.getValue()).equalsIgnoreCase(suppliedChecksum)) continue; // incomplete/corrupt tail
            String[] p = payload.split("\\|", -1);
            if (p.length < 3) continue;
            long seq;
            try { seq = Long.parseLong(p[0]); } catch (NumberFormatException e) { continue; }
            maxSeq = Math.max(maxSeq, seq);
            if (seq <= lastSequence) continue;
            try {
                switch (p[1]) {
                    case "CREATE" -> {
                        String id = p[2];
                        String holder = new String(Base64.getUrlDecoder().decode(p[3]), StandardCharsets.UTF_8);
                        double balance = Double.parseDouble(p[4]);
                        accounts.putIfAbsent(id, new Account(id, holder, balance));
                    }
                    case "DEPOSIT" -> {
                        Account a = accounts.get(p[2]); if (a == null) break;
                        double amount = Double.parseDouble(p[3]), target = Double.parseDouble(p[4]);
                        a.setBalanceForRecovery(target, "RECOVER-DEPOSIT", amount);
                    }
                    case "WITHDRAW" -> {
                        Account a = accounts.get(p[2]); if (a == null) break;
                        double amount = Double.parseDouble(p[3]), target = Double.parseDouble(p[4]);
                        a.setBalanceForRecovery(target, "RECOVER-WITHDRAW", -amount);
                    }
                    case "TRANSFER" -> {
                        Account from = accounts.get(p[2]), to = accounts.get(p[3]); if (from == null || to == null) break;
                        double amount = Double.parseDouble(p[4]), fromTarget = Double.parseDouble(p[5]), toTarget = Double.parseDouble(p[6]);
                        from.setBalanceForRecovery(fromTarget, "RECOVER-XFER-OUT", -amount);
                        to.setBalanceForRecovery(toTarget, "RECOVER-XFER-IN", amount);
                    }
                    default -> { continue; }
                }
                lastSequence = seq;
                recovered++;
            } catch (RuntimeException e) {
                System.err.println("[server] skipped invalid journal record: " + e.getMessage());
            }
        }
        lastSequence = Math.max(lastSequence, maxSeq);
        if (recovered > 0) {
            System.out.println("[server] recovered " + recovered + " committed transaction(s) from journal");
            persistSnapshot();
        }
        int maxAccount = 999;
        for (String id : accounts.keySet()) {
            try { maxAccount = Math.max(maxAccount, Integer.parseInt(id.replace("ACC", ""))); } catch (NumberFormatException ignored) {}
        }
        idSeq.set(Math.max(idSeq.get(), maxAccount + 1));
    }
}
