package org.traccar.game;

import javax.sql.DataSource;

public class GameDeviceListH2Test extends GameDeviceListTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
