package org.traccar.game;

import com.mysql.cj.jdbc.MysqlDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Throwaway databases on a MySQL test server. Never point it at a server that holds data: it creates and drops its
 * own databases. Tests that use it are enabled with
 *
 * <pre>
 * TRACCAR_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3310/{database}?serverTimezone=UTC&amp;useSSL=false&amp;...'
 * TRACCAR_TEST_MYSQL_USER=root TRACCAR_TEST_MYSQL_PASSWORD=...
 * </pre>
 *
 * where {@code {database}} is replaced by a new database name for every database a test needs. A throwaway
 * server from the image that {@code compose.yml} pins keeps the development database out of reach:
 *
 * <pre>
 * docker run -d --rm --name game-schema-mysql -p 127.0.0.1:3310:3306 --tmpfs /var/lib/mysql \
 *     -e MYSQL_ROOT_PASSWORD=... mysql@sha256:be18eb9dc45eea9b86cb74f8c68ab92ce8569ecc37ea4e6fade02e37036c5ff4
 * </pre>
 */
public final class GameMySqlFixture {

    /** The environment variable that enables the MySQL tests; its value must contain {@code {database}}. */
    public static final String URL = "TRACCAR_TEST_MYSQL_URL";

    private final List<String> databases = new ArrayList<>();

    private static MysqlDataSource source(String database) {
        MysqlDataSource source = new MysqlDataSource();
        source.setURL(System.getenv(URL).replace("{database}", database));
        source.setUser(System.getenv("TRACCAR_TEST_MYSQL_USER"));
        source.setPassword(System.getenv("TRACCAR_TEST_MYSQL_PASSWORD"));
        return source;
    }

    /** A new, empty database with the master changelog applied, or only its model-1 part when asked. */
    public DataSource create(boolean model2) throws Exception {
        String name = "game_schema_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = source("").getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        databases.add(name);
        DataSource source = source(name);
        if (model2) {
            GameSchemaFixture.apply(source, GameSchemaFixture.MASTER);
        } else {
            GameSchemaFixture.model1(source);
        }
        return source;
    }

    /** Drops every database this fixture created. */
    public void dropAll() throws Exception {
        try (Connection connection = source("").getConnection(); Statement statement = connection.createStatement()) {
            for (String name : databases) {
                statement.execute("DROP DATABASE IF EXISTS " + name);
            }
        }
        databases.clear();
    }

}
