package org.traccar.game;

import javax.sql.DataSource;

public class GameSchemaH2Test extends GameSchemaTest {

    @Override
    protected DataSource database(boolean model2) throws Exception {
        DataSource source = GameSchemaFixture.h2();
        if (model2) {
            GameSchemaFixture.apply(source, GameSchemaFixture.MASTER);
        } else {
            GameSchemaFixture.model1(source);
        }
        return source;
    }


}
