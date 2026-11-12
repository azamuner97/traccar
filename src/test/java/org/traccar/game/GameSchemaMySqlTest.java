package org.traccar.game;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;

/** Runs the schema rules on a MySQL test server ({@link GameMySqlFixture}). */
@EnabledIfEnvironmentVariable(named = GameMySqlFixture.URL, matches = ".*\\{database}.*")
public class GameSchemaMySqlTest extends GameSchemaTest {

    private final GameMySqlFixture mysql = new GameMySqlFixture();

    @Override
    protected DataSource database(boolean model2) throws Exception {
        return mysql.create(model2);
    }

    @AfterAll
    void dropDatabases() throws Exception {
        mysql.dropAll();
    }

}
