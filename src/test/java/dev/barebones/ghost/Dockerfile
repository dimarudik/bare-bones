FROM postgres:17-bookworm

# 1. Устанавливаем Python и системные зависимости для сборки/работы Patroni
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    curl \
    jq \
    procps \
    vim-tiny \
    && rm -rf /var/lib/apt/lists/*

# 2. Ставим Patroni с поддержкой etcd и актуальным драйвером psycopg3
# Используем флаг --break-system-packages, так как в Debian Bookworm действует PEP 668
RUN pip3 install --no-cache-dir --break-system-packages \
    "patroni[etcd3]" \
    "psycopg[binary]"

# 3. Создаем директории для конфигов Patroni и данных PostgreSQL с правильными правами
RUN mkdir -p /etc/patroni /var/lib/postgresql/17/main && \
    chown -R postgres:postgres /etc/patroni /var/lib/postgresql/17/main && \
    chmod 700 /var/lib/postgresql/17/main

# 4. Переключаемся на пользователя postgres (Patroni категорически запрещает запуск от root)
USER postgres

EXPOSE 5432 8008

# 5. Запускаем Patroni как PID 1, передавая путь к конфигу (его мы смонтируем из Java)
CMD ["patroni", "/etc/patroni/patroni.yml"]
