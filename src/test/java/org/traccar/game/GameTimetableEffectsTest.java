package org.traccar.game;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The joker and break effect rules ({@link GameTimetableEffects}; timetable-instructions-effects-plan.md, 8.1), the
 * break windows ({@link GameBreakRunner#window}, 8.2) and the agreement of the operator read with the engine's
 * {@link GamePingEngine#decide} for every instruction.
 */
public class GameTimetableEffectsTest {

    private static final String H = TimetableInstruction.HUNTERS;
    private static final String P = TimetableInstruction.PLAYERS;
    private static final String S = TimetableInstruction.SUPPORTERS;
    private static final GamePingEngine.JokerFake JOKER = new GamePingEngine.JokerFake(41, 47.5, 8.25);
    private static final Instant NOW = Instant.parse("2026-11-20T14:00:00Z");

    /** Every valid instruction: real cells, and coordinate cells with each target. */
    private static final List<String> INSTRUCTIONS = List.of("p", "b", "2H", "2P", "2S", "2P2H", "2S2H", "2P2S",
            "2P2S2H", "47.1,8.5", "47.1,8.5|p", "47.1,8.5|2H", "47.1,8.5|2P", "47.1,8.5|2S", "47.1,8.5|2P2H",
            "47.1,8.5|2S2H", "47.1,8.5|2P2S", "47.1,8.5|2P2S2H");

    private static GameTimetableEffects.Result apply(String content, boolean onBreak, boolean joker) {
        return GameTimetableEffects.apply(TimetableInstruction.parse(content), onBreak, joker ? JOKER : null);
    }

    /** One effect as "real / fake@coordinate / withheld", codes as the grammar spells them. */
    private static String text(GameTimetableEffects.Result result) {
        String fake = result.fake().isEmpty() ? "-"
                : TimetableInstruction.code(result.fake()) + "@" + result.latitude() + "," + result.longitude();
        return TimetableInstruction.code(result.real()) + " / " + fake + " / "
                + TimetableInstruction.code(result.withheld());
    }

    @Test
    void theTableOfEffects() {
        // Instruction, with a fake joker, during a break (plan 8.1); "-" is nobody.
        String[][] table = {
            {"p", "2P2S / 2H@47.5,8.25 / -", "2P2S / - / 2H"},
            {"2P2S2H", "2P2S / 2H@47.5,8.25 / -", "2P2S / - / 2H"},
            {"2H", "- / 2H@47.5,8.25 / -", "- / - / 2H"},
            {"2P2H", "2P / 2H@47.5,8.25 / -", "2P / - / 2H"},
            {"2S2H", "2S / 2H@47.5,8.25 / -", "2S / - / 2H"},
            {"2P", "2P / - / -", "2P / - / -"},
            {"2S", "2S / - / -", "2S / - / -"},
            {"2P2S", "2P2S / - / -", "2P2S / - / -"},
            {"b", "2P2S / - / -", "2P2S / - / -"},
            {"47.1,8.5", "2P2S / 2H@47.1,8.5 / -", "2P2S / - / 2H"},
            {"47.1,8.5|p", "- / 2P2S2H@47.1,8.5 / -", "- / 2P2S@47.1,8.5 / 2H"},
            {"47.1,8.5|2P2S2H", "- / 2P2S2H@47.1,8.5 / -", "- / 2P2S@47.1,8.5 / 2H"},
            {"47.1,8.5|2P2S", "2H / 2P2S@47.1,8.5 / -", "- / 2P2S@47.1,8.5 / 2H"},
            {"47.1,8.5|2P", "2S2H / 2P@47.1,8.5 / -", "2S / 2P@47.1,8.5 / 2H"},
            {"47.1,8.5|2P2H", "2S / 2P2H@47.1,8.5 / -", "2S / 2P@47.1,8.5 / 2H"},
            {"47.1,8.5|2S", "2P2H / 2S@47.1,8.5 / -", "2P / 2S@47.1,8.5 / 2H"},
            {"47.1,8.5|2S2H", "2P / 2S2H@47.1,8.5 / -", "2P / 2S@47.1,8.5 / 2H"},
        };
        for (String[] row : table) {
            assertEquals(row[1], text(apply(row[0], false, true)), row[0] + " with a fake joker");
            assertEquals(row[2], text(apply(row[0], true, false)), row[0] + " during a break");
            // When both apply, the break wins.
            assertEquals(row[2], text(apply(row[0], true, true)), row[0] + " with both");
        }
        // Without either, the cell is unchanged.
        assertEquals("2P2S2H / - / -", text(apply("p", false, false)));
        assertEquals("2P2S / 2H@47.1,8.5 / -", text(apply("47.1,8.5", false, false)));
    }

    @Test
    void effectsAreListedOnlyWhenAJokerOrABreakApplies() {
        var joker = new GameTimetableEffects.FakeJoker(41, 47.5, 8.25);
        var pause = new GameTimetableEffects.Break(7L, GameState.PLANNED, NOW, NOW.plusSeconds(1200), false);
        assertNull(GameTimetableEffects.effect("p", null, null));
        assertNull(GameTimetableEffects.effect("x", joker, pause));
        var both = GameTimetableEffects.effect("p", joker, pause);
        assertNotNull(both);
        // The joker is listed, so the operator sees it will be released when the break starts.
        assertEquals(joker, both.fakeJoker());
        assertEquals(pause, both.pause());
        assertEquals(List.of(P, S), both.real());
        assertEquals(List.of(), both.fake());
        assertEquals(List.of(H), both.withheld());
        assertNull(both.latitude());
        var fake = GameTimetableEffects.effect("2P2H", joker, null);
        assertEquals(List.of(P), fake.real());
        assertEquals(List.of(H), fake.fake());
        assertEquals(47.5, fake.latitude());
        assertEquals(8.25, fake.longitude());
        // A joker never applies to a coordinate cell.
        var coordinate = GameTimetableEffects.effect("47.1,8.5|2P", joker, null);
        assertEquals(List.of(H, S), coordinate.real());
        assertEquals(List.of(P), coordinate.fake());
        assertEquals(47.1, coordinate.latitude());
    }

    // Break windows -----------------------------------------------------------------------------------------

    private static GameState.Break pause(String state, Instant plannedStart, Instant startedAt, Instant deadline) {
        return new GameState.Break(7, 3, state, plannedStart, startedAt, deadline, null, GameState.BY_OPERATOR, null,
                11L);
    }

    @Test
    void breakWindowsFollowTheBreakRunnersRules() {
        // Active: from its start to its deadline; none once the deadline passed.
        assertEquals(new GameBreakRunner.Window(NOW.minusSeconds(60), NOW.plusSeconds(540), false),
                GameBreakRunner.window(pause(GameState.ACTIVE, null, NOW.minusSeconds(60), NOW.plusSeconds(540)),
                        10.0, NOW, false));
        assertNull(GameBreakRunner.window(pause(GameState.ACTIVE, null, NOW.minusSeconds(900),
                NOW.minusSeconds(300)), 10.0, NOW, false));
        // Planned: from its planned start for the break length.
        var planned = pause(GameState.PLANNED, NOW.plusSeconds(600), null, null);
        var window = GameBreakRunner.window(planned, 20.0, NOW, false);
        assertEquals(new GameBreakRunner.Window(NOW.plusSeconds(600), NOW.plusSeconds(1800), false), window);
        assertTrue(window.contains(NOW.plusSeconds(600)));
        assertTrue(window.contains(NOW.plusSeconds(1799)));
        assertFalse(window.contains(NOW.plusSeconds(1800)));
        assertFalse(window.contains(NOW.plusSeconds(599)));
        // A break with no length can't start: no window.
        assertNull(GameBreakRunner.window(planned, null, NOW, false));
        assertNull(GameBreakRunner.window(pause(GameState.WAITING, NOW.minusSeconds(60), null, null), null, NOW,
                true));
        // Waiting (start passed on time): starts as soon as it can, for its full length, estimated.
        assertEquals(new GameBreakRunner.Window(NOW, NOW.plusSeconds(600), true), GameBreakRunner.window(
                pause(GameState.WAITING, NOW.minusSeconds(300), null, null), 10.0, NOW, true));
        // Start passed while the game was stopped: from now to its planned end; missed once that passed.
        assertEquals(new GameBreakRunner.Window(NOW, NOW.plusSeconds(300), false), GameBreakRunner.window(
                pause(GameState.PLANNED, NOW.minusSeconds(300), null, null), 10.0, NOW, false));
        assertNull(GameBreakRunner.window(pause(GameState.PLANNED, NOW.minusSeconds(600), null, null), 10.0, NOW,
                false));
        // Finished breaks protect nobody.
        assertNull(GameBreakRunner.window(pause(GameState.ENDED, null, NOW.minusSeconds(60), NOW.plusSeconds(60)),
                10.0, NOW, false));
    }

    // The same rules as the engine --------------------------------------------------------------------------

    private static GamePingEngine.State reachable(boolean onBreak, boolean joker) {
        return new GamePingEngine.State(false, true, true, false, onBreak, true, true,
                Set.copyOf(TimetableInstruction.AUDIENCES), true, false, joker);
    }

    @Test
    void theReadAndTheEngineAgreeForEveryInstruction() {
        List<String> checked = new ArrayList<>();
        for (String content : INSTRUCTIONS) {
            TimetableInstruction instruction = TimetableInstruction.parse(content);
            assertTrue(instruction.valid(), content);
            for (boolean onBreak : List.of(false, true)) {
                for (boolean joker : List.of(false, true)) {
                    var effect = apply(content, onBreak, joker);
                    // The runner hands the engine the armed joker only on cells that can take a fake.
                    var decision = GamePingEngine.decide(instruction.audiences(), instruction.fakeAudiences(),
                            reachable(onBreak, joker && instruction.fakeEligible()));
                    // The engine keeps the hunters real and gives their mirror the joker's coordinate.
                    List<String> real = new ArrayList<>(decision.real());
                    List<String> fake = new ArrayList<>(decision.fake());
                    if (decision.jokerFakeUsed()) {
                        real.remove(H);
                        fake.add(0, H);
                    }
                    String label = content + (onBreak ? " on a break" : "") + (joker ? " with a joker" : "");
                    assertEquals(effect.real(), real, label);
                    assertEquals(effect.fake(), fake, label);
                    assertEquals(effect.withheld(), decision.withheld(), label);
                    boolean nothing = effect.real().isEmpty() && effect.fake().isEmpty();
                    assertEquals(nothing ? GamePingEngine.SKIPPED : !effect.withheld().isEmpty()
                            ? GamePingEngine.SKIPPED : GamePingEngine.SENT, decision.status(), label);
                    if (!effect.withheld().isEmpty()) {
                        assertEquals(GamePingEngine.BREAK, decision.reason(), label);
                    }
                    checked.add(label);
                }
            }
        }
        assertEquals(INSTRUCTIONS.size() * 4, checked.size());
    }

}
