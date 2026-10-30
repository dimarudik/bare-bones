# Integration Test Stand: Reproducing and Resolving B-Tree Index Corruption (Collation Mismatch) during PostgreSQL 12 Migration

This repository contains an end-to-end integration JUnit test powered by **Testcontainers** and **JDBC** that clearly reproduces a critical PostgreSQL vulnerability — **silent textual B-Tree index corruption** triggered by changes in the operating system and `glibc` library versions (migrating from CentOS 7 to Ubuntu 22.04).

The test not only captures data corruption but also fully validates a production-grade **Zero-Downtime Failover** remediation scenario using deterministic **ICU Collations** combined with a cold **Single-User Mode system catalog (`pg_database`) patch**.

---

## 💥 The Core Issue (Collation Mismatch)

By default, PostgreSQL relies on the operating system's locale provider (`libc`). During physical replication (`pg_basebackup`) or physical data volume migration from an old OS to a new one, the B-Tree index files are copied exactly "as is".

However, the B-Tree topology built on old character weights (e.g., `glibc 2.17` on CentOS 7) completely diverges from the string comparison logic of the new system (e.g., `glibc 2.35` on Ubuntu 22.04). Special characters (hyphens, spaces) and specific letters shift their relative weights.

When performing a range search (`Range Scan`) or point lookups (`Index Only Scan`), the query planner on the new OS takes the **wrong turn** at B-Tree page splits, misses the target data, and returns an **incomplete result set to the application (silent data loss)**, even though the rows physically exist in the table.

---

## 🏗️ Test Stand Architecture

The test stand is fully optimized for native execution on **Apple Silicon (Mac M1/M2/M3 / ARM64)** processors without Rosetta 2 emulation failures:

1. **Master (`local-pg12-master-bullseye`):**
    * **Database:** CentOS 7.9 (`linux/amd64` forced via Java container modifier).
    * **Environment:** System library **`glibc 2.17`**.
    * **Database Locale:** `en_US.UTF-8` (the default working character set for CentOS SCL).
2. **Replica (`local-pg12-replica-ubuntu22`):**
    * **Database:** Clean Ubuntu 22.04 built locally via a Dockerfile.
    * **Environment:** New library **`glibc 2.35`**.
    * **Orchestration:** A custom supervising bash script running as **`PID 1`** (`tail -f /dev/null`). It keeps Testcontainers network ports active during full stops and cold restarts of the database process initiated by the Java code.

---

## 🛠️ Local Image Pre-building

Before running the Java test, you must build the custom Docker images in your Mac's terminal. This isolates infrastructure issues related to OpenShift/RedHat authentication and archived repositories.

### 1. Building the Master (CentOS 7 + PostgreSQL 12)
Create the `Dockerfile.master`:
```dockerfile
FROM centos/postgresql-12-centos7
USER root
ENV POSTGRESQL_RECOVERY_LOCALE=en_US.UTF-8
ENV POSTGRESQL_DEFAULTS_LOCALE=en_US.UTF-8
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8
ENV POSTGRESQL_MAX_WAL_SENDERS=10
ENV POSTGRESQL_MAX_REPLICATION_SLOTS=10
USER 26
```
Build the image:
```bash
docker build -t local-pg12-master-bullseye -f Dockerfile.master .
```

### 2. Building the Replica (Ubuntu 22.04 + PostgreSQL 12)
Create the `Dockerfile.replica`:
```dockerfile
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    curl \
    ca-certificates \
    lsb-release \
    locales \
    language-pack-ru \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

RUN locale-gen ru_RU.UTF-8 && \
    locale-gen en_US.UTF-8 && \
    update-locale LANG=en_US.UTF-8

RUN apt-get update && apt-get install -y postgresql-common && \
    yes | /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh

RUN apt-get update && apt-get install -y \
    postgresql-12 \
    postgresql-contrib-12 \
    && rm -rf /var/lib/apt/lists/*

RUN rm -rf /var/lib/postgresql/12/main/*
RUN echo "hot_standby = on" >> /etc/postgresql/12/main/postgresql.conf

EXPOSE 5432

RUN echo '#!/bin/bash\n\
if [ ! -f /var/lib/postgresql/12/main/PG_VERSION ]; then\n\
  PGPASSWORD=rep_pass pg_basebackup -h pg-master -U replicator -D /var/lib/postgresql/12/main/ -Fp -Xs -R\n\
  chown -R postgres:postgres /var/lib/postgresql/12/main\n\
  echo "local all all trust" > /etc/postgresql/12/main/pg_hba.conf\n\
  echo "host all all 0.0.0.0/0 trust" >> /etc/postgresql/12/main/pg_hba.conf\n\
  echo "host replication all 0.0.0.0/0 trust" >> /etc/postgresql/12/main/pg_hba.conf\n\
  chown postgres:postgres /etc/postgresql/12/main/pg_hba.conf\n\
fi\n\
\n\
su - postgres -c "/usr/lib/postgresql/12/bin/postgres -D /var/lib/postgresql/12/main -c config_file=/etc/postgresql/12/main/postgresql.conf -c hba_file=/etc/postgresql/12/main/pg_hba.conf -c listen_addresses=* &"\n\
\n\
tail -f /dev/null' > /start-replica.sh && chmod +x /start-replica.sh

CMD ["/start-replica.sh"]
```
Build the image:
```bash
docker build -t local-pg12-replica-ubuntu22 -f Dockerfile.replica .
```

