# Интеграционный тест-стенд: Воспроизведение и устранение повреждения индексов B-Tree (Collation Mismatch) при миграции PostgreSQL 12

Репозиторий содержит сквозной интеграционный JUnit-тест на базе **Testcontainers** и **JDBC**, который наглядно воспроизводит критическую уязвимость СУБД PostgreSQL — **тихое повреждение текстовых индексов B-Tree** при смене операционной системы и версий библиотеки `glibc` (миграция с CentOS 7 на Ubuntu 22.04).

Тест не только фиксирует порчу данных, но и полностью валидирует промышленный сценарий бесшовного лечения проблемы с нулевым простоем (**Zero-Downtime Failover**) через детерминированные **ICU-локали** в связке с модификацией системного каталога `pg_database` в режиме **Single-User**.

---

## 💥 Суть проблемы (Collation Mismatch)

По умолчанию PostgreSQL использует системный провайдер локалей операционной системы (`libc`). При бинарной репликации (`pg_basebackup`) или физическом переносе файлов СУБД со старой ОС на новую, файлы B-Tree индексов копируются «один в один».

Однако топология дерева B-Tree, построенная на старых весах символов (например, в `glibc 2.17` на CentOS 7), кардинально расходится с логикой сравнения строк в новой системе (например, `glibc 2.35` на Ubuntu 22.04). Спецсимволы (дефисы, пробелы) и буквы «е/ё» меняют свои веса.

При поиске диапазонов (`Range Scan`) или точечных равенствах (`Index Only Scan`), планировщик на новой ОС сворачивает на развилках страниц B-Tree **не в ту сторону**, промахивается мимо целевых данных и возвращает приложению **неполный срез строк (тихая потеря данных)**, хотя физически строки лежат в таблице.

---

## 🏗️ Архитектура тестового стенда

Тест-стенд полностью адаптирован под нативный запуск на процессорах **Apple Silicon (Mac M1/M2/M3 / ARM64)** без падений эмуляции Rosetta 2:

1. **Мастер (`local-pg12-master-bullseye`):**
    * **База:** CentOS 7.9 (`linux/amd64` с принудительным модификатором платформы в Java).
    * **Окружение:** Системная библиотека **`glibc 2.17`**.
    * **Локаль СУБД:** `en_US.UTF-8` (дефолтная рабочая кодовая страница для CentOS SCL).
2. **Реплика (`local-pg12-replica-ubuntu22`):**
    * **База:** Чистая Ubuntu 22.04, собранная локально через Dockerfile.
    * **Окружение:** Новая библиотека **`glibc 2.35`**.
    * **Оркестрация:** Специальный контролирующий bash-скрипт на **`PID 1`** (`tail -f /dev/null`). Он удерживает сетевые порты Testcontainers активными при полной остановке и холодных перезапусках СУБД из Java-кода.

---

## 🛠️ Предварительная сборка локальных образов

Перед запуском Java-теста необходимо собрать кастомные Docker-образы в терминале вашего Mac. Это изолирует инфраструктурные проблемы с авторизацией OpenShift/RedHat и архивными репозиториями.

### 1. Сборка Мастера (CentOS 7 + PostgreSQL 12)
Создайте `Dockerfile.master`:
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
Соберите образ:
```bash
docker build -t local-pg12-master-bullseye -f Dockerfile.master .
```

### 2. Сборка Реплики (Ubuntu 22.04 + PostgreSQL 12)
Создайте `Dockerfile.replica`:
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

# Контролирующий скрипт для динамического pg_basebackup и удержания PID 1
RUN echo '#!/bin/bash\n\
if [ ! -f /var/lib/postgresql/12/main/PG_VERSION ]; then\n\
  echo "Динамически стягиваем бэкап с работающего pg-master..."\n\
  PGPASSWORD=rep_pass pg_basebackup -h pg-master -U replicator -D /var/lib/postgresql/12/main/ -Fp -Xs -R\n\
  chown -R postgres:postgres /var/lib/postgresql/12/main\n\
  echo "local all all trust" > /etc/postgresql/12/main/pg_hba.conf\n\
  echo "host all all 0.0.0.0/0 trust" >> /etc/postgresql/12/main/pg_hba.conf\n\
  echo "host replication all 0.0.0.0/0 trust" >> /etc/postgresql/12/main/pg_hba.conf\n\
  chown postgres:postgres /etc/postgresql/12/main/pg_hba.conf\n\
fi\n\
\n\
echo "Запускаем PostgreSQL под пользователем postgres..."\n\
su - postgres -c "/usr/lib/postgresql/12/bin/postgres -D /var/lib/postgresql/12/main -c config_file=/etc/postgresql/12/main/postgresql.conf -c hba_file=/etc/postgresql/12/main/pg_hba.conf -c listen_addresses=* &"\n\
\n\
echo "Скрипт переходит в бесконечный цикл удержания PID 1 в Docker..."\n\
tail -f /dev/null' > /start-replica.sh && chmod +x /start-replica.sh

