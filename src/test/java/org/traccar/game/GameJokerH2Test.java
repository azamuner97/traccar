package org.traccar.game;

import javax.sql.DataSource;

public class GameJokerH2Test extends GameJokerTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
