package server;

import common.InvalidAmountException;

import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class BankServer {

    public static final int RMI_PORT = 1099;
    public static final String SERVICE_NAME = "BankService";

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("data");

        BankServiceImpl service = new BankServiceImpl(dataDir);

        Registry registry = LocateRegistry.createRegistry(RMI_PORT);
        registry.rebind(SERVICE_NAME, service);

        // Seed a couple of demo accounts only on a totally fresh data directory.
        if (service.listAccounts().isEmpty()) {
            try {
                String a = service.createAccount("Alice", 1000.0);
                String b = service.createAccount("Bob", 500.0);
                System.out.println("[server] seeded demo accounts: Alice=" + a + ", Bob=" + b);
            } catch (InvalidAmountException e) {
                // unreachable with the constants above
            }
        }

        System.out.println("=============================================================");
        System.out.println(" Distributed Banking System - central server is READY");
        System.out.println(" RMI registry on port " + RMI_PORT + ", bound as \"" + SERVICE_NAME + "\"");
        System.out.println(" Data directory: " + dataDir.toAbsolutePath());
        System.out.println(" Accounts on file:");
        for (String line : service.listAccounts()) System.out.println("   " + line);
        System.out.println(" Press Ctrl+C to stop.");
        System.out.println("=============================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[server] shutting down, final balances:");
            try {
                for (String line : service.listAccounts()) System.out.println("   " + line);
                System.out.printf("   TOTAL SYSTEM BALANCE: %.2f%n", service.getTotalSystemBalance());
            } catch (Exception ignored) {}
        }));
    }
}
