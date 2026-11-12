package org.traccar.game;

import javax.sql.DataSource;

public class GameStateH2Test extends GameStateTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
