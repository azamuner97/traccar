package org.traccar.game;

import javax.sql.DataSource;

public class GameOneOffPingH2Test extends GameOneOffPingTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
