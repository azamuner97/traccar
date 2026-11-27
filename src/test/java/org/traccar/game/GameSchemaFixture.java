package org.traccar.game;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

/**
 * Databases built from the real Liquibase changelogs, so service tests exercise the schema that ships instead of
 * hand-written DDL.
 */
public final class GameSchemaFixture {

    public static final Path SCHEMA = Path.of("schema");
    public static final String MASTER = "changelog-master.xml";

    private GameSchemaFixture() {
    }

    /** An empty in-memory H2 database that lives until the JVM ends. */
    private static JdbcDataSource h2() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:game-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    /** An H2 database with the master changelog applied. */
    public static JdbcDataSource model2() throws Exception {
        JdbcDataSource source = h2();
        apply(source, MASTER);
        return source;
    }

    /**
     * Applies one changelog of {@link #SCHEMA} the way {@code DatabaseModule} does.
     *
     * <p>On H2 the migration session is never closed: H2 2.4.240 binds a CHECK constraint to the session that
     * created it, and once that session closes, evaluating the constraint fails with "database is already closed"
     * until the database is reopened. In-memory test databases are never reopened.
     */
    public static void apply(DataSource source, String changelog) throws Exception {
        System.setProperty("liquibase.analytics.enabled", "false");
        Connection connection = source.getConnection();
        if (source instanceof JdbcDataSource) {
            Connection open = connection;
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            return null;
                        }
                        try {
                            return method.invoke(open, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase(changelog, new DirectoryResourceAccessor(SCHEMA), database)) {
            liquibase.update(new Contexts());
        }
    }

}
