# 👻 Integration Test Stand: Synchronous Commit Ghosts & `pg_rewind` Rollbacks in PostgreSQL 17 + Patroni

This directory contains an end-to-end integration JUnit test powered by **Testcontainers** and **JDBC** that reproduces one of the most insidious failure modes in high-availability PostgreSQL clusters: **the silent disappearance of a locally committed transaction** when a client disconnects during synchronous replication.

The test not only proves data loss following an automatic failover and `pg_rewind` execution, but also outlines an architectural pattern to safeguard applications against this state of uncertainty.

---

## 💥 The Core Issue (The Ghost Commit Paradox)

In PostgreSQL's architecture, transaction commitment (`COMMIT`) executes strictly in the following order:
1. Writing and flushing (`fsync`) changes to the Master's local WAL log.
2. Updating the transaction status in `pg_xact` to `committed` (at this exact millisecond, the data is already visible to other sessions on the Master).
3. Waiting for a network acknowledgment from the synchronous replica (`synchronous_commit = on`).

If the synchronous replica hangs at step 3, and the client application aborts the query due to a timeout or `Ctrl+C` (`Statement.cancel()`):
* **The JDBC Trap:** PostgreSQL **does not throw a `SQLException`**, because the commit is already finalized locally. Instead, the driver receives a **`SQLWarning` (SQLState `01000`)**: `canceling wait for synchronous replication due to user request`. Most ORMs (Spring, Hibernate) ignore `getWarnings()` and treat the transaction as successful.
* **The `pg_rewind` Death Sentence:** If the Master crashes right at this moment ("pulling the power plug") and Patroni fails over to the replica (which never received this commit), bringing the old Master back online will trigger **`pg_rewind`**. It synchronizes the timelines and **permanently wipes the ghost commit** from the former Master's disk.

---

## 🏗️ Test Stand Architecture

The stand is fully adapted for native execution on **Apple Silicon (ARM64)** and consists of 5 containers inside an isolated Docker network:
1. **`etcd` Cluster (3 nodes — `etcd-1`, `etcd-2`, `etcd-3`):** Distributed Configuration Store (DCS) for quorum consensus and leader lock management.
2. **Node `pg-node-1` (PostgreSQL 17 + Patroni as PID 1):** Initial cluster Leader.
3. **Node `pg-node-2` (PostgreSQL 17 + Patroni as PID 1):** Synchronous replica (`sync_state = 'sync'`).

---

## 🛠️ Image Pre-building

Before running the test, build the custom PostgreSQL 17 image with Patroni installed from the `dev/barebones/ghost` directory:

```bash
docker build -t local-pg17-patroni .
```

---

## 🚀 Integration Test Lifecycle

The `reproduceGhostCommitAndPatroniRewind()` method guides the cluster through **5 stages**:

1. **Cluster Initialization:** Spins up 3 `etcd` nodes and 2 Patroni nodes running in `synchronous_mode: true`. The test waits until `pg_stat_replication` reports the `sync` state.
2. **Surgical Replication Freeze:** Sends a `SIGSTOP` signal (`pkill -STOP -f '[w]alreceiver'`) on replica `pg-node-2` to freeze **only the WAL receiver process**, allowing Patroni and PostgreSQL on the replica to stay alive and healthy.
3. **`Ctrl+C` Simulation & `SQLWarning` Interception:** Executes an `INSERT` and `COMMIT` on the Master. After 2 seconds, a background thread invokes `st.cancel()`. The test captures the `SQLWarning` (`01000`) and executes a `SELECT` to prove the record is already committed locally on the Master.
4. **Hard Master Crash & Failover:** Immediately kills the `pg-node-1` container via Docker API (`stop -t 0`). Once the `etcd` TTL expires, replica `pg-node-2` promotes to the new Leader. A query confirms the ghost record is missing on the new Leader.
5. **Old Master Recovery & `pg_rewind`:** Restarts `pg-node-1`. Patroni detects the timeline divergence, automatically runs `pg_rewind`, and demotes the node to a replica. A final `SELECT` against `pg-node-1` proves the ghost commit has been permanently wiped from both servers.

---

## 🛡️ Production Remediation Strategy (How to Live With It)

Patroni settings (including `synchronous_mode_strict: true`) do not prevent the Master from committing locally before receiving a replica acknowledgment. The only reliable protection lies at the application architecture level using the **Reconciliation Queue** pattern:

1. **Mandatory `SQLWarning` Interception:** In a custom `TransactionManager` or JDBC wrapper, inspect both `connection.getWarnings()` and `statement.getWarnings()` after every `commit()` for SQLState `01000` (alongside catching network drops `08xxx`).
2. **Idempotency (`idempotency_key`):** Every critical operation must carry a client-generated UUID backed by a strict `UNIQUE` index in the database.
3. **Asynchronous Reconciliation Instead of Immediate Retry:** Upon catching a `SQLWarning` or network drop during commit, never immediately re-run the `INSERT` (risk of duplicates) nor mark the operation as definitively failed.
    * Return **`202 Accepted` (Processing)** to the caller.
    * Persist the transaction context and `idempotency_key` into an **external reconciliation queue** (Kafka, Redis, or a local persistent log — never the same database).
    * A background worker using exponential backoff polls the current Leader via `SELECT` by `idempotency_key`. If the record exists, confirm the operation; if it is still missing after the cluster stabilizes and `pg_rewind` completes, safely re-execute the insert using the exact same key.
