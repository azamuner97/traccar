package org.traccar.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server timetable (plan 1.1–1.4): changesets with every rule and error code, the 15 s freeze horizon, moves
 * that keep IDs, participants (add, remove, order, retire with history kept), canonical spelling, revisions and
 * idempotency, fake releases (move, ineligible content, cancellation, retirement, break, catch) and the 15 s fake
 * cutoff, the execution toggle and the timetable settings. Runs on H2 ({@link GameTimetableH2Test}) and MySQL
 * ({@link GameTimetableMySqlTest}).
 */
public abstract class GameTimetableTest extends GameTestBase {

    private static final long EPOCH = 31;

    private GameTimetableService timetables;
    private GameRequestService requests;
    private GameJokerService jokers;
    private GameEffectsService effects;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() {
        timetables = new GameTimetableService(transactions, mapper, publisher);
        requests = new GameRequestService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        effects = effectsService();
    }

    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    // Helpers -----------------------------------------------------------------------------------------------

    private static GameTimetableService.Change add(String key, Instant at, Map<String, String> cells) {
        return new GameTimetableService.Change(GameTimetableService.ADD_SLOT, key, null,
                at == null ? null : at.toString(), cells, null, null, null, null);
    }

    private static GameTimetableService.Change move(long slot, Instant at) {
        return new GameTimetableService.Change(GameTimetableService.MOVE_SLOT, null, slot, at.toString(), null, null,
                null, null, null);
    }

    private static GameTimetableService.Change remove(long slot) {
        return new GameTimetableService.Change(GameTimetableService.REMOVE_SLOT, null, slot, null, null, null, null,
                null, null);
    }

    private static GameTimetableService.Change set(Long slot, String key, GameMember member, String content) {
        return new GameTimetableService.Change(GameTimetableService.SET_CELL, key, slot, null, null, member.id(),
                content, null, null);
    }

    private static GameTimetableService.Change clear(long slot, GameMember member) {
        return new GameTimetableService.Change(GameTimetableService.CLEAR_CELL, null, slot, null, null, member.id(),
                null, null, null);
    }

    private static GameTimetableService.Change column(GameMember member, Integer position) {
        return new GameTimetableService.Change(GameTimetableService.ADD_PARTICIPANT, null, null, null, null,
                member.id(), null, position, null);
    }

    private static GameTimetableService.Change dropColumn(GameMember member) {
        return new GameTimetableService.Change(GameTimetableService.REMOVE_PARTICIPANT, null, null, null, null,
                member.id(), null, null, null);
    }

    private static GameTimetableService.Change order(GameMember... columns) {
        return new GameTimetableService.Change(GameTimetableService.ORDER_PARTICIPANTS, null, null, null, null, null,
                null, null, java.util.Arrays.stream(columns).map(GameMember::id).toList());
    }