CMD ["/start-replica.sh"]
```
Соберите образ:
```bash
docker build -t local-pg12-replica-ubuntu22 -f Dockerfile.replica .
```

---

## 🚀 Жизненный цикл интеграционного теста

Метод `@Test` в классе `PostgresCollationMigrationTest` автоматически проводит СУБД через **5 фаз миграции**:

### Фаза 1: Инициализация и наполнение Мастера (CentOS 7)
* Поднимается контейнер CentOS 7.
* Внедряются сетевые правила `trust` на самый верх `pg_hba.conf` для обхода жестких политик OpenShift.
* СУБД перезагружается, Java подключается по чистому TCP-каналу (параметр `sslmode=disable` через объект `Properties` для изоляции race-conditions сетевого стека Docker под macOS).
* Создается таблица `test_corruption` и стандартный B-Tree индекс.
* Вставляется тестовый паттерн, чувствительный к весу дефисов: `('аб'), ('а-б'), ('ав'), ('а-в')`.
* Выполняется запрос диапазона `WHERE name >= 'а-б' AND name <= 'а-в'`.
* **Результат Мастера:** `[а-б, ав, а-в]` (Старая `glibc 2.17` отсекла `аб`, так как дефис почти полностью игнорировался при сортировке букв).

### Фаза 2: Паника на Реплике (Ubuntu 22.04)
* Стартует контейнер Реплики на Ubuntu, скрипт `start-replica.sh` динамически стягивает бэкап через `pg_basebackup` и входит в режим `Standby (Streaming WAL)`.
* Выполняется аналогичный запрос диапазона. Новая `glibc 2.35` пытается применить свои веса к старому физическому дереву страниц.
* **Результат Реплики (БАГ):** Индекс слепнет, ломает бинарный поиск и возвращает искаженный результат. Тест фиксирует расхождение с Мастером.

### Фаза 3: Внедрение ICU-локали на живом Мастере
* На живом Мастере (CentOS) регистрируется независимая детерминированная ICU локаль:
  ```sql
  CREATE COLLATION icu_ru (provider = icu, locale = 'ru-RU', deterministic = true);
  ```
  *(Флаг `deterministic = true` критически важен в PostgreSQL 12 для поддержки B-Tree операторов сравнения).*

* Конкурентно строится новый параллельный ICU-индекс, который автоматически улетает на Реплику по WAL-потоку:
  ```sql
  CREATE INDEX idx_test_name_icu ON test_corruption (name COLLATE icu_ru);
  ```

### Фаза 4: Бесшовный Failover и Рокировка старых индексов
* Эмулируется аварийное переключение (Реплика повышается до нового Мастера командой `pg_ctl promote`).
* Подключаемся к новой СУБД на Ubuntu и выполняем рок-н-ролл индексов на лету:
  ```sql
  DROP INDEX idx_test_name;
  ALTER INDEX idx_test_name_icu RENAME TO idx_test_name;
  ANALYZE test_corruption;
  ```
* Запрос диапазона выполняется повторно по **оригинальному имени индекса**, но уже на базе ICU.
* **Результат Нового Мастера (УСПЕХ):** База возвращает **`[а-б, аб, а-в]`** — абсолютно полный, математически точный и консистентный Unicode-ответ, один в один совпадающий с дисковым `Seq Scan`. Данные спасены без применения тяжелого длительного `REINDEX`.

### 💥 Фаза 5: Атомный Single-User патч глобальных дефолтов базы данных
* Для защиты будущих релизов от дефолтного `LC_COLLATE` старого шаблона, унаследованного из CentOS, СУБД на Ubuntu останавливается через `pg_ctl stop`.
* Через STDIN в автономный бинарный режим `postgres --single` вбрасывается ядерный апдейт метаданных системного каталога:
  ```sql
  SET allow_system_table_mods = on;
  UPDATE pg_database SET datcollate = 'C.UTF-8', datctype = 'C.UTF-8' WHERE datname = 'postgres';
  UPDATE pg_database SET datcollate = 'C.UTF-8', datctype = 'C.UTF-8' WHERE datname = 'template1';
  ```
* СУБД запускается обратно в мультипользовательский режим с явным подтягиванием оригинальных конфигурационных файлов Ubuntu из `/etc/`.
* Тестируется создание **новой таблицы и нового B-Tree индекса БЕЗ указания COLLATE**. Проверяется, что глобальный дефолт успешно переключился на побайтовую ASCII кодировку `C.UTF-8`, полностью защитив новые объекты от влияния `glibc 2.35` на веса спецсимволов.

---

## 📈 Памятка для применения в Продакшене

Если вы планируете перенос этого решения на боевые кластеры СУБД, следуйте трем правилам:
1. **Фоновая сборка:** В проде всегда используйте ключевое слово `CONCURRENTLY` как при создании (`CREATE INDEX CONCURRENTLY`), так и при удалении (`DROP INDEX CONCURRENTLY`), чтобы операции не блокировали бизнес-запросы на запись/чтение.
2. **Флаг детерминированности:** Помните, что в PostgreSQL 12 текстовый B-Tree индекс не соберется по ICU-локали, если явно не указать `deterministic = true`.
