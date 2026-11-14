package org.traccar.game;

import javax.sql.DataSource;

public class GameExecutionH2Test extends GameExecutionTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