    private static Map<String, String> cells(Object... pairs) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(Long.toString(((GameMember) pairs[i]).id()), (String) pairs[i + 1]);
        }
        return result;
    }

    private GameTimetableService.ChangeResult apply(long session, GameTimetableService.Change... changes)
            throws Exception {
        return timetables.changes(session, new GameTimetableService.ChangeSet("change-" + UUID.randomUUID(),
                timetables.get(session).revision(), List.of(changes)));
    }

    /** The error codes of a rejected changeset; nothing of it is applied. */
    private List<String> rejected(long session, GameTimetableService.Change... changes) throws Exception {
        long revision = timetables.get(session).revision();
        int slots = timetables.get(session).slots().size();
        var error = assertThrows(GameTimetableService.Rejected.class, () -> apply(session, changes));
        assertEquals(revision, timetables.get(session).revision());
        assertEquals(slots, timetables.get(session).slots().size());
        return error.errors().stream().map(GameTimetableService.ChangeError::code).toList();
    }

    private GameTimetableService.Slot slot(long session, long id) throws Exception {
        return timetables.get(session).slots().stream().filter(item -> item.id() == id).findFirst().orElse(null);
    }

    private String status(long entry) throws Exception {
        return text("SELECT status FROM tc_game_timetable_entries WHERE id = ?", entry);
    }

    private record Game(long session, GameMember anna, GameMember ben, GameMember carla, GameMember hunter) {
    }

    private Game game() throws Exception {
        long session = session("{\"break_duration_minutes\": 10}");
        GameMember anna = bound(session, GameMember.PLAYER);
        GameMember ben = bound(session, GameMember.PLAYER);
        GameMember carla = bound(session, GameMember.SUPPORTER);
        GameMember hunter = bound(session, GameMember.HUNTER);
        apply(session, column(anna, null), column(ben, null));
        return new Game(session, anna, ben, carla, hunter);
    }

    // Changes -----------------------------------------------------------------------------------------------

    @Test
    void theCellLimitCountsOnlySlotsStillToRun() throws Exception {
        // Review SE-8: the history of done slots does not use up the limit of 20000 cells.
        Game game = game();
        Instant now = clock.instant();
        var result = apply(game.session(),
                add("done", now.plusSeconds(600), cells(game.anna(), "p", game.ben(), "p")),
                add("next", now.plusSeconds(1200), cells(game.anna(), "p", game.ben(), "p")));
        long done = result.slots().get("done");
        java.util.function.Supplier<Integer> active = () -> {
            try {
                return transactions.read(transaction -> GameTimetableRepository.activeCount(transaction,
                        game.session()));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
        assertEquals(4, active.get());
        transactions.write(transaction -> {
            transaction.lockSession(game.session());
            GameTimetableRepository.finish(transaction, game.session(), done);
            return null;
        });
        assertEquals(2, active.get());
    }

    @Test
    void slotsAndCellsAreEditedAllOrNothingAndKeepTheirIds() throws Exception {
        Game game = game();
        Instant now = clock.instant();
        long before = revision(game.session());
        var result = apply(game.session(),
                add("first", now.plusSeconds(600), cells(game.anna(), " P ", game.ben(), "2p2h")),
                add("second", now.plusSeconds(1200), cells(game.anna(), " ")),
                set(null, "second", game.ben(), " 47.1 , 8.5 | 2s "));
        assertEquals(2, result.revision());
        assertFalse(result.replayed());
        assertEquals(before + 1, revision(game.session()));
        long first = result.slots().get("first");
        long second = result.slots().get("second");
        var read = timetables.get(game.session());
        assertEquals(List.of(first, second), read.slots().stream().map(GameTimetableService.Slot::id).toList());
        // Stored trimmed and canonical; a blank cell is no entry, so addSlot leaves it out.
        assertEquals(List.of("p", "2P2H"), read.slots().get(0).entries().stream()
                .map(GameTimetableService.Entry::content).toList());
        assertEquals(List.of("47.1,8.5|2S"), read.slots().get(1).entries().stream()
                .map(GameTimetableService.Entry::content).toList());
        assertEquals(0L, number("SELECT COUNT(*) FROM tc_game_timetable_entries WHERE slotid = ? AND memberid = ?",
                second, game.anna().id()));
        assertEquals(now.plusSeconds(15), read.frozenUntil());
        assertEquals(now, read.serverTime());
        assertEquals(now.plusSeconds(600), read.execution().nextSlotAt());
        assertFalse(read.execution().enabled());
        assertFalse(publisher.topic(GamePublisher.TIMETABLE).isEmpty());

        // Moving changes only the time: the slot and its entries keep their IDs.
        long annaFirst = entry(timetables, game.session(), first, game.anna().id()).id();
        apply(game.session(), move(first, now.plusSeconds(1800)));
        var moved = slot(game.session(), first);
        assertEquals(now.plusSeconds(1800), moved.scheduledAt());
        assertEquals(annaFirst, moved.entries().getFirst().id());
        // A cleared cell is absent; setting it again brings the same entry back.
        apply(game.session(), clear(first, game.anna()));
        assertEquals(List.of(game.ben().id()), slot(game.session(), first).entries().stream()
                .map(GameTimetableService.Entry::memberId).toList());
        assertEquals(GameTimetableRepository.CANCELLED, status(annaFirst));
        apply(game.session(), set(first, null, game.anna(), "2H"));
        assertEquals(annaFirst, entry(timetables, game.session(), first, game.anna().id()).id());
        assertEquals(GameTimetableRepository.SCHEDULED, status(annaFirst));
        // A blank setCell is no entry: it clears the cell like clearCell, and inserts nothing on an absent one.
        apply(game.session(), set(first, null, game.anna(), "  "), set(second, null, game.anna(), ""));
        assertEquals(GameTimetableRepository.CANCELLED, status(annaFirst));
        assertEquals(List.of(game.ben().id()), slot(game.session(), first).entries().stream()
                .map(GameTimetableService.Entry::memberId).toList());
        assertEquals(1, slot(game.session(), second).entries().size());
        apply(game.session(), set(first, null, game.anna(), "2H"));
        assertEquals(annaFirst, entry(timetables, game.session(), first, game.anna().id()).id());
        // A removed slot keeps its rows (fakes reference entries) but disappears, and frees its time.
        apply(game.session(), remove(second));
        assertNull(slot(game.session(), second));
        assertEquals(GameTimetableRepository.REMOVED, text("SELECT state FROM tc_game_timetable_slots WHERE id = ?",
                second));
        assertEquals(List.of(GameTimetableRepository.CANCELLED),
                strings("SELECT status FROM tc_game_timetable_entries WHERE slotid = ?", second));
        long again = apply(game.session(), add("again", now.plusSeconds(1200), cells(game.anna(), "p")))
                .slots().get("again");
        assertNotEquals(second, again);
        assertEquals(9, timetables.get(game.session()).revision());
    }

    @Test
    void everyRuleRejectsTheWholeChangesetWithItsCode() throws Exception {
        Game game = game();
        GameMember outsider = bound(game.session(), GameMember.PLAYER);
        Instant now = clock.instant();
        var result = apply(game.session(), add("a", now.plusSeconds(600), cells(game.anna(), "p")),
                add("b", now.plusSeconds(700), cells(game.anna(), "p")),
                add("c", now.plusSeconds(800), cells(game.anna(), "p")),
                add("d", now.plusSeconds(900), cells(game.anna(), "p")));
        long a = result.slots().get("a");
        long b = result.slots().get("b");
        long c = result.slots().get("c");
        long d = result.slots().get("d");
        execute("UPDATE tc_game_timetable_slots SET state = 'claimed', claimedat = ? WHERE id = ?", now, b);
        execute("UPDATE tc_game_timetable_slots SET state = 'done', finishedat = ? WHERE id = ?", now, c);
        apply(game.session(), remove(d));

        // The freeze horizon: due within 15 s can't change, also as a move's target.
        assertEquals(List.of("frozen"), rejected(game.session(), add("x", now.plusSeconds(15), Map.of())));
        apply(game.session(), add("y", now.plusSeconds(16), Map.of()));
        assertEquals(List.of("frozen"), rejected(game.session(), move(a, now.plusSeconds(10))));
        clock.set(now.plusSeconds(586));
        assertEquals(List.of("frozen", "frozen"), rejected(game.session(), set(a, null, game.ben(), "p"),
                move(a, now.plusSeconds(5000))));
        clock.set(now);
        // Every failing change is listed, in order, with its code; the valid one is not applied either.
        var error = assertThrows(GameTimetableService.Rejected.class, () -> apply(game.session(),
                set(b, null, game.anna(), "2H"),
                set(c, null, game.anna(), "2H"),
                set(d, null, game.anna(), "2H"),
                add("dup", now.plusSeconds(600), Map.of()),
                set(a, null, game.anna(), "2Q"),
                set(a, null, game.hunter(), "p"),
                set(a, null, outsider, "p"),
                set(9_999_999L, null, game.anna(), "p"),
                set(null, "missing", game.anna(), "p"),
                add("valid", now.plusSeconds(2000), cells(game.anna(), "b")),
                add("valid", now.plusSeconds(2100), Map.of()),
                add("time", null, Map.of()),
                order(game.anna()),
                column(game.anna(), null),
                new GameTimetableService.Change("swap", null, a, null, null, null, null, null, null),
                set(a, null, game.anna(), null),
                add("member", now.plusSeconds(2200), Map.of("not-a-number", "p"))));
        assertEquals(List.of("claimed", "done", "removed", "duplicateTime", "invalidInstruction", "inactiveMember",
                "notParticipant", "unknownSlot", "unknownKey", "invalidKey", "invalidTime", "invalidOrder",
                "duplicateParticipant", "invalidChange", "invalidInstruction", "invalidChange"),
                error.errors().stream().map(GameTimetableService.ChangeError::code).toList());
        var first = error.errors().getFirst();
        assertEquals(0, first.index());
        assertEquals(b, first.slotId());
        assertEquals(game.anna().id(), error.errors().get(5 - 1).memberId());
        assertTrue(error.errors().get(4).message().contains("2Q"));
        assertEquals(4, timetables.get(game.session()).slots().size());
        // x is no instruction, neither as a cell nor as a coordinate target.
        var removed = assertThrows(GameTimetableService.Rejected.class, () -> apply(game.session(),
                set(a, null, game.anna(), "x"), add("old", now.plusSeconds(2300), cells(game.anna(), "X")),
                set(a, null, game.anna(), "47.1,8.5|x")));
        assertEquals(List.of("invalidInstruction", "invalidInstruction", "invalidInstruction"),
                removed.errors().stream().map(GameTimetableService.ChangeError::code).toList());
        assertTrue(removed.errors().stream().allMatch(item -> item.message().contains("is not supported")));
        // Shape errors answer 400 before any transaction.
        for (var changeSet : List.of(new GameTimetableService.ChangeSet("short", 0L, List.of()),
                new GameTimetableService.ChangeSet("change-" + UUID.randomUUID(), null, List.of()),
                new GameTimetableService.ChangeSet("change-" + UUID.randomUUID(), 0L, null))) {
            assertStatus(400, () -> timetables.changes(game.session(), changeSet));
        }
        List<GameTimetableService.Change> many = new ArrayList<>();
        for (int i = 0; i <= GameTimetableService.MAX_CHANGES; i++) {
            many.add(order());
        }
        assertStatus(400, () -> timetables.changes(game.session(), new GameTimetableService.ChangeSet(
                "change-" + UUID.randomUUID(), 0L, many)));
    }

    @Test
    void revisionsConflictAndARetriedChangeIdAnswersItsResult() throws Exception {
        Game game = game();
        long current = timetables.get(game.session()).revision();
        var changeSet = new GameTimetableService.ChangeSet("change-retry-1", current, List.of(add("k",
                clock.instant().plusSeconds(600), cells(game.anna(), "p"))));
        var first = timetables.changes(game.session(), changeSet);
        var replay = timetables.changes(game.session(), changeSet);
        assertTrue(replay.replayed());
        assertEquals(first.slots(), replay.slots());
        assertEquals(first.revision(), replay.revision());
        assertEquals(1, timetables.get(game.session()).slots().size());
        // The same changeId with other content, and a stale base revision, conflict.
        assertStatus(409, () -> timetables.changes(game.session(), new GameTimetableService.ChangeSet(
                "change-retry-1", current, List.of())));
        assertStatus(409, () -> timetables.changes(game.session(), new GameTimetableService.ChangeSet(
                "change-retry-2", current, List.of())));
        // An empty changeset changes nothing.
        var empty = timetables.changes(game.session(), new GameTimetableService.ChangeSet("change-retry-3",
                first.revision(), List.of()));
        assertEquals(first.revision(), empty.revision());
        // Another session's timetable is its own.
        long other = session();
        assertEquals(0, timetables.get(other).revision());
        assertStatus(404, () -> timetables.get(other + 1000));
    }

    // Participants ------------------------------------------------------------------------------------------

    @Test
    void participantsAreAddedOrderedRemovedAndKeptWithHistory() throws Exception {
        Game game = game();
        Instant now = clock.instant();
        apply(game.session(), column(game.carla(), 0));
        assertEquals(List.of(game.carla().id(), game.anna().id(), game.ben().id()), timetables.get(game.session())
                .participants().stream().map(GameTimetableService.Participant::memberId).toList());
        apply(game.session(), order(game.ben(), game.anna(), game.carla()));
        var participants = timetables.get(game.session()).participants();
        assertEquals(List.of(game.ben().id(), game.anna().id(), game.carla().id()), participants.stream()
                .map(GameTimetableService.Participant::memberId).toList());
        assertEquals(List.of(0, 1, 2), participants.stream().map(GameTimetableService.Participant::position)
                .toList());
        assertEquals(game.ben().displayName(), participants.getFirst().displayName());
        assertEquals(GameMember.SUPPORTER, participants.get(2).role());
        assertEquals(game.ben().trackerId(), participants.getFirst().trackerId());
        assertEquals(List.of("inactiveMember"), rejected(game.session(), column(game.hunter(), null)));

        // Without history, removing a column removes it with its editable cells.
        var result = apply(game.session(), add("past", now.plusSeconds(60), cells(game.anna(), "p", game.ben(), "p")),
                add("future", now.plusSeconds(600), cells(game.anna(), "p", game.ben(), "p", game.carla(), "p")));
        long future = result.slots().get("future");
        apply(game.session(), dropColumn(game.carla()));
        assertEquals(2, timetables.get(game.session()).participants().size());
        assertEquals(2, slot(game.session(), future).entries().size());
        // A member with history (a cell that ran, or is due) keeps the column; editable cells are cancelled.
        clock.set(now.plusSeconds(50));
        apply(game.session(), dropColumn(game.ben()));
        assertTrue(timetables.get(game.session()).participants().stream()
                .anyMatch(item -> item.memberId() == game.ben().id()));
        assertEquals(List.of(game.anna().id()), slot(game.session(), future).entries().stream()
                .map(GameTimetableService.Entry::memberId).toList());

        // Retiring cancels the member's editable cells and keeps the column; the plan revision advances.
        long revision = timetables.get(game.session()).revision();
        publisher.clear();
        members.retire(game.session(), game.anna().id());
        var read = timetables.get(game.session());
        assertEquals(revision + 1, read.revision());
        assertTrue(slot(game.session(), future).entries().isEmpty());
        assertEquals(2, slot(game.session(), result.slots().get("past")).entries().size());
        assertEquals(GameMember.RETIRED, read.participants().stream()
                .filter(item -> item.memberId() == game.anna().id()).findFirst().orElseThrow().state());
        assertFalse(publisher.topic(GamePublisher.TIMETABLE).isEmpty());
        assertEquals(List.of("inactiveMember"), rejected(game.session(), set(future, null, game.anna(), "p")));
        // Another role is another member (retire, plan, bind), planned anew.
        members.retire(game.session(), game.carla().id());
        GameMember renewed = plan(game.session(), GameMember.PLAYER, "Carla again");
        members.bindAccount(game.session(), renewed.id(), game.carla().userId());
        members.bindTracker(game.session(), renewed.id(), game.carla().trackerId());
        apply(game.session(), column(renewed, null), set(future, null, renewed, "2H"));
        assertEquals("2H", entry(timetables, game.session(), future, renewed.id()).content());
    }

    // Fakes -------------------------------------------------------------------------------------------------

    private void lease(long session) throws Exception {
        lease(session, EPOCH);
    }

    private GameEffectsService.Outcome deliver(long session, GameOperation... operations) throws Exception {
        return effects.apply(session, EPOCH, new GameEffectsService.Delivery("delivery-" + UUID.randomUUID(),
                List.of(operations), null, null, null, null, null, null));
    }

    @Test
    void armedFakesAreReleasedWhenTheirPingMovesChangesOrDisappears() throws Exception {
        Game game = game();
        Instant now = clock.instant();
        var result = apply(game.session(),
                add("move", now.plusSeconds(600), cells(game.anna(), "p")),
                add("content", now.plusSeconds(700), cells(game.anna(), "p")),
                add("eligible", now.plusSeconds(800), cells(game.anna(), "p")),
                add("clear", now.plusSeconds(900), cells(game.anna(), "p")),
                add("remove", now.plusSeconds(1000), cells(game.anna(), "p")),
                add("retire", now.plusSeconds(1100), cells(game.anna(), "p")));
        Map<String, Long> fakes = new java.util.HashMap<>();
        for (String key : List.of("move", "content", "eligible", "clear", "remove")) {
            fakes.put(key, armFake(game.session(), game.anna(), entry(timetables, game.session(),
                    result.slots().get(key), game.anna().id()).id(), 46.5, 7.5));
        }
        publisher.clear();
        apply(game.session(), move(result.slots().get("move"), now.plusSeconds(650)));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(fakes.get("move")));
        assertEquals(Set.of(game.anna().id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        // Still reaching the hunters keeps the fake; b, codes without hunters, coordinates and blank release it.
        apply(game.session(), set(result.slots().get("eligible"), null, game.anna(), "2s2h"));
        assertEquals(GameJokerRepository.RESERVED, reservationStatus(fakes.get("eligible")));
        apply(game.session(), set(result.slots().get("content"), null, game.anna(), "47.1,8.5"));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(fakes.get("content")));
        apply(game.session(), clear(result.slots().get("clear"), game.anna()));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(fakes.get("clear")));
        apply(game.session(), remove(result.slots().get("remove")));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(fakes.get("remove")));
        // The read shows the armed fake on its cell.
        assertEquals(fakes.get("eligible"), entry(timetables, game.session(), result.slots().get("eligible"),
                game.anna().id()).fakeReservationId());
        assertNull(entry(timetables, game.session(), result.slots().get("content"), game.anna().id())
                .fakeReservationId());

        // Retiring releases the member's fakes on the cells it cancels.
        long retired = armFake(game.session(), game.anna(), entry(timetables, game.session(),
                result.slots().get("retire"), game.anna().id()).id(), 46.5, 7.5);
        members.retire(game.session(), game.anna().id());
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(retired));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(fakes.get("eligible")));
        assertEquals(List.of(), reconcile(game.session()));
    }

    @Test
    void aBreakReleasesFakesInsideItAndACatchReleasesEveryArmedFake() throws Exception {
        Game game = game();
        lease(game.session());
        Instant now = clock.instant();
        var result = apply(game.session(),
                add("inside", now.plusSeconds(300), cells(game.anna(), "p", game.ben(), "p")),
                add("after", now.plusSeconds(3600), cells(game.anna(), "p", game.ben(), "p")));
        long inside = armFake(game.session(), game.anna(), entry(timetables, game.session(),
                result.slots().get("inside"), game.anna().id()).id(), 46.5, 7.5);
        long after = armFake(game.session(), game.anna(), entry(timetables, game.session(),
                result.slots().get("after"), game.anna().id()).id(), 46.5, 7.5);
        long benFake = armFake(game.session(), game.ben(), entry(timetables, game.session(),
                result.slots().get("after"), game.ben().id()).id(), 46.5, 7.5);
        // A planned break of 10 minutes starts now: the 5-minute ping falls inside it, the 1-hour one does not.
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(), GameJokerRepository.BREAK, 1,
                null), "Operator");
        jokers.planBreak(game.session(), game.anna().id(), null, false, "Operator");
        breakTick(game.session());
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(inside));
        assertEquals(GameJokerRepository.RESERVED, reservationStatus(after));
        // A catch releases every armed plan of the caught member.
        deliver(game.session(), new GameOperation.Catch(game.ben().id(), "Hunter"));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(benFake));
        assertEquals(GameJokerRepository.RESERVED, reservationStatus(after));
        assertEquals(List.of(), reconcile(game.session()));
    }

    @Test
    void theOperatorReadShowsJokerAndBreakEffectsThatMembersNeverSee() throws Exception {
        // timetable-instructions-effects-plan.md, 8.2 and 8.3: derived on each read, never stored.
        Game game = game();
        lease(game.session());
        Instant now = clock.instant();
        var result = apply(game.session(),
                add("soon", now.plusSeconds(300), cells(game.ben(), "47.1,8.5|2P")),
                add("joker", now.plusSeconds(600), cells(game.anna(), "p", game.ben(), "2P2H")),
                add("break", now.plusSeconds(3700), cells(game.anna(), "p")),
                add("both", now.plusSeconds(4000), cells(game.anna(), "2H")),
                add("after", now.plusSeconds(4300), cells(game.anna(), "p")));
        long revision = timetables.get(game.session()).revision();
        var pings = timetables.pings(game.anna()).stream().map(GameTimetableService.Ping::entryId).toList();
        var hunterSlots = timetables.hunterSlots(game.session());
        java.util.function.Function<String, GameTimetableService.Entry> anna = key -> {
            try {
                return entry(timetables, game.session(), result.slots().get(key), game.anna().id());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
        assertTrue(timetables.get(game.session()).slots().stream().flatMap(item -> item.entries().stream())
                .allMatch(item -> item.effect() == null));

        long joker = armFake(game.session(), game.anna(), anna.apply("joker").id(), 46.5, 7.5);
        long doomed = armFake(game.session(), game.anna(), anna.apply("both").id(), 46.5, 7.5);
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(), GameJokerRepository.BREAK, 1,
                null), "Operator");
        jokers.change(game.session(), new GameJokerService.Change(game.ben().id(), GameJokerRepository.BREAK, 1,
                null), "Operator");
        jokers.planBreak(game.session(), game.anna().id(), now.plusSeconds(3600).toString(), false, "Operator");
        jokers.planBreak(game.session(), game.ben().id(), now.plusSeconds(1800).toString(), false, "Operator");
        long annaBreak = number("SELECT id FROM tc_game_breaks WHERE gamesessionid = ? AND memberid = ?",
                game.session(), game.anna().id());
        long benBreak = number("SELECT id FROM tc_game_breaks WHERE gamesessionid = ? AND memberid = ?",
                game.session(), game.ben().id());
        // Ben's break waits: its start passed while the game ran, so it lasts its full length from now (estimated).
        execute("UPDATE tc_game_breaks SET state = 'waiting', plannedstart = ? WHERE id = ?", now.minusSeconds(10),
                benBreak);
        startGame(game.session());

        // A fake joker moves the hunters to its coordinate.
        var fake = anna.apply("joker").effect();
        assertEquals(joker, anna.apply("joker").fakeReservationId());
        assertEquals(new GameTimetableEffects.FakeJoker(joker, 46.5, 7.5), fake.fakeJoker());
        assertNull(fake.pause());
        assertEquals(List.of("players", "supporters"), fake.real());
        assertEquals(List.of("hunters"), fake.fake());
        assertEquals(List.of(), fake.withheld());
        assertEquals(46.5, fake.latitude());
        assertEquals(7.5, fake.longitude());
        assertNull(entry(timetables, game.session(), result.slots().get("joker"), game.ben().id()).effect());
        // A planned break of 10 minutes withholds the hunters inside its window.
        var planned = new GameTimetableEffects.Break(annaBreak, GameState.PLANNED, now.plusSeconds(3600),
                now.plusSeconds(4200), false);
        var inside = anna.apply("break").effect();
        assertEquals(planned, inside.pause());
        assertNull(inside.fakeJoker());
        assertEquals(List.of("players", "supporters"), inside.real());
        assertEquals(List.of("hunters"), inside.withheld());
        // A joker inside a planned break: the break wins, and the joker is listed (it is released at the start).
        var both = anna.apply("both").effect();
        assertEquals(planned, both.pause());
        assertEquals(new GameTimetableEffects.FakeJoker(doomed, 46.5, 7.5), both.fakeJoker());
        assertEquals(List.of(), both.real());
        assertEquals(List.of(), both.fake());
        assertEquals(List.of("hunters"), both.withheld());
        assertNull(anna.apply("after").effect());
        // A waiting break: from now for its full length, estimated; the coordinate still reaches the players.
        var waiting = entry(timetables, game.session(), result.slots().get("soon"), game.ben().id()).effect();
        assertEquals(new GameTimetableEffects.Break(benBreak, GameState.WAITING, now, now.plusSeconds(600), true),
                waiting.pause());
        assertEquals(List.of("supporters"), waiting.real());
        assertEquals(List.of("players"), waiting.fake());
        assertEquals(47.1, waiting.latitude());
        assertEquals(List.of("hunters"), waiting.withheld());

        // Jokers and breaks are no edits; members still see the pings of the operator's instructions.
        assertEquals(revision, timetables.get(game.session()).revision());
        assertEquals(pings, timetables.pings(game.anna()).stream().map(GameTimetableService.Ping::entryId).toList());
        assertEquals(hunterSlots, timetables.hunterSlots(game.session()));
        // Content and stored rows are unchanged.
        assertEquals("2H", anna.apply("both").content());
        assertEquals(List.of("47.1,8.5|2P", "p", "2P2H", "p", "2H", "p"),
                strings("SELECT e.content FROM tc_game_timetable_entries e JOIN tc_game_timetable_slots s"
                        + " ON s.id = e.slotid WHERE e.gamesessionid = ? ORDER BY s.scheduledat, e.memberid",
                        game.session()));
    }

    @Test
    void theFakeCutoffIsFifteenSeconds() throws Exception {
        Game game = game();
        lease(game.session());
        Instant now = clock.instant();
        var result = apply(game.session(), add("near", now.plusSeconds(20), cells(game.anna(), "p")),
                add("far", now.plusSeconds(40), cells(game.anna(), "p")));
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(), GameJokerRepository.FAKE_PING, 2,
                null), "Operator");
        assertEquals(15, GameTimetableService.FAKE_CUTOFF_SECONDS);
        String near = "{\"entryId\": " + entry(timetables, game.session(), result.slots().get("near"),
                game.anna().id()).id() + ", \"latitude\": 47.1, \"longitude\": 8.5}";
        // 16 s ahead can still carry a fake; 15 s ahead is too close.
        clock.set(now.plusSeconds(4));
        assertEquals(2, jokers.own(game.anna()).timetableEntries().size());
        assertNotNull(requests.submit(game.anna(), new GameRequestService.Submission(GameRequest.FAKE_PING, null,
                null, json(near))));
        String far = "{\"entryId\": " + entry(timetables, game.session(), result.slots().get("far"),
                game.anna().id()).id() + ", \"latitude\": 47.1, \"longitude\": 8.5}";
        clock.set(now.plusSeconds(25));
        assertEquals(409, status(() -> requests.submit(game.anna(), new GameRequestService.Submission(
                GameRequest.FAKE_PING, null, null, json(far)))));
        assertEquals(List.of(), jokers.own(game.anna()).timetableEntries());
    }

    // Toggle and settings -----------------------------------------------------------------------------------

    @Test
    void theToggleIsRecordedWithEventsAndTheSettingsAreValidated() throws Exception {
        Game game = game();
        long operator = user();
        publisher.clear();
        var on = timetables.setExecution(game.session(), true, operator);
        assertTrue(on.enabled());
        assertEquals(clock.instant(), on.enabledChangedAt());
        assertEquals(GameExecutionService.STOPPED, on.gameState());
        assertEquals(1, publisher.events.size());
        var event = publisher.events.getFirst();
        assertEquals(GameEvent.TIMETABLE, event.category());
        assertEquals("execution", event.data().get("type").asText());
        assertEquals("on", event.data().get("state").asText());
        assertEquals(event.id(), on.lastEventId());
        // Setting the same state again changes nothing.
        timetables.setExecution(game.session(), true, operator);
        assertEquals(1, publisher.events.size());
        long revision = timetables.get(game.session()).revision();
        assertFalse(timetables.setExecution(game.session(), false, operator).enabled());
        assertEquals("off", publisher.events.get(1).data().get("state").asText());
        // Execution never changes the plan revision.
        assertEquals(revision, timetables.get(game.session()).revision());

        // The two timetable settings: whole seconds in range, defaults 20 and 30.
        long session = session("{\"timetable_freshness_window_seconds\": 10,"
                + " \"timetable_fresh_fix_wait_seconds\": 0}");
        var settings = transactions.read(transaction -> GameSettings.read(transaction, session));
        assertEquals(10, settings.timetableFreshnessSeconds());
        assertEquals(0, settings.timetableWaitSeconds());
        var defaults = transactions.read(transaction -> GameSettings.read(transaction, game.session()));
        assertEquals(20, defaults.timetableFreshnessSeconds());
        assertEquals(30, defaults.timetableWaitSeconds());
        for (String invalid : List.of("{\"timetable_fresh_fix_wait_seconds\": 61}",
                "{\"timetable_fresh_fix_wait_seconds\": -1}", "{\"timetable_fresh_fix_wait_seconds\": 1.5}",
                "{\"timetable_freshness_window_seconds\": \"20\"}", "{\"timetable_freshness_window_seconds\": 301}")) {
            assertStatus(400, () -> session(invalid));
        }
        session("{\"timetable_fresh_fix_wait_seconds\": 60, \"timetable_freshness_window_seconds\": null}");
    }

}
