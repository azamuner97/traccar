package org.traccar.game;

import javax.sql.DataSource;

public class GamePingEngineH2Test extends GamePingEngineTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
