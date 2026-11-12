package org.traccar.game;

import javax.sql.DataSource;

public class GameScheduleH2Test extends GameScheduleTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
