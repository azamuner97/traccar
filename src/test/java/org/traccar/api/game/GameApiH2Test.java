package org.traccar.api.game;

import org.traccar.game.GameSchemaFixture;

import javax.sql.DataSource;

public class GameApiH2Test extends GameApiTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
