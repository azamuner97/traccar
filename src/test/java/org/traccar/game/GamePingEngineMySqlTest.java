package org.traccar.game;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;

/** Runs the ping engine tests on a MySQL test server ({@link GameMySqlFixture}). */
@EnabledIfEnvironmentVariable(named = GameMySqlFixture.URL, matches = ".*\\{database}.*")
public class GamePingEngineMySqlTest extends GamePingEngineTest {

    private final GameMySqlFixture mysql = new GameMySqlFixture();

    @Override
    protected DataSource database() throws Exception {
        return mysql.create(true);
    }

    @AfterAll
    void dropDatabases() throws Exception {
        mysql.dropAll();
    }

}
