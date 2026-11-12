package org.traccar.game;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

/**
 * Databases built from the real Liquibase changelogs, so service tests exercise the schema that ships instead of
 * hand-written DDL.
 *
 * <p>The master changelog includes game data model 2 ({@link #MODEL_2}) since the switchover. {@link #model1} builds
 * a database as it was before, so tests can follow the migration from model 1.
 */
public final class GameSchemaFixture {

    public static final Path SCHEMA = Path.of("schema");
    public static final String MASTER = "changelog-master.xml";
    public static final String MODEL_2 = "changelog-game-model-2.xml";
    private static final String MODEL_1_MASTER = "changelog-master-model-1.xml";

    private GameSchemaFixture() {
    }

    /** An empty in-memory H2 database that lives until the JVM ends. */
    public static JdbcDataSource h2() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:game-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    /** An H2 database with the full model-2 schema. */
    public static JdbcDataSource model2() throws Exception {
        JdbcDataSource source = h2();
        apply(source, MASTER);
        return source;
    }

    /**
     * Applies the master changelog without model 2: the schema of model 1. Applying {@link #MASTER} afterwards runs
     * only model 2, as the switchover does on an existing database.
     */
    public static void model1(DataSource source) throws Exception {
        String master = Files.readString(SCHEMA.resolve(MASTER));
        String include = "<include file=\"" + MODEL_2 + "\" relativeToChangelogFile=\"true\" />";
        if (!master.contains(include)) {
            throw new IllegalStateException("The master changelog does not include " + MODEL_2);
        }
        Path directory = Files.createTempDirectory("game-model-1");
        Files.writeString(directory.resolve(MODEL_1_MASTER), master.replace(include, ""));
        try {
            apply(source, MODEL_1_MASTER, new CompositeResourceAccessor(
                    new DirectoryResourceAccessor(directory), new DirectoryResourceAccessor(SCHEMA)));
        } finally {
            Files.delete(directory.resolve(MODEL_1_MASTER));
            Files.delete(directory);
        }
    }

    /**
     * Applies one changelog of {@link #SCHEMA} the way {@code DatabaseModule} does.
     *
     * <p>On H2 the migration session is never closed: H2 2.4.240 binds a CHECK constraint to the session that
     * created it, and once that session closes, evaluating the constraint fails with "database is already closed"
     * until the database is reopened. In-memory test databases are never reopened.
     */
    public static void apply(DataSource source, String changelog) throws Exception {
        apply(source, changelog, new DirectoryResourceAccessor(SCHEMA));
    }

    private static void apply(DataSource source, String changelog, ResourceAccessor accessor) throws Exception {
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
        try (Liquibase liquibase = new Liquibase(changelog, accessor, database)) {
            liquibase.update(new Contexts());
        }
    }

}