---

## 🚀 Integration Test Lifecycle

The `@Test` method in the `PostgresCollationMigrationTest` class automatically guides the database through **5 migration phases**:

### Phase 1: Initialization and Population of the Master (CentOS 7)
* The CentOS 7 container is spun up.
* Network `trust` rules are injected at the very top of `pg_hba.conf` to bypass restrictive OpenShift policies.
* The database restarts, and Java connects via a clean TCP channel (using the `sslmode=disable` parameter inside the `Properties` object to isolate Docker network stack race conditions under macOS).
* The `test_corruption` table and a standard B-Tree index are created.
* A test pattern sensitive to hyphen weights is inserted: `('аб'), ('а-б'), ('ав'), ('а-в')`.
* A range query is executed: `WHERE name >= 'а-б' AND name <= 'а-в'`.
* **Master Result:** `[а-б, ав, а-в]` (The old `glibc 2.17` excluded `аб` because hyphens were almost completely ignored during character sorting).

### Phase 2: Panic on the Replica (Ubuntu 22.04)
* The Ubuntu Replica container starts, and the `start-replica.sh` script dynamically pulls the backup via `pg_basebackup` and enters `Standby (Streaming WAL)` mode.
* The exact same range query is executed. The new `glibc 2.35` attempts to apply its new weights to the old physical page tree.
* **Replica Result (BUG):** The index goes blind, breaking the binary search and returning a corrupted result. The test captures this mismatch against the Master.

### Phase 3: Provisioning the ICU Locale on the Live Master
* A decoupled, deterministic ICU collation is registered on the live Master (CentOS):
  ```sql
  CREATE COLLATION icu_ru (provider = icu, locale = 'ru-RU', deterministic = true);
  ```
  *(The `deterministic = true` flag is critically important in PostgreSQL 12 to support B-Tree comparison operators).*
* A new parallel ICU index is built concurrently and automatically streams to the Replica via the WAL pipeline:
  ```sql
  CREATE INDEX idx_test_name_icu ON test_corruption (name COLLATE icu_ru);
  ```

### Phase 4: Seamless Failover and Hot-Swapping Old Indexes
* Failover is emulated (the Replica is promoted to the new Master using the `pg_ctl promote` command).
* Connecting to the new database on Ubuntu and executing a hot-swap of the indexes on the fly:
  ```sql
  DROP INDEX idx_test_name;
  ALTER INDEX idx_test_name_icu RENAME TO idx_test_name;
  ANALYZE test_corruption;
  ```
* The range query is executed again using the **original index name**, but this time powered by the ICU engine.
* **New Master Result (SUCCESS):** The database returns **`[а-б, аб, а-в]`**—a completely full, mathematically precise, and consistent Unicode response that matches the disk-level `Seq Scan` exactly. The data is recovered without triggering a heavy, time-consuming `REINDEX`.

### 💥 Phase 5: Atomic Single-User Patch of Global Database Defaults
* To safeguard future releases from the default `LC_COLLATE` inherited from the old CentOS template, the database process on Ubuntu is stopped via `pg_ctl stop`.
* A hard metadata update of the system catalog is fed through STDIN into the standalone binary mode (`postgres --single`):
  ```sql
  SET allow_system_table_mods = on;
  UPDATE pg_database SET datcollate = 'C.UTF-8', datctype = 'C.UTF-8' WHERE datname = 'postgres';
  UPDATE pg_database SET datcollate = 'C.UTF-8', datctype = 'C.UTF-8' WHERE datname = 'template1';
  ```
* The database process is started back up into standard multi-user mode, explicitly pulling the original Ubuntu configuration files from `/etc/`.
* Creation of a **new table and a new B-Tree index WITHOUT specifying a COLLATE** is tested. The verification ensures that the global database default has successfully switched to the byte-by-byte ASCII encoding `C.UTF-8`, fully shielding new database objects from the impact of `glibc 2.35` character sorting weights.

---

## 📈 Production Production Deployment Rules

If you plan to apply this remediation strategy to your live database clusters, adhere to these three rules:
1. **Background Indexing:** In a production environment, always use the `CONCURRENTLY` keyword during both creation (`CREATE INDEX CONCURRENTLY`) and removal (`DROP INDEX CONCURRENTLY`) to prevent blocking parallel read/write business traffic.
2. **Determinism Flag:** Keep in mind that in PostgreSQL 12, a text-based B-Tree index will fail to compile under an ICU collation unless `deterministic = true` is explicitly passed.
