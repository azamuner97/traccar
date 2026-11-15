package org.traccar.game;

import javax.sql.DataSource;

public class GameBreakRunnerH2Test extends GameBreakRunnerTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
