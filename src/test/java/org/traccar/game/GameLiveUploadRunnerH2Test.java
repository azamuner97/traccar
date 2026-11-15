package org.traccar.game;

import javax.sql.DataSource;

public class GameLiveUploadRunnerH2Test extends GameLiveUploadRunnerTest {

    @Override
    protected DataSource database() throws Exception {
        return GameSchemaFixture.model2();
    }

}
