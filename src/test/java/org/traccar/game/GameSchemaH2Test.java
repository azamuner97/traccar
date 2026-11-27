package org.traccar.game;

import javax.sql.DataSource;

public class GameSchemaH2Test extends GameSchemaTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
