/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The server's ping engine (plan B3): turns one <em>ping intent</em> into mirror positions and an outcome. It holds
 * the rules of the Tool's {@code TimetableRowProcessor}, {@code OutgoingPositionPolicy},
 * {@code ParticipantPingSender}, {@code PositionUploadService} and {@code HunterRelayService}.
 *
 * <p>Rules, in order ({@link #decide}): past {@code notAfter} nothing is written ({@code skipped}/{@code missed});
 * a retired source or one without a tracker is {@code skipped}/{@code inactive}; a caught source (relay: a caught
 * recipient) is {@code skipped}/{@code caught}; a source on an active break has the hunters withheld on participant
 * paths ({@code skipped}/{@code break} when nothing is left); players and supporters need another active member in
 * that role, otherwise that audience is dropped ({@code failed}/{@code noRecipient} when nothing is left); every
 * remaining audience needs its mirror ({@code failed}/{@code noMirror}); the real audiences need a position
 * ({@code failed}/{@code noPosition}); a fix older than {@code minimumFixTime} is delivered and marked stale. A
 * timetable joker fake gives the hunter mirrors its coordinate and is consumed only when the hunters receive it,
 * otherwise released. Delivered is {@code sent}, or {@code skipped} when audiences were withheld, as in the Tool.
 *
 * <p>Mechanics: a freshness wait happens before the transaction, so no lock is held while waiting. It completes as
 * soon as a fresh fix arrives ({@link GamePositionWaiter}, with the {@code Position} itself) and polls the latest
 * positions every {@value #POLL_MILLIS} ms as a fallback. One transaction per intent then locks the session and the
 * member states its effects touch, writes the positions through {@link GamePositions}, applies
 * {@code onDelivered} or {@code onNotDelivered}, settles the joker fake and lets the caller record the outcome and
 * its event; positions and invalidations are published after commit.
 */
@Singleton
public class GamePingEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(GamePingEngine.class);

    public static final String TIMETABLE = "timetable";
    public static final String MANUAL = "manual";
    public static final String LIVE = "live";
    public static final String GEOFENCE_LIVE = "geofenceLive";
    public static final String SPEEDHUNT = "speedhunt";
    public static final String RELAY = "relay";
    public static final Set<String> PATHS = Set.of(TIMETABLE, MANUAL, LIVE, GEOFENCE_LIVE, SPEEDHUNT, RELAY);

    public static final String SENT = "sent";
    public static final String SKIPPED = "skipped";
    public static final String FAILED = "failed";

    public static final String INACTIVE = "inactive";
    public static final String CAUGHT = "caught";
    public static final String BREAK = "break";
    public static final String NO_RECIPIENT = "noRecipient";
    public static final String NO_MIRROR = "noMirror";
    public static final String NO_POSITION = "noPosition";
    public static final String MISSED = "missed";

    /** Poll interval of the latest positions while waiting for a fresh fix (fallback of the position hook). */
    public static final long POLL_MILLIS = 200;
    public static final int MAX_WAIT_SECONDS = 60;
    public static final int MAX_PINGS = 50;

    // Intent ------------------------------------------------------------------------------------------------

    /** A coordinate sent as a fake to its own audiences (a timetable {@code lat,lon|code}, an operator fake). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fake(Double latitude, Double longitude, List<String> audiences) {
    }

    /**
     * Send as soon as a fix at or after {@code minimumFixTime} exists; otherwise use the last known position after
     * {@code waitSeconds}. Only the server's timetable waits.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Freshness(String minimumFixTime, Integer waitSeconds) {
    }

    /** Operations, request completions and joker uses applied in the intent's transaction. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Effects(List<GameOperation> operations, List<GameRequestService.Completion> requests,
                          List<GameJokerService.Use> jokers) {

        static final Effects NONE = new Effects(List.of(), List.of(), List.of());

        List<GameOperation> operationList() {
            return operations == null ? List.of() : operations;
        }

        List<GameRequestService.Completion> requestList() {
            return requests == null ? List.of() : requests;
        }

        List<GameJokerService.Use> jokerList() {
            return jokers == null ? List.of() : jokers;
        }

        boolean empty() {
            return operationList().isEmpty() && requestList().isEmpty() && jokerList().isEmpty();
        }
    }

    /**
     * A ping intent: whose position goes to whom. {@code path} is {@code timetable}, {@code manual}, {@code live},
     * {@code geofenceLive}, {@code speedhunt} or {@code relay}. Participant paths name {@code sourceMemberId}; a
     * relay names {@code recipientMemberId}, who receives every active hunter's position on their per-hunter
     * mirrors. {@code audiences} receive the real position, {@code fake.audiences} the fake coordinate.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Intent(String path, Long sourceMemberId, Long recipientMemberId, List<String> audiences, Fake fake,
                         Freshness freshness, String notAfter, Effects onDelivered, Effects onNotDelivered) {
    }

    /** A timetable fake-ping joker armed on the entry: the hunter mirrors get this coordinate. */
    public record JokerFake(long reservationId, double latitude, double longitude) {
    }

    /** What the server adds to an intent it runs itself (the timetable runner). */
    public record Context(Instant scheduledAt, JokerFake jokerFake) {
        static final Context NONE = new Context(null, null);
    }

    /** The intent's transaction calls these: to stop before anything is written, and to record the outcome. */
    public interface Hooks {
        Hooks NONE = new Hooks() {
        };

        /** Inside the transaction before anything is written; false writes nothing and yields no outcome. */
        default boolean proceed(GameTransaction transaction) throws Exception {
            return true;
        }

        /** Inside the same transaction, after positions and effects: record the outcome (entry, event). */
        default void completed(GameTransaction transaction, Outcome outcome) throws Exception {
        }

        /** Whether the intent is still wanted while it waits for a fresh fix; checked on every poll. */
        default boolean wanted() {
            return true;
        }
    }

    // Outcome -----------------------------------------------------------------------------------------------

    /** The real position used: its coordinate, fix time, and whether it was older than {@code minimumFixTime}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PositionOutcome(double latitude, double longitude, String fixTime, boolean stale) {
    }

    /** What became of an armed timetable joker fake: {@code consumed} or {@code released}. */
    public record JokerFakeOutcome(long reservationId, String status, double latitude, double longitude) {
    }

    /**
     * The outcome of one intent: {@code status} ({@code sent}, {@code skipped}, {@code failed}) and {@code reason},
     * the audiences that received a position ({@code delivered}) and the ones a break withheld, the fake delivered
     * with its audiences, the joker fake, the position used, the delay after the scheduled time, the written mirror
     * positions, the relayed hunters, and the results of the applied operations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(String path, Long sourceMemberId, Long recipientMemberId, String status, String reason,
                          List<String> delivered, List<String> withheld, Fake fake, JokerFakeOutcome jokerFake,
                          PositionOutcome position, Double delaySeconds, String detail, List<Long> hunters,
                          List<GamePositions.Written> positions, List<GameOperations.Result> operations) {

        public boolean deliveredAny() {
            return delivered != null && !delivered.isEmpty();
        }
    }

    // Policy ------------------------------------------------------------------------------------------------

    /** The state an intent of a participant path is decided on. */
    public record State(boolean pastNotAfter, boolean sourceActive, boolean sourceTracked, boolean sourceCaught,
                        boolean sourceOnBreak, boolean otherPlayers, boolean otherSupporters, Set<String> mirrors,
                        boolean positionKnown, boolean stale, boolean jokerFake) {
    }

    /**
     * The decision: who gets the real position ({@code real}) and who the fake coordinate ({@code fake}), what a
     * break withheld, whether the joker fake reaches the hunters, and whether the position is stale.
     */
    public record Decision(String status, String reason, List<String> real, List<String> fake, List<String> withheld,
                           boolean jokerFakeUsed, boolean stale, String detail) {

        public List<String> delivered() {
            List<String> result = new ArrayList<>();
            for (String audience : TimetableInstruction.AUDIENCES) {
                if (real.contains(audience) || fake.contains(audience)) {
                    result.add(audience);
                }
            }
            return result;
        }

        static Decision none(String status, String reason) {
            return new Decision(status, reason, List.of(), List.of(), List.of(), false, false, null);
        }
    }

    private static List<String> without(List<String> audiences, Set<String> removed) {
        return audiences.stream().filter(audience -> !removed.contains(audience)).toList();
    }

    private static List<String> ordered(List<String> audiences) {
        return TimetableInstruction.AUDIENCES.stream().filter(audiences::contains).toList();
    }

    /** Decides an intent of a participant path (every path except {@code relay}) on the given state. */
    public static Decision decide(List<String> realAudiences, List<String> fakeAudiences, State state) {
        if (state.pastNotAfter()) {
            return Decision.none(SKIPPED, MISSED);
        }
        if (!state.sourceActive() || !state.sourceTracked()) {
            return Decision.none(SKIPPED, INACTIVE);
        }
        if (state.sourceCaught()) {
            return Decision.none(SKIPPED, CAUGHT);
        }
        List<String> real = ordered(realAudiences);
        List<String> fake = ordered(fakeAudiences);
        List<String> withheld = List.of();
        if (state.sourceOnBreak()) {
            // A break withholds the hunters from the participant's pings, real or fake.
            if (real.contains(TimetableInstruction.HUNTERS) || fake.contains(TimetableInstruction.HUNTERS)) {
                withheld = List.of(TimetableInstruction.HUNTERS);
            }
            real = without(real, Set.copyOf(withheld));
            fake = without(fake, Set.copyOf(withheld));
            if (real.isEmpty() && fake.isEmpty()) {
                return new Decision(SKIPPED, BREAK, List.of(), List.of(), withheld, false, false, null);
            }
        }
        Set<String> unreachable = new LinkedHashSet<>();
        if (!state.otherPlayers()) {
            unreachable.add(TimetableInstruction.PLAYERS);
        }
        if (!state.otherSupporters()) {
            unreachable.add(TimetableInstruction.SUPPORTERS);
        }
        real = without(real, unreachable);
        fake = without(fake, unreachable);
        if (real.isEmpty() && fake.isEmpty()) {
            return withheld.isEmpty() ? Decision.none(FAILED, NO_RECIPIENT)
                    : new Decision(SKIPPED, BREAK, List.of(), List.of(), withheld, false, false, null);
        }
        for (String audience : TimetableInstruction.AUDIENCES) {
            if ((real.contains(audience) || fake.contains(audience)) && !state.mirrors().contains(audience)) {
                return new Decision(FAILED, NO_MIRROR, List.of(), List.of(), withheld, false, false,
                        "No " + audience + " mirror of this participant");
            }
        }
        String detail = null;
        if (!real.isEmpty() && !state.positionKnown()) {
            if (fake.isEmpty()) {
                return new Decision(FAILED, NO_POSITION, List.of(), List.of(), withheld, false, false, null);
            }
            detail = "No real position for " + String.join(", ", real);
            real = List.of();
        }
        boolean jokerUsed = state.jokerFake() && real.contains(TimetableInstruction.HUNTERS);
        boolean stale = !real.isEmpty() && state.stale();
        return new Decision(withheld.isEmpty() ? SENT : SKIPPED, withheld.isEmpty() ? null : BREAK, real, fake,
                withheld, jokerUsed, stale, detail);
    }

    // Engine ------------------------------------------------------------------------------------------------

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;
    private final GamePositionWaiter waiter;
    private final GameScheduler scheduler;

    @Inject
    public GamePingEngine(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher,
                          GamePositionWaiter waiter, GameScheduler scheduler) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.waiter = waiter;
        this.scheduler = scheduler;
    }

    private static String audience(String value) {
        if (!TimetableInstruction.AUDIENCES.contains(value)) {
            throw GameErrors.invalid("Invalid ping audience " + value);
        }
        return value;
    }

    /** Checks the shape of an intent before any transaction; {@code waits} allows a freshness wait. */
    static void validate(Intent intent, boolean waits) {
        if (intent == null || intent.path() == null || !PATHS.contains(intent.path())) {
            throw GameErrors.invalid("Invalid ping path");
        }
        boolean relay = RELAY.equals(intent.path());
        if (relay != (intent.recipientMemberId() != null) || relay == (intent.sourceMemberId() != null)) {
            throw GameErrors.invalid(relay ? "A relay names its recipient" : "A ping names its source member");
        }
        List<String> audiences = intent.audiences() == null ? List.of() : intent.audiences();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : audiences) {
            if (!seen.add(audience(value))) {
                throw GameErrors.invalid("Duplicate ping audience " + value);
            }
        }
        if (relay && (!audiences.isEmpty() || intent.fake() != null)) {
            throw GameErrors.invalid("A relay has no audiences or fake");
        }
        if (intent.fake() != null) {
            GamePositions.checkCoordinates(intent.fake().latitude(), intent.fake().longitude());
            if (intent.fake().audiences() == null || intent.fake().audiences().isEmpty()) {
                throw GameErrors.invalid("A fake names its audiences");
            }
            for (String value : intent.fake().audiences()) {
                if (!seen.add(audience(value))) {
                    throw GameErrors.invalid("An audience receives either the real position or the fake");
                }
            }
        }
        if (!relay && seen.isEmpty()) {
            throw GameErrors.invalid("A ping names its audiences");
        }
        if (intent.freshness() != null) {
            if (!waits) {
                throw GameErrors.invalid("Only the server's timetable waits for a fresh fix");
            }
            GameTime.parse(intent.freshness().minimumFixTime(), "minimum fix time");
            Integer wait = intent.freshness().waitSeconds();
            if (wait == null || wait < 0 || wait > MAX_WAIT_SECONDS) {
                throw GameErrors.invalid("waitSeconds must be between 0 and " + MAX_WAIT_SECONDS);
            }
        }
        GameTime.parseOptional(intent.notAfter(), "notAfter");
        for (Effects effects : new Effects[] {intent.onDelivered(), intent.onNotDelivered()}) {
            if (effects != null && (effects.operationList().stream().anyMatch(java.util.Objects::isNull)
                    || effects.requestList().stream().anyMatch(java.util.Objects::isNull)
                    || effects.jokerList().stream().anyMatch(java.util.Objects::isNull))) {
                throw GameErrors.invalid("Invalid ping effect");
            }
        }
    }

    /** The members whose state the effects of these intents may touch, to lock before applying any of them. */
    static Set<Long> members(GameTransaction transaction, long sessionId, List<Intent> intents) throws SQLException {
        Set<Long> result = new TreeSet<>();
        for (Intent intent : intents) {
            for (Effects effects : new Effects[] {intent.onDelivered(), intent.onNotDelivered()}) {
                if (effects != null) {
                    result.addAll(GameOperations.members(transaction, sessionId, effects.operationList()));
                }
            }
        }
        return result;
    }

    private static Effects effects(Effects value) {
        return value == null ? Effects.NONE : value;
    }

    private static String role(String audience) {
        return switch (audience) {
            case TimetableInstruction.HUNTERS -> GameMember.HUNTER;
            case TimetableInstruction.PLAYERS -> GameMember.PLAYER;
            default -> GameMember.SUPPORTER;
        };
    }

    private static Long mirrorDevice(GameTransaction transaction, long sessionId, long source, String role,
                                     Long destinationMember) throws SQLException {
        return GameResourceRepository.mirrorByRoute(transaction, sessionId, source, destinationMember == null
                        ? role : null, destinationMember)
                .filter(mirror -> mirror.active() && mirror.deviceId() != null)
                .map(GameResource.Mirror::deviceId).orElse(null);
    }

    private static boolean otherActive(GameTransaction transaction, long sessionId, String role, long except)
            throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_session_members WHERE gamesessionid = ? AND role = ?"
                + " AND state = ? AND id <> ?", sessionId, role, GameMember.ACTIVE, except);
    }

    /** The newer of a fix handed over by the wait and the latest stored one. */
    private static GamePositions.Fix newer(GamePositions.Fix supplied, Optional<GamePositions.Fix> stored) {
        if (stored.isEmpty()) {
            return supplied;
        }
        if (supplied == null || supplied.fixTime() == null
                || stored.get().fixTime() != null && stored.get().fixTime().isAfter(supplied.fixTime())) {
            return stored.get();
        }
        return supplied;
    }

    private static Instant minimumFixTime(Intent intent) {
        return intent.freshness() == null ? null
                : GameTime.parse(intent.freshness().minimumFixTime(), "minimum fix time");
    }

    private static Double delay(Context context, Instant now) {
        return context.scheduledAt() == null ? null
                : Math.max(0, Duration.between(context.scheduledAt(), now).toMillis()) / 1000.0;
    }

    /**
     * Applies an intent inside a transaction that holds the session lock and the member locks of its effects:
     * decides, applies {@code onDelivered} or {@code onNotDelivered} (with {@code applied}), writes the positions
     * (collected for publication by the caller) and settles the joker fake. {@code supplied} is a fresh fix handed
     * over by the wait, if any.
     */
    Outcome apply(GameTransaction transaction, long sessionId, Intent intent, Context context,
                  GamePositions.Fix supplied, GameOperations applied, GamePositions.Collector collector)
            throws Exception {
        return RELAY.equals(intent.path())
                ? relay(transaction, sessionId, intent, context, applied, collector)
                : participant(transaction, sessionId, intent, context, supplied, applied, collector);
    }

    /** Reads the state of a participant intent and decides it; {@code fix} is the position to use, if any. */
    private static Decision decide(GameTransaction transaction, long sessionId, GameMember source, Intent intent,
                                   GamePositions.Fix fix, boolean jokerFake) throws SQLException {
        Instant now = transaction.now();
        Instant notAfter = GameTime.parseOptional(intent.notAfter(), "notAfter");
        boolean caught = GameStateRepository.activeCatch(transaction, sessionId, source.id()).isPresent();
        boolean onBreak = GameStateRepository.activeBreak(transaction, sessionId, source.id())
                .map(item -> item.protects(now)).orElse(false);
        Set<String> mirrors = new LinkedHashSet<>();
        for (String audience : TimetableInstruction.AUDIENCES) {
            if (mirrorDevice(transaction, sessionId, source.id(), role(audience), null) != null) {
                mirrors.add(audience);
            }
        }
        Instant minimum = minimumFixTime(intent);
        boolean stale = fix != null && minimum != null && (fix.fixTime() == null || fix.fixTime().isBefore(minimum));
        List<String> real = intent.audiences() == null ? List.of() : intent.audiences();
        List<String> fakeAudiences = intent.fake() == null ? List.of() : intent.fake().audiences();
        return decide(real, fakeAudiences, new State(notAfter != null && now.isAfter(notAfter), source.active(),
                source.trackerId() != null, caught, onBreak,
                otherActive(transaction, sessionId, GameMember.PLAYER, source.id()),
                otherActive(transaction, sessionId, GameMember.SUPPORTER, source.id()), mirrors, fix != null, stale,
                jokerFake));
    }

    private Outcome participant(GameTransaction transaction, long sessionId, Intent intent, Context context,
                                GamePositions.Fix supplied, GameOperations applied,
                                GamePositions.Collector collector) throws Exception {
        Instant now = transaction.now();
        GameMember source = GameMemberRepository.find(transaction, sessionId, intent.sourceMemberId())
                .orElseThrow(() -> GameErrors.invalid("Member " + intent.sourceMemberId()
                        + " not found in this game session"));
        if (!GameMember.hasState(source.role())) {
            throw GameErrors.invalid("The " + source.label() + " is not a player or supporter");
        }
        GamePositions.Fix fix = source.trackerId() == null ? null
                : newer(supplied, GamePositions.latest(transaction, source.trackerId()));
        JokerFake joker = context.jokerFake();
        if (joker != null) {
            // Only a fake that is still armed applies; a cancelled one leaves the real position to the hunters.
            var reservation = GameJokerRepository.reservation(transaction, sessionId, joker.reservationId());
            if (reservation.isEmpty() || !reservation.get().armed()) {
                joker = null;
            }
        }
        Decision decision = decide(transaction, sessionId, source, intent, fix, joker != null);

        List<String> delivered = decision.delivered();
        List<GameOperations.Result> results = effects(transaction, sessionId, applied,
                delivered.isEmpty() ? intent.onNotDelivered() : intent.onDelivered());

        List<GamePositions.Written> written = new ArrayList<>();
        if (!delivered.isEmpty()) {
            Instant fixTime = GamePositions.fixTime((String) null, now);
            GameOperations.AppliedFake speedhuntFake = applied.appliedFakes().get(source.id());
            for (String audience : TimetableInstruction.AUDIENCES) {
                double latitude;
                double longitude;
                if (decision.fake().contains(audience)) {
                    latitude = intent.fake().latitude();
                    longitude = intent.fake().longitude();
                } else if (decision.real().contains(audience)) {
                    if (decision.jokerFakeUsed() && TimetableInstruction.HUNTERS.equals(audience)) {
                        latitude = joker.latitude();
                        longitude = joker.longitude();
                    } else {
                        latitude = fix.latitude();
                        longitude = fix.longitude();
                    }
                    if (speedhuntFake != null && TimetableInstruction.HUNTERS.equals(audience)) {
                        // The target's own speedhunt fake reaches hunter destinations only.
                        double[] coordinate = GameEffectsService.fakeCoordinate(speedhuntFake.latitude(),
                                speedhuntFake.longitude(), speedhuntFake.live());
                        latitude = coordinate[0];
                        longitude = coordinate[1];
                    }
                } else {
                    continue;
                }
                long device = mirrorDevice(transaction, sessionId, source.id(), role(audience), null);
                written.add(GamePositions.write(transaction, objectMapper, device, latitude, longitude, fixTime,
                        null, collector));
            }
        }

        JokerFakeOutcome jokerOutcome = null;
        if (joker != null) {
            var reservation = GameJokerRepository.reservation(transaction, sessionId, joker.reservationId())
                    .orElseThrow();
            String status;
            if (decision.jokerFakeUsed()) {
                GameJokerRepository.consume(transaction, reservation, "Server", "Used on its timetable ping");
                status = GameJokerRepository.CONSUMED;
            } else {
                GameJokerRepository.release(transaction, reservation, "Server", fakeReleaseDetail(decision));
                status = GameJokerRepository.RELEASED;
            }
            applied.jokerMembers().add(source.id());
            jokerOutcome = new JokerFakeOutcome(joker.reservationId(), status, joker.latitude(), joker.longitude());
        }

        Fake fake = decision.fake().isEmpty() ? null
                : new Fake(intent.fake().latitude(), intent.fake().longitude(), decision.fake());
        PositionOutcome position = fix == null || decision.real().isEmpty() ? null
                : new PositionOutcome(fix.latitude(), fix.longitude(),
                        fix.fixTime() == null ? null : fix.fixTime().toString(), decision.stale());
        return new Outcome(intent.path(), source.id(), null, decision.status(), decision.reason(), delivered,
                decision.withheld(), fake, jokerOutcome, position, delay(context, now), decision.detail(), null,
                written, results);
    }

    private static String fakeReleaseDetail(Decision decision) {
        if (BREAK.equals(decision.reason()) || decision.withheld().contains(TimetableInstruction.HUNTERS)) {
            return "Its timetable ping falls inside a break";
        }
        if (SENT.equals(decision.status()) || SKIPPED.equals(decision.status()) && decision.reason() == null) {
            return "The hunters did not receive its ping";
        }
        return "Its timetable ping could not be sent";
    }

    /** Relays every active hunter's latest position to the recipient's per-hunter mirrors. */
    private Outcome relay(GameTransaction transaction, long sessionId, Intent intent, Context context,
                          GameOperations applied, GamePositions.Collector collector) throws Exception {
        Instant now = transaction.now();
        GameMember recipient = GameMemberRepository.find(transaction, sessionId, intent.recipientMemberId())
                .orElseThrow(() -> GameErrors.invalid("Member " + intent.recipientMemberId()
                        + " not found in this game session"));
        if (!GameMember.hasState(recipient.role())) {
            throw GameErrors.invalid("The " + recipient.label() + " is not a player or supporter");
        }
        Instant notAfter = GameTime.parseOptional(intent.notAfter(), "notAfter");
        String status;
        String reason = null;
        String detail = null;
        List<Long> relayed = new ArrayList<>();
        List<GamePositions.Written> written = new ArrayList<>();
        List<GameOperations.Result> results;
        if (notAfter != null && now.isAfter(notAfter)) {
            status = SKIPPED;
            reason = MISSED;
        } else if (!recipient.active()) {
            status = SKIPPED;
            reason = INACTIVE;
        } else if (GameStateRepository.activeCatch(transaction, sessionId, recipient.id()).isPresent()) {
            status = SKIPPED;
            reason = CAUGHT;
        } else {
            record Item(long hunter, long device, GamePositions.Fix fix) {
            }
            List<Item> items = new ArrayList<>();
            int noPosition = 0;
            int noMirror = 0;
            List<GameMember> hunters = GameMemberRepository.list(transaction, sessionId).stream()
                    .filter(member -> GameMember.HUNTER.equals(member.role()) && member.active()).toList();
            for (GameMember hunter : hunters) {
                Optional<GamePositions.Fix> fix = hunter.trackerId() == null ? Optional.empty()
                        : GamePositions.latest(transaction, hunter.trackerId());
                if (fix.isEmpty()) {
                    noPosition++;
                    continue;
                }
                Long device = mirrorDevice(transaction, sessionId, hunter.id(), null, recipient.id());
                if (device == null) {
                    noMirror++;
                    continue;
                }
                items.add(new Item(hunter.id(), device, fix.get()));
            }
            if (!items.isEmpty()) {
                status = SENT;
                if (noPosition + noMirror > 0) {
                    detail = (noPosition > 0 ? noPosition + " hunter(s) without a position" : "")
                            + (noPosition > 0 && noMirror > 0 ? ", " : "")
                            + (noMirror > 0 ? noMirror + " hunter(s) without a mirror" : "");
                }
            } else {
                status = FAILED;
                reason = hunters.isEmpty() ? NO_RECIPIENT : noMirror > 0 ? NO_MIRROR : NO_POSITION;
            }
            results = effects(transaction, sessionId, applied,
                    items.isEmpty() ? intent.onNotDelivered() : intent.onDelivered());
            for (Item item : items) {
                // A relayed position keeps the hunter's own fix time, so its age stays visible.
                Instant fixTime = item.fix().fixTime() == null ? now : item.fix().fixTime();
                written.add(GamePositions.write(transaction, objectMapper, item.device(), item.fix().latitude(),
                        item.fix().longitude(), GamePositions.fixTime(fixTime.isAfter(now) ? now : fixTime, now),
                        null, collector));
                relayed.add(item.hunter());
            }
            return new Outcome(RELAY, null, recipient.id(), status, reason,
                    relayed.isEmpty() ? List.of() : List.of(TimetableInstruction.HUNTERS), List.of(), null, null,
                    null, delay(context, now), detail, relayed, written, results);
        }
        results = effects(transaction, sessionId, applied, intent.onNotDelivered());
        return new Outcome(RELAY, null, recipient.id(), status, reason, List.of(), List.of(), null, null, null,
                delay(context, now), detail, relayed, written, results);
    }

    /** Applies the chosen effects; returns the results of their operations. */
    private static List<GameOperations.Result> effects(GameTransaction transaction, long sessionId,
                                                       GameOperations applied, Effects value) throws Exception {
        Effects chosen = effects(value);
        if (chosen.empty()) {
            return List.of();
        }
        int before = applied.results().size();
        GameRequestService.complete(transaction, sessionId, chosen.requestList(), applied);
        List<GameOperation> operations = chosen.operationList();
        for (int i = 0; i < operations.size(); i++) {
            applied.apply(i, operations.get(i));
        }
        for (GameJokerService.Use use : chosen.jokerList()) {
            GameJokerService.UseOutcome used = GameJokerService.use(transaction, sessionId, use, "Tool");
            applied.jokerMembers().add(used.memberId());
        }
        return List.copyOf(applied.results().subList(before, applied.results().size()));
    }

    // Execution ---------------------------------------------------------------------------------------------

    /**
     * Runs an intent on its own: a freshness wait first (without any lock), then one transaction. The future
     * completes with the outcome, or with null when {@link Hooks#proceed} declined it in the transaction.
     */
    public CompletableFuture<Outcome> execute(long sessionId, Intent intent, Context context, Hooks hooks) {
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        try {
            validate(intent, true);
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            return result;
        }
        if (intent.freshness() == null || RELAY.equals(intent.path())) {
            scheduler.execute(() -> finish(sessionId, intent, context, hooks, null, result));
            return result;
        }
        scheduler.execute(() -> startWait(sessionId, intent, context, hooks, result));
        return result;
    }

    /** The tracker to wait for, or null when the intent needs no fresh fix (decided without a position). */
    private Long waitTarget(long sessionId, Intent intent, Instant minimum) throws Exception {
        return transactions.read(transaction -> {
            GameMember source = GameMemberRepository.find(transaction, sessionId, intent.sourceMemberId())
                    .orElse(null);
            if (source == null || !GameMember.hasState(source.role()) || source.trackerId() == null) {
                return null;
            }
            Optional<GamePositions.Fix> stored = GamePositions.latest(transaction, source.trackerId());
            if (stored.isPresent() && stored.get().fixTime() != null && !stored.get().fixTime().isBefore(minimum)) {
                return null;
            }
            // Wait only when a fresh fix would be sent: an intent that the rules settle without the real position
            // (caught, a break that leaves only the hunters, nobody to receive it, a coordinate for every
            // audience) is decided at once.
            Decision decision = decide(transaction, sessionId, source, intent,
                    new GamePositions.Fix(0, 0, minimum), false);
            return decision.real().isEmpty() ? null : source.trackerId();
        });
    }

    private void startWait(long sessionId, Intent intent, Context context, Hooks hooks,
                           CompletableFuture<Outcome> result) {
        Instant minimum = minimumFixTime(intent);
        Long tracker;
        try {
            tracker = waitTarget(sessionId, intent, minimum);
        } catch (Exception error) {
            result.completeExceptionally(error);
            return;
        }
        long waitMillis = intent.freshness().waitSeconds() * 1000L;
        Instant notAfter = GameTime.parseOptional(intent.notAfter(), "notAfter");
        if (notAfter != null) {
            waitMillis = Math.min(waitMillis, Math.max(0, Duration.between(transactions.now(), notAfter)
                    .toMillis()));
        }
        if (tracker == null || waitMillis <= 0) {
            finish(sessionId, intent, context, hooks, null, result);
            return;
        }
        CompletableFuture<GamePositions.Fix> arrival = waiter.await(tracker, minimum);
        AtomicBoolean done = new AtomicBoolean();
        List<GameScheduler.Handle> handles = new ArrayList<>();
        Runnable stop = () -> {
            handles.forEach(GameScheduler.Handle::cancel);
            arrival.cancel(false);
        };
        java.util.function.Consumer<GamePositions.Fix> complete = fix -> {
            if (done.compareAndSet(false, true)) {
                stop.run();
                finish(sessionId, intent, context, hooks, fix, result);
            }
        };
        arrival.thenAccept(fix -> scheduler.execute(() -> complete.accept(fix)));
        handles.add(scheduler.schedule(() -> complete.accept(null), waitMillis));
        handles.add(scheduler.repeat(() -> {
            if (done.get()) {
                return;
            }
            if (!hooks.wanted()) {
                complete.accept(null);
                return;
            }
            try {
                Optional<GamePositions.Fix> stored = transactions.read(
                        transaction -> GamePositions.latest(transaction, tracker));
                if (stored.isPresent() && stored.get().fixTime() != null
                        && !stored.get().fixTime().isBefore(minimum)) {
                    complete.accept(stored.get());
                }
            } catch (Exception error) {
                LOGGER.warn("Latest position poll failed", error);
            }
        }, POLL_MILLIS, POLL_MILLIS));
    }

    private void finish(long sessionId, Intent intent, Context context, Hooks hooks, GamePositions.Fix fix,
                        CompletableFuture<Outcome> result) {
        try {
            result.complete(run(sessionId, intent, context, hooks, fix));
        } catch (Exception | Error error) {
            result.completeExceptionally(error);
        }
    }

    /** The intent's transaction. */
    Outcome run(long sessionId, Intent intent, Context context, Hooks hooks, GamePositions.Fix fix)
            throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (!hooks.proceed(transaction)) {
                return null;
            }
            transaction.lockMemberStates(members(transaction, sessionId, List.of(intent)));
            GameOperations applied = new GameOperations(transaction, sessionId, "Server", objectMapper, publisher);
            GamePositions.Collector collector = new GamePositions.Collector();
            Outcome outcome = apply(transaction, sessionId, intent, context, fix, applied, collector);
            hooks.completed(transaction, outcome);
            transaction.bumpSession();
            GameStateService.publish(transaction, publisher, sessionId, applied);
            collector.publish(transaction, publisher);
            return outcome;
        });
    }

    /** Ping intents of one effects delivery at most. */
    static void validateAll(List<Intent> intents) {
        if (intents != null) {
            if (intents.size() > MAX_PINGS) {
                throw GameErrors.invalid("Delivery is too large");
            }
            for (Intent intent : intents) {
                validate(intent, false);
            }
        }
    }

}
