package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies typed game operations outside an effects delivery and reads the typed game state of a session, for tests:
 * the server applies operations only with effects deliveries and its own execution.
 */
public final class GameStateFixture {

    /** Operations applied in one transaction, and the session aggregate revision after it. */
    public record Applied(List<GameOperations.Result> results, List<GameOperations.FakeOutcome> fakes,
                          long sessionRevision) {
    }

    /** The current state: active catches, running speedhunts, current breaks and active live uploads. */
    public record Snapshot(List<GameState.Catch> catches, List<GameState.Run> runs, List<GameState.Break> breaks,
                           List<GameState.LiveUpload> liveUploads, Map<Long, Long> revisions) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;

    public GameStateFixture(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    /** Applies operations in one transaction as an operator, publishing what they change after commit. */
    public Applied apply(long sessionId, List<GameOperation> operations) throws Exception {
        if (operations == null || operations.isEmpty()
                || operations.stream().anyMatch(java.util.Objects::isNull)) {
            throw GameErrors.invalid("Invalid game operations");
        }
        GameOperations.checkSent(operations);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            transaction.lockMemberStates(GameOperations.members(transaction, sessionId, operations));
            GameOperations applied = new GameOperations(transaction, sessionId, "Operator", objectMapper, publisher);
            for (int i = 0; i < operations.size(); i++) {
                applied.apply(i, operations.get(i));
            }
            if (!applied.immediateBreaks().isEmpty()) {
                throw GameErrors.invalid("An immediate break starts with its request's outcome");
            }
            long revision = transaction.bumpSession();
            GameOperations.publish(transaction, publisher, sessionId, applied);
            return new Applied(applied.results(), applied.fakes(), revision);
        });
    }

    private <T> T read(long sessionId, GameTransactions.Work<T> work) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return work.run(transaction);
        });
    }

    public Snapshot snapshot(long sessionId) throws Exception {
        return read(sessionId, transaction -> new Snapshot(
                GameStateRepository.catches(transaction, sessionId, true),
                GameStateRepository.runs(transaction, sessionId, true),
                GameStateRepository.currentBreaks(transaction, sessionId, null),
                GameStateRepository.activeUploads(transaction, sessionId),
                GameStateRepository.revisions(transaction, sessionId)));
    }

    /** Every catch interval of a session, oldest first. */
    public List<GameState.Catch> catches(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.catches(transaction, sessionId, false));
    }

    /** Every run with its delivered pings, newest first. */
    public List<GameStateRepository.RunHistory> runs(long sessionId) throws Exception {
        return read(sessionId, transaction -> {
            List<GameStateRepository.RunHistory> result = new ArrayList<>();
            for (GameState.Run run : GameStateRepository.runs(transaction, sessionId, false)) {
                result.add(new GameStateRepository.RunHistory(run,
                        GameStateRepository.pings(transaction, sessionId, run.id())));
            }
            return result;
        });
    }

    public List<GameState.Break> breaks(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.breaks(transaction, sessionId));
    }

    public List<GameState.LiveUpload> liveUploads(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.uploads(transaction, sessionId));
    }

    /** Per member: speedhunts, delivered pings, live pings and the start of the latest speedhunt. */
    public List<GameStateRepository.Statistics> statistics(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.statistics(transaction, sessionId));
    }

    /** The target of the most recently started speedhunt, if any. */
    public Long lastSpeedhuntTarget(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.lastRun(transaction, sessionId)
                .map(GameState.Run::targetMemberId).orElse(null));
    }

    /** Whether any speedhunt runs: an active run or an active live speedhunt ping. */
    public boolean speedhuntRunning(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.speedhuntRunning(transaction, sessionId));
    }

    /** Hunter live shares ({@link GameStateRepository#liveShares}). */
    public List<GameStateRepository.LiveShare> liveShares(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.liveShares(transaction, sessionId));
    }

}
