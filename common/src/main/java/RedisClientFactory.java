
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public final class RedisClientFactory {

    public static JedisPool create() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        return new JedisPool(config, Config.redisHost(), Config.redisPort());
    }

    private RedisClientFactory() {
    }
}
