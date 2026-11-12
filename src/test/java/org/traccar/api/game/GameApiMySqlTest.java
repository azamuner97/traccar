package org.traccar.api.game;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.traccar.game.GameMySqlFixture;

import javax.sql.DataSource;

/** Runs the step-4 REST resource tests on a MySQL test server ({@link GameMySqlFixture}). */
@EnabledIfEnvironmentVariable(named = GameMySqlFixture.URL, matches = ".*\\{database}.*")
public class GameApiMySqlTest extends GameApiTest {

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
