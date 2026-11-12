package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;

import javax.sql.DataSource;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Who hears of a committed game change: {@link GameServerPublisher} resolves member invalidations to accounts from
 * member rows, keeps proximity circles to hunters and the spectator, and never throws after a commit.
 */
public class GameServerPublisherTest {

    private final ConnectionManager connections = mock(ConnectionManager.class);
    private final CacheManager cache = mock(CacheManager.class);
    private final GamePushOutbox outbox = mock(GamePushOutbox.class);
    private DataSource source;
    private GameServerPublisher publisher;
    private long session;
    private long player;
    private long hunter;
    private long spectator;
    private long retired;
    private long playerMember;

    private long insert(String sql, Object... params) throws Exception {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private long account(String name) throws Exception {
        return insert("INSERT INTO tc_users (name, email) VALUES (?, ?)", name, name);
    }

    @BeforeEach
    void setup() throws Exception {
        source = GameSchemaFixture.model2();
        GameTransactions transactions = new GameTransactions(source);
        var sessions = new GameSessionService(transactions, new ObjectMapper());
        var members = new GameMemberService(transactions);
        session = sessions.create("Publisher", 5, 5, JsonNodeFactory.instance.objectNode()).id();
        long other = sessions.create("Other", 5, 5, JsonNodeFactory.instance.objectNode()).id();
        player = account("player");
        hunter = account("hunter");
        spectator = account("spectator");
        retired = account("retired");
        playerMember = members.plan(session, GameMember.PLAYER, null, "Player").id();
        members.bindAccount(session, playerMember, player);
        members.bindAccount(session, members.plan(session, GameMember.HUNTER, null, "Hunter").id(), hunter);
        members.bindAccount(session, members.plan(session, GameMember.SPECTATOR, null, "Spectator").id(),
                spectator);
        long leaving = members.plan(session, GameMember.SUPPORTER, null, "Leaving").id();
        members.bindAccount(session, leaving, retired);
        members.retire(session, leaving);
        members.plan(session, GameMember.PLAYER, null, "Without account");
        members.bindAccount(other, members.plan(other, GameMember.PLAYER, null, "Elsewhere").id(),
                account("elsewhere"));
        publisher = new GameServerPublisher(transactions, cache, connections, outbox);
    }

    @Test
    void sessionWideInvalidationsReachTheActiveMembersAccounts() {
        publisher.invalidate(new GamePublisher.Invalidation(session, GamePublisher.STATUS, null));
        verify(connections).updateGameUsers(true, Set.of(player, hunter, spectator), GamePublisher.STATUS);
    }

    @Test
    void proximityCirclesReachOnlyHuntersAndTheSpectator() {
        publisher.invalidate(new GamePublisher.Invalidation(session, GamePublisher.PROXIMITY, null));
        verify(connections).updateGameUsers(true, Set.of(hunter, spectator), GamePublisher.PROXIMITY);
        publisher.proximityAccess(session, Set.of(player));
        verify(connections).updateGameAccess(true, Set.of(player));
    }

    @Test
    void memberInvalidationsReachOnlyTheNamedMembers() {
        publisher.invalidate(new GamePublisher.Invalidation(session, GamePublisher.JOKERS, Set.of(playerMember)));
        verify(connections).updateGameUsers(true, Set.of(player), GamePublisher.JOKERS);
        publisher.invalidate(new GamePublisher.Invalidation(session, GamePublisher.JOKERS, Set.of(-1L)));
        verify(connections).updateGameUsers(true, Set.of(), GamePublisher.JOKERS);
        publisher.accounts(Set.of(retired), GamePublisher.STATUS);
        verify(connections).updateGameUsers(true, Set.of(retired), GamePublisher.STATUS);
        publisher.operators("game-controller");
        verify(connections).updateGameAdmin(true, "game-controller");
    }

    @Test
    void positionsAndPushesGoToCachesAndTheOutboxAndFailuresNeverEscape() {
        Position position = new Position();
        position.setDeviceId(7);
        publisher.positions(List.of(7L), List.of(position));
        verify(connections).updateDevice(eq(7L), eq(Device.STATUS_ONLINE), any(Date.class));
        verify(cache).updatePosition(position);
        verify(connections).updatePosition(true, position);
        publisher.pushes(List.of(3L));
        verify(outbox).dispatch(List.of(3L));

        doThrow(new IllegalStateException("socket")).when(connections).updateGameUsers(
                eq(true), anySet(), anyString());
        doThrow(new IllegalStateException("socket")).when(connections).updateDevice(
                anyLong(), anyString(), any(Date.class));
        publisher.invalidate(new GamePublisher.Invalidation(session, GamePublisher.STATUS, null));
        publisher.accounts(Set.of(player), GamePublisher.STATUS);
        publisher.positions(List.of(7L), List.of());
    }

}
