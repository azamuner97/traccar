package org.traccar.game;

import javax.sql.DataSource;

public class GameServicesH2Test extends GameServicesTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
