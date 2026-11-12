package org.traccar.game;

import javax.sql.DataSource;

public class GameStatusH2Test extends GameStatusTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
