package dev.barebones.ghost;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;

import java.sql.*;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PostgresSynchronousCommitGhost2Test {
    private static final Logger log = LoggerFactory.getLogger(PostgresSynchronousCommitGhost2Test.class);
    private static final Network network = Network.newNetwork();

    private static final String ETCD_IMAGE = "quay.io/coreos/etcd:v3.5.17";
    private static final String INITIAL_CLUSTER = "etcd-1=http://etcd-1:2380,etcd-2=http://etcd-2:2380,etcd-3=http://etcd-3:2380";

    private static final GenericContainer<?> etcd1 = createEtcdNode("etcd-1");
    private static final GenericContainer<?> etcd2 = createEtcdNode("etcd-2");
    private static final GenericContainer<?> etcd3 = createEtcdNode("etcd-3");

    private static GenericContainer<?> createEtcdNode(String nodeName) {
        String bindAll = String.join(".", "0", "0", "0", "0");

        return new GenericContainer<>(ETCD_IMAGE)
                .withEnv("ETCD_DATA_DIR", "/etcd-data")
                .withEnv("ETCD_LISTEN_PEER_URLS", "http://" + bindAll + ":2380")
                .withEnv("ETCD_LISTEN_CLIENT_URLS", "http://" + bindAll + ":2379")
                .withEnv("ETCD_ADVERTISE_CLIENT_URLS", "http://" + nodeName + ":2379")
                .withEnv("ETCD_INITIAL_CLUSTER", INITIAL_CLUSTER)
                .withEnv("ETCD_INITIAL_CLUSTER_STATE", "new")
                .withEnv("ETCD_INITIAL_CLUSTER_TOKEN", "barebones-etcd-cluster")
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName(nodeName))
                .withNetwork(network)
                .withNetworkAliases(nodeName)
                .withExposedPorts(2379, 2380)
                .withCommand(
                        "/usr/local/bin/etcd",
                        "--name", nodeName,
                        "--initial-advertise-peer-urls", "http://" + nodeName + ":2380"
                )
                .waitingFor(Wait.forHttp("/health")
                        .forPort(2379)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix(nodeName.toUpperCase()));
    }

    private static final GenericContainer<?> pgNode1 = createPatroniNode("pg-node-1");
    private static final GenericContainer<?> pgNode2 = createPatroniNode("pg-node-2");

    private static GenericContainer<?> createPatroniNode(String nodeName) {
        return new GenericContainer<>("local-pg17-patroni")
                .withNetwork(network)
                .withNetworkAliases(nodeName)
                .dependsOn(etcd1, etcd2, etcd3)
                .withExposedPorts(5432, 8008)
                .withCopyToContainer(
                        Transferable.of(generatePatroniConfig(nodeName)),
                        "/etc/patroni/patroni.yml"
                )
                .waitingFor(Wait.forHttp("/patroni")
                        .forPort(8008)
                        .forStatusCodeMatching(code -> code == 200 || code == 503)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix(nodeName.toUpperCase()))
                ;
    }

    @BeforeAll
    static void startCluster() {
        Startables.deepStart(Stream.of(etcd1, etcd2, etcd3)).join();
        pgNode1.start();
        pgNode2.start();
    }

    @AfterAll
    static void stopCluster() {
        pgNode2.stop();
        pgNode1.stop();
        etcd3.stop();
        etcd2.stop();
        etcd1.stop();
    }

    private static String generatePatroniConfig(String nodeName) {
        return String.format("""
                scope: barebones-cluster
                namespace: /db/
                name: %s

                restapi:
                  listen: 0.0.0.0:8008
                  connect_address: %s:8008

                etcd3:
                  hosts: etcd-1:2379,etcd-2:2379,etcd-3:2379

                bootstrap:
                  dcs:
                    ttl: 20
                    loop_wait: 5
                    retry_timeout: 3
                    maximum_lag_on_failover: 1048576
                    synchronous_mode: true
                    synchronous_mode_strict: false
                    synchronous_node_count: 1
                    use_pg_rewind: true
                    postgresql:
                      use_pg_rewind: true
                      use_slots: true
                      parameters:
                        wal_level: replica
                        hot_standby: "on"
                        wal_keep_size: 64MB
                        max_wal_senders: 5
                        max_replication_slots: 5
                        wal_log_hints: "on"
                        synchronous_commit: "on"

                  initdb:
                    - encoding: UTF8
                    - data-checksums

                  pg_hba:
                    - local all all trust
                    - host all all 0.0.0.0/0 trust
                    - host replication replicator 0.0.0.0/0 trust

                postgresql:
                  listen: 0.0.0.0:5432
                  connect_address: %s:5432
                  data_dir: /var/lib/postgresql/17/main
                  bin_dir: /usr/lib/postgresql/17/bin
                  authentication:
                    replication:
                      username: replicator
                      password: rep_pass
                    superuser:
                      username: postgres
                      password: postgres
                  parameters:
                    unix_socket_directories: '/var/run/postgresql'

                tags:
                  nofailover: false
                  noloadbalance: false
                  clonefrom: false
                  nosync: false
                """, nodeName, nodeName, nodeName);
    }

    @Test
    void reproduceGhostCommitAndPatroniRewind() throws Exception {
        String node1Jdbc = "jdbc:postgresql://" + pgNode1.getHost() + ":" + pgNode1.getMappedPort(5432) + "/postgres";
        String node2Jdbc = "jdbc:postgresql://" + pgNode2.getHost() + ":" + pgNode2.getMappedPort(5432) + "/postgres";

        waitForSyncReplication(node1Jdbc);

        try (Connection conn = DriverManager.getConnection(node1Jdbc, "postgres", "postgres");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE orders (id serial PRIMARY KEY, idempotency_key text UNIQUE, amount int);");
        }

        log.info("❄️ Pausing only the walreceiver process on pg-node-2 (kill -STOP)...");
        Container.ExecResult stopWal = pgNode2.execInContainer("sh", "-c", "pkill -STOP -f '[w]alreceiver'");
        assertEquals(0, stopWal.getExitCode(), "Failed to find and pause the walreceiver process");

        try (Connection ghostConn = DriverManager.getConnection(node1Jdbc, "postgres", "postgres");
             Statement st = ghostConn.createStatement()) {
            ghostConn.setAutoCommit(false);
            st.execute("INSERT INTO orders (idempotency_key, amount) VALUES ('tx-ghost-001', 5000);");

            st.setQueryTimeout(2);
            st.execute("COMMIT;");

            // PostgreSQL does not throw an exception here; it emits a SQLWarning instead
            SQLWarning warning = st.getWarnings();
            if (warning == null) {
                warning = ghostConn.getWarnings();
            }
            assertNotNull(warning, "Expected a SQLWarning indicating interrupted synchronous replication!");
            log.warn("⚠️ Caught SQLWarning from PostgreSQL: {} (SQLState: {})", warning.getMessage(), warning.getSQLState());

            // Verify the ghost record is committed locally on the Master
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM orders WHERE idempotency_key = 'tx-ghost-001';")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "The ghost commit must be visible locally on the Master!");
                log.info("👻 GHOST CONFIRMED: Record 'tx-ghost-001' IS committed and visible on the Master (pg-node-1)!");
            }
        }

        // 4. Immediately kill the Master in the main thread (deterministically right after catching the Warning)
        log.info("💀 DISASTER: Pulling the power plug on the Master (docker stop -t 0)!");
        pgNode1.getDockerClient()
                .stopContainerCmd(pgNode1.getContainerId())
                .withTimeout(0)
                .exec();

        log.info("⏳ Waiting for pg-node-2 to promote to the new Leader via Patroni REST API...");
        waitForNodeToBecomeLeader(pgNode2);

        try (Connection newLeaderConn = DriverManager.getConnection(node2Jdbc, "postgres", "postgres");
             Statement st = newLeaderConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM orders WHERE idempotency_key = 'tx-ghost-001';")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "The ghost commit must not exist on the new leader!");
            log.info("🚨 CONFIRMED: Record 'tx-ghost-001' is MISSING on the new Leader (pg-node-2)!");
        }

        log.info("⏰ Unfreezing walreceiver on pg-node-2 (kill -CONT)...");
        pgNode2.execInContainer("sh", "-c", "pkill -CONT -f '[w]alreceiver'");

        log.info("🔄 Restarting the crashed pg-node-1. Patroni will trigger pg_rewind...");
        pgNode1.getDockerClient()
                .startContainerCmd(pgNode1.getContainerId())
                .exec();
        pgNode1.followOutput(new Slf4jLogConsumer(log).withPrefix("PG-NODE-1-REWIND"));

        InspectContainerResponse inspect = pgNode1.getDockerClient().inspectContainerCmd(pgNode1.getContainerId()).exec();
        Map<ExposedPort, Ports.Binding[]> bindings = inspect.getNetworkSettings().getPorts().getBindings();
        Ports.Binding[] portBindings8008 = bindings.get(ExposedPort.tcp(8008));
        Ports.Binding[] portBindings5432 = bindings.get(ExposedPort.tcp(5432));

        int freshPort8008 = Integer.parseInt(portBindings8008[0].getHostPortSpec());
        int freshPort5432 = Integer.parseInt(portBindings5432[0].getHostPortSpec());

        waitForNodeToBecomeReplica(pgNode1.getHost(), freshPort8008);

        String newNode1Jdbc = "jdbc:postgresql://" + pgNode1.getHost() + ":" + freshPort5432 + "/postgres";

        // FINAL BLOW: Verify the old Master — pg_rewind must have permanently wiped the ghost commit from its disk
        try (Connection rewoundNodeConn = DriverManager.getConnection(newNode1Jdbc, "postgres", "postgres");
             Statement st = rewoundNodeConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM orders WHERE idempotency_key = 'tx-ghost-001';")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "pg_rewind should have erased the unreplicated commit!");
            log.info("💥 FINALE: pg_rewind succeeded! Ghost commit 'tx-ghost-001' has been permanently wiped from both nodes!");
        }
    }

    private void waitForSyncReplication(String masterJdbc) throws Exception {
        for (int i = 0; i < 30; i++) {
            try (Connection conn = DriverManager.getConnection(masterJdbc, "postgres", "postgres");
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sync_state FROM pg_stat_replication WHERE application_name = 'pg-node-2';")) {
                if (rs.next() && "sync".equals(rs.getString("sync_state"))) {
                    log.info("✅ pg-node-2 is now in sync with pg-node-1!");
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Replica did not become SYNC within 30 seconds!");
    }

    private void waitForNodeToBecomeLeader(GenericContainer<?> node) throws Exception {
        String patroniLeaderUrl = "http://" + node.getHost() + ":" + node.getMappedPort(8008) + "/leader";
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(patroniLeaderUrl))
                .build();

        for (int i = 0; i < 45; i++) {
            try {
                java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    log.info("👑 Node {} is now the Leader!", node.getNetworkAliases().get(0));
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Node did not become leader within 45 seconds");
    }

    private void waitForNodeToBecomeReplica(String host, int port8008) throws Exception {
        String url = "http://" + host + ":" + port8008 + "/patroni";
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .build();

        for (int i = 0; i < 45; i++) {
            try {
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.body().contains("\"role\": \"replica\"") && response.body().contains("\"state\": \"running\"")) {
                    log.info("🔄 Node {} is now a replica (HTTP 200)!", host);
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Node did not become a replica after 45 seconds!");
    }
}
