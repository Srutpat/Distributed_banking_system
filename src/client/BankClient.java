package client;

import common.*;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.Scanner;

public class BankClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;

        Registry registry = LocateRegistry.getRegistry(host, port);
        BankService bank = (BankService) registry.lookup("BankService");

        Scanner sc = new Scanner(System.in);
        System.out.println("Connected to bank server at " + host + ":" + port);

        boolean running = true;
        while (running) {
            printMenu();
            System.out.print("> ");
            String choice = sc.nextLine().trim();
            try {
                switch (choice) {
                    case "1" -> {
                        System.out.print("Holder name: ");
                        String name = sc.nextLine().trim();
                        System.out.print("Opening balance: ");
                        double bal = Double.parseDouble(sc.nextLine().trim());
                        String id = bank.createAccount(name, bal);
                        System.out.println("Created account " + id);
                    }
                    case "2" -> {
                        System.out.print("Account ID: ");
                        String id = sc.nextLine().trim();
                        System.out.print("Amount: ");
                        double amt = Double.parseDouble(sc.nextLine().trim());
                        double newBal = bank.deposit(id, amt);
                        System.out.printf("Deposited. New balance: %.2f%n", newBal);
                    }
                    case "3" -> {
                        System.out.print("Account ID: ");
                        String id = sc.nextLine().trim();
                        System.out.print("Amount: ");
                        double amt = Double.parseDouble(sc.nextLine().trim());
                        double newBal = bank.withdraw(id, amt);
                        System.out.printf("Withdrew. New balance: %.2f%n", newBal);
                    }
                    case "4" -> {
                        System.out.print("From account ID: ");
                        String from = sc.nextLine().trim();
                        System.out.print("To account ID: ");
                        String to = sc.nextLine().trim();
                        System.out.print("Amount: ");
                        double amt = Double.parseDouble(sc.nextLine().trim());
                        double newBal = bank.transfer(from, to, amt);
                        System.out.printf("Transferred. New balance of %s: %.2f%n", from, newBal);
                    }
                    case "5" -> {
                        System.out.print("Account ID: ");
                        String id = sc.nextLine().trim();
                        System.out.printf("Balance: %.2f (held by %s)%n", bank.getBalance(id), bank.getHolderName(id));
                    }
                    case "6" -> {
                        System.out.print("Account ID: ");
                        String id = sc.nextLine().trim();
                        List<String> hist = bank.getTransactionHistory(id);
                        hist.forEach(System.out::println);
                    }
                    case "7" -> {
                        List<String> all = bank.listAccounts();
                        all.forEach(System.out::println);
                        System.out.printf("TOTAL SYSTEM BALANCE: %.2f%n", bank.getTotalSystemBalance());
                    }
                    case "0" -> running = false;
                    default -> System.out.println("Unknown option.");
                }
            } catch (AccountNotFoundException | InvalidAmountException | InsufficientFundsException e) {
                System.out.println("ERROR: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("UNEXPECTED ERROR: " + e);
            }
        }
        System.out.println("Bye.");
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("---- Distributed Banking System ----");
        System.out.println("1. Create account");
        System.out.println("2. Deposit");
        System.out.println("3. Withdraw");
        System.out.println("4. Transfer");
        System.out.println("5. Check balance");
        System.out.println("6. Transaction history");
        System.out.println("7. List all accounts + total system balance");
        System.out.println("0. Exit");
    }
}
