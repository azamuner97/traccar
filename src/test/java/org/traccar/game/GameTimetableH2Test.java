package org.traccar.game;

import javax.sql.DataSource;

public class GameTimetableH2Test extends GameTimetableTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
