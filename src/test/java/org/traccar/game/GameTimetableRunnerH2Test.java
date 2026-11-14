package org.traccar.game;

import javax.sql.DataSource;

public class GameTimetableRunnerH2Test extends GameTimetableRunnerTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
