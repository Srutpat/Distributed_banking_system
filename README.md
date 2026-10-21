# Distributed Banking System (Java RMI)

A central bank server exposed over **Java RMI**. Any number of clients — on
the same machine or different machines on the network — connect concurrently
and perform balance inquiries, deposits, withdrawals, and inter-account
transfers, with locking designed to preserve balance consistency and avoid transfer deadlocks under concurrent access.

This satisfies the "Distributed Banking System" brief: *clients perform
balance inquiry, deposits, and withdrawals on a central server ensuring
consistency* — and goes further by adding deadlock-safe transfers, an
checksummed write-ahead journal + atomic snapshots for crash recovery, and a dedicated load-test client that checks consistency under heavy concurrency.

## Why this demonstrates distributed systems concepts

| Concept | Where |
|---|---|
| Remote procedure invocation | `common.BankService` (RMI remote interface), stub/skeleton generated automatically by the JVM |
| Client-server architecture | One `BankServiceImpl` on the server; any number of independent `BankClient` / `LoadTestClient` JVMs as clients |
| Concurrency control | Per-account `ReentrantLock` (fair, so no starvation) in `server.Account` |
| Deadlock avoidance | `transfer()` always locks the two involved accounts in a fixed global order (by account ID), never in caller-supplied order — the textbook fix for the "dining philosophers"-style deadlock that naive bidirectional transfers cause |
| Consistency under concurrency | `client.LoadTestClient` runs concurrent operations and compares the observed total with the expected total; this is strong test evidence, not a formal proof for every execution |
| Durability / recovery | A checksummed journal record is forced to disk before the in-memory mutation; startup replays valid records newer than the snapshot sequence |

## Project layout

```
distributed-banking-system/
  src/
    common/   BankService.java              (RMI remote interface)
              AccountNotFoundException.java
              InsufficientFundsException.java
              InvalidAmountException.java
    server/   Account.java                  (locking + balance + history)
              BankServiceImpl.java          (RMI service, locking, persistence)
              BankServer.java               (registry bootstrap, main())
    client/   BankClient.java               (interactive console client)
              LoadTestClient.java           (concurrency stress test)
  build.sh / run-server.sh / run-client.sh / run-loadtest.sh
  data/       created at runtime: accounts.csv (snapshot), transactions.log (audit trail)
```

No external libraries or build tools are used — pure JDK (`java.rmi`,
`java.util.concurrent`, `java.nio.file`) — so there is nothing to download,
no Maven/Gradle, nothing version-sensitive. Any JDK 11+ works.

## Quick start

```bash
# 1. Build everything
chmod +x build.sh run-server.sh run-client.sh run-loadtest.sh
./build.sh

# 2. Start the server (keep this terminal open)
./run-server.sh
```

The server seeds two demo accounts on first run (Alice: ACC1000, Bob:
ACC1001, if `data/` is empty) and prints their IDs.

In a **second terminal**, talk to it:

```bash
# Interactive menu-driven client
./run-client.sh
# or, to connect to a server on another machine:
./run-client.sh 192.168.1.50 1099
```

Menu options: create account, deposit, withdraw, transfer, check balance,
view transaction history, list all accounts + total system balance.

### Prove consistency under concurrent load

In a third terminal, with the server still running:

```bash
./run-loadtest.sh                 # 50 clients x 40 ops = 2000 concurrent operations
./run-loadtest.sh localhost 1099 100 100   # heavier: 10,000 concurrent operations
```

It seeds two fresh accounts with 10,000 each, then fires concurrent random
deposits / withdrawals / bidirectional transfers from many simulated
clients (each with its **own RMI stub**, exactly like independent client
processes). At the end it compares the actual final total against the
mathematically expected total and reports **CONSISTENT** or flags an inconsistency. This is empirical evidence for the tested run, not a mathematical proof of all possible schedules.

### Run multiple real client processes at once

Open several terminals and run `./run-client.sh` in each — they all talk to
the same server simultaneously, exactly like a real distributed deployment.
To run across machines, start the server on one host, open port `1099` on
its firewall, and point clients at `./run-client.sh <server-ip> 1099`.

### Restart and verify persistence

Stop the server (`Ctrl+C`), then `./run-server.sh` again. It loads the latest snapshot and replays valid, checksummed journal records with sequence numbers newer than that snapshot. The journal is forced to disk before each mutation, so a crash after the journal write but before the snapshot can be recovered on restart. The snapshot is replaced atomically where the filesystem supports it.

### Crash recovery design and limits

Each committed mutation is represented by a single checksummed `TX` journal line. The server calls `FileChannel.force(true)` before applying the mutation in memory. The snapshot stores the latest applied journal sequence and is written to a temporary file before replacement. On startup, valid journal entries newer than the snapshot sequence are replayed; incomplete or checksum-invalid tail records are ignored. Reads that report balances or account lists synchronize with the commit section so they do not observe the two balance updates halfway through a transfer.

This protects against common process-crash windows, assuming the filesystem and storage device honor force/rename semantics. It is not a claim of protection against every hardware/filesystem failure, and the load test is not a formal verification of all possible executions. The journal currently has no compaction/rotation, and transaction history shown in the UI is in-memory history rather than a fully reconstructed audit view.

## Design notes worth mentioning in your report/viva

- **Why per-account locks instead of one global lock on the whole bank?**
  A single global lock would serialize *every* operation system-wide, even
  on unrelated accounts, killing throughput. Per-account locks let deposits
  on account A and withdrawals on account B happen fully in parallel.
- **Why fair locks (`new ReentrantLock(true)`)?** Under sustained
  concurrent load on a hot account, an unfair lock can let some threads
  starve indefinitely. Fairness trades a little throughput for guaranteed
  progress for every client.
- **Why lock ordering for transfers?** Two threads doing `transfer(A,B,10)`
  and `transfer(B,A,5)` at the same time would deadlock if each locked its
  "from" account first (classic circular wait). Always locking by account
  ID order breaks the cycle.
- **Why `CopyOnWriteArrayList` for transaction history?** History is
  read-heavy (`getTransactionHistory`) and append-only; a copy-on-write
  list avoids needing a separate lock for reads while writes are already
  protected by the account's own lock.
