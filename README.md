# 🦴 bare-bones

> **bare-bones** *(idiom)*: stripped of text, fluff, or non-essential elements; the bare minimum; the absolute core.

This repository is a curated collection of hardcore production-grade engineering deep dives, stripped down to their absolute core. No marketing fluff, no basic tutorials, and no "hello world" templates. Only minimal reproducible examples of non-trivial system behaviors, silent data corruptions, and architectural pitfalls.

Each directory is a self-contained lab with its own infrastructure (Docker/Testcontainers) and code, allowing you to reproduce, break, and fix complex behaviors locally.

---

## 🗂️ Lab Directory

### 🛢️ Databases & Storage

* **[PostgreSQL 12 Collation Mismatch](./src/test/java/dev/barebones/collation)**
  Reproducing silent B-Tree index corruption during logical/physical migration from CentOS 7 (`glibc 2.17`) to Ubuntu 22.04 (`glibc 2.35`).
    * *Fix validated:* Zero-downtime migration via deterministic **ICU Collations** combined with a cold **Single-User Mode system catalog (`pg_database`) patch**.

---

## 🎯 Philosophy

1. **Isolate:** Every problem must be completely decoupled from external infrastructure.
2. **Reproduce:** If it cannot be consistently reproduced via a single JUnit/Testcontainers run, it doesn't belong here.
3. **Fix:** Do not just break things. Provide a production-ready, verified remediation strategy.
