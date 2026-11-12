package org.traccar.game;

import javax.sql.DataSource;

public class GameEffectsH2Test extends GameEffectsTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
