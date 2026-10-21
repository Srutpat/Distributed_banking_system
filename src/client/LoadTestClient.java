package client;

import common.BankService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Spins up many concurrent "clients" (threads, each with its own RMI stub
 * lookup) hammering the SAME two accounts at once with deposits, withdrawals
 * and bidirectional transfers. Because every client talks to one central
 * server, and the server serializes access per-account with fair locks plus
 * deadlock-safe ordered locking for transfers, the total system balance must
 * be identical before and after the storm - regardless of interleaving.
 *
 * This is the practical proof-of-consistency the report/demo should show.
 */
public class LoadTestClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        int opsPerThread = args.length > 3 ? Integer.parseInt(args[3]) : 40;

        Registry registry = LocateRegistry.getRegistry(host, port);
        BankService bootstrap = (BankService) registry.lookup("BankService");

        // Use two fresh accounts dedicated to this run so the result is easy to verify.
        String accA = bootstrap.createAccount("LoadTest-A", 10_000.0);
        String accB = bootstrap.createAccount("LoadTest-B", 10_000.0);
        double expectedTotal = bootstrap.getBalance(accA) + bootstrap.getBalance(accB);

        System.out.println("Seeded " + accA + " and " + accB + " with 10000.00 each.");
        System.out.println("Expected combined total throughout: " + expectedTotal);
        System.out.println("Launching " + threads + " concurrent clients x " + opsPerThread + " ops each...");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger(); // business-rule rejections (e.g. insufficient funds) - expected, not errors
        AtomicInteger failed = new AtomicInteger();   // genuine unexpected errors

        // Deposits/withdrawals are NOT conservative (they create/destroy money by design),
        // only transfers between A and B are. So track net deposits-minus-withdrawals
        // separately and fold it into the expected total; this is what "consistency"
        // actually means here, not a naive "total never changes" check.
        DoubleAdder netDepositsOnA = new DoubleAdder();

        long start = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int workerId = t;
            pool.submit(() -> {
                try {
                    // Each thread gets its OWN stub lookup, simulating an independent client process.
                    Registry r = LocateRegistry.getRegistry(host, port);
                    BankService bank = (BankService) r.lookup("BankService");
                    java.util.Random rnd = new java.util.Random(workerId * 7919L + System.nanoTime());

                    for (int i = 0; i < opsPerThread; i++) {
                        int op = rnd.nextInt(4);
                        double amount = 1 + rnd.nextInt(50); // small amounts relative to the 10000 seed
                        try {
                            switch (op) {
                                case 0 -> { bank.deposit(accA, amount); netDepositsOnA.add(amount); }
                                case 1 -> { bank.withdraw(accA, amount); netDepositsOnA.add(-amount); }
                                case 2 -> bank.transfer(accA, accB, amount);
                                case 3 -> bank.transfer(accB, accA, amount);
                            }
                            succeeded.incrementAndGet();
                        } catch (common.InsufficientFundsException e) {
                            rejected.incrementAndGet(); // fine: correctly refused an overdraft under concurrency
                        }
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.err.println("worker " + workerId + " failed: " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        pool.shutdown();
        long elapsed = System.currentTimeMillis() - start;

        double finalA = bootstrap.getBalance(accA);
        double finalB = bootstrap.getBalance(accB);
        double finalTotal = finalA + finalB;
        double adjustedExpectedTotal = expectedTotal + netDepositsOnA.sum();

        System.out.println();
        System.out.println("================ LOAD TEST RESULT ================");
        System.out.println("Total ops attempted      : " + (threads * opsPerThread));
        System.out.println("Succeeded                 : " + succeeded.get());
        System.out.println("Rejected (expected)       : " + rejected.get() + "  (insufficient funds - correctly refused)");
        System.out.println("Unexpected failures        : " + failed.get());
        System.out.println("Elapsed                    : " + elapsed + " ms");
        System.out.println("Net deposits-withdrawals   : " + netDepositsOnA.sum() + "  (these legitimately change the total)");
        System.out.println("Final balance A            : " + finalA);
        System.out.println("Final balance B            : " + finalB);
        System.out.println("Final combined total       : " + finalTotal);
        System.out.println("Starting combined total    : " + expectedTotal);
        System.out.println("Expected total (start + net deposits/withdrawals): " + adjustedExpectedTotal);
        System.out.println(Math.abs(finalTotal - adjustedExpectedTotal) < 0.001
                ? "RESULT: CONSISTENT - transfers conserved money exactly; deposits/withdrawals matched their own net. No double-spend, no lost updates, no deadlock."
                : "RESULT: *** INCONSISTENCY DETECTED *** - something is wrong with locking!");
        System.out.println("====================================================");
    }
}
