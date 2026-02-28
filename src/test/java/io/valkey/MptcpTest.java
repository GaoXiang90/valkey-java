package io.valkey;

import static org.junit.Assert.*;

import java.net.Socket;
import org.junit.Test;

public class MptcpTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;

    @Test
    public void testMptcpIsAvailable() {
        boolean available = Mptcp.isAvailable();
        System.out.println("[Test 1] MPTCP available: " + available);
        assertTrue("MPTCP should be available on this system", available);
    }

    @Test
    public void testEnableMptcpOnSocket() throws Exception {
        Socket socket = new Socket();
        try {
            Mptcp.enable(socket);
            System.out.println("[Test 2] MPTCP enabled on raw socket successfully");
        } finally {
            socket.close();
        }
    }

    @Test(expected = NullPointerException.class)
    public void testEnableMptcpNullSocket() throws Exception {
        Mptcp.enable(null);
    }

    @Test
    public void testValkeyConnectionWithMptcp() {
        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .mptcp(true)
                .build();

        try (Connection conn = new Connection(new HostAndPort(HOST, PORT), config)) {
            conn.ping();
            System.out.println("[Test 4] Valkey PING over MPTCP connection: PONG");
        }
    }

    @Test
    public void testJedisOperationsWithMptcp() {
        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .mptcp(true)
                .build();

        try (Jedis jedis = new Jedis(new HostAndPort(HOST, PORT), config)) {
            // SET / GET
            String setResult = jedis.set("mptcp:test:key1", "hello-mptcp");
            assertEquals("OK", setResult);
            System.out.println("[Test 5a] SET mptcp:test:key1 = hello-mptcp -> " + setResult);

            String getResult = jedis.get("mptcp:test:key1");
            assertEquals("hello-mptcp", getResult);
            System.out.println("[Test 5b] GET mptcp:test:key1 -> " + getResult);

            // INCR
            jedis.set("mptcp:test:counter", "0");
            Long incrResult = jedis.incr("mptcp:test:counter");
            assertEquals(Long.valueOf(1), incrResult);
            System.out.println("[Test 5c] INCR mptcp:test:counter -> " + incrResult);

            // DEL
            Long delResult = jedis.del("mptcp:test:key1", "mptcp:test:counter");
            assertEquals(Long.valueOf(2), delResult);
            System.out.println("[Test 5d] DEL -> " + delResult);
        }
    }

    @Test
    public void testConnectionPoolWithMptcp() {
        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .mptcp(true)
                .build();

        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(5);
        poolConfig.setMaxIdle(3);

        try (JedisPooled jedis = new JedisPooled(poolConfig, new HostAndPort(HOST, PORT), config)) {
            for (int i = 0; i < 10; i++) {
                String key = "mptcp:pool:key" + i;
                jedis.set(key, "value" + i);
                String val = jedis.get(key);
                assertEquals("value" + i, val);
            }
            System.out.println("[Test 6] Connection pool with MPTCP: 10 SET/GET operations succeeded");

            // Cleanup
            for (int i = 0; i < 10; i++) {
                jedis.del("mptcp:pool:key" + i);
            }
        }
    }

    @Test
    public void testMptcpVsTcpConnections() {
        JedisClientConfig tcpConfig = DefaultJedisClientConfig.builder()
                .mptcp(false)
                .build();

        JedisClientConfig mptcpConfig = DefaultJedisClientConfig.builder()
                .mptcp(true)
                .build();

        try (Jedis tcpJedis = new Jedis(new HostAndPort(HOST, PORT), tcpConfig);
             Jedis mptcpJedis = new Jedis(new HostAndPort(HOST, PORT), mptcpConfig)) {

            tcpJedis.set("mptcp:compare:tcp", "tcp-value");
            mptcpJedis.set("mptcp:compare:mptcp", "mptcp-value");

            // Cross-read: each connection type can see data written by the other.
            assertEquals("mptcp-value", tcpJedis.get("mptcp:compare:mptcp"));
            assertEquals("tcp-value", mptcpJedis.get("mptcp:compare:tcp"));

            System.out.println("[Test 7] TCP and MPTCP connections both work and see same data");

            // Cleanup
            tcpJedis.del("mptcp:compare:tcp", "mptcp:compare:mptcp");
        }
    }

    @Test
    public void testMptcpConfigCopy() {
        JedisClientConfig original = DefaultJedisClientConfig.builder()
                .mptcp(true)
                .connectionTimeoutMillis(5000)
                .build();

        DefaultJedisClientConfig copy = DefaultJedisClientConfig.copyConfig(original);
        assertTrue("MPTCP flag should be preserved in copy", copy.isMptcp());
        assertEquals(5000, copy.getConnectionTimeoutMillis());
        System.out.println("[Test 8] MPTCP config copyConfig preserves mptcp=true");
    }

    @Test
    public void testDefaultConfigMptcpDisabled() {
        JedisClientConfig config = DefaultJedisClientConfig.builder().build();
        assertFalse("MPTCP should be disabled by default", config.isMptcp());
        System.out.println("[Test 9] Default config has mptcp=false");
    }

    @Test
    public void testPipelineWithMptcp() {
        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .mptcp(true)
                .build();

        try (Jedis jedis = new Jedis(new HostAndPort(HOST, PORT), config)) {
            Pipeline pipeline = jedis.pipelined();
            for (int i = 0; i < 100; i++) {
                pipeline.set("mptcp:pipeline:" + i, String.valueOf(i));
            }
            pipeline.sync();

            // Spot-check a few values written by the pipeline.
            assertEquals("0",  jedis.get("mptcp:pipeline:0"));
            assertEquals("50", jedis.get("mptcp:pipeline:50"));
            assertEquals("99", jedis.get("mptcp:pipeline:99"));

            System.out.println("[Test 10] Pipeline 100 SET operations over MPTCP succeeded");

            // Cleanup
            for (int i = 0; i < 100; i++) {
                jedis.del("mptcp:pipeline:" + i);
            }
        }
    }
}

