/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Members of data model 2 (plan §2.2): stable places in a game, planned before their Traccar account and tracker
 * exist, and bound to them later.
 *
 * <ul>
 * <li>A member's role never changes. {@link #changeRole} retires the member and creates a new one.</li>
 * <li>Numbers are never reused in a session, also after retirement. The spectator is number 1, so a session has
 * at most one spectator; it has no tracker and is never retired, because it could not be replaced.</li>
 * <li>An account is bound to at most one member of all sessions; a tracker to at most one member per session, and
 * never while it is a mirror.</li>
 * <li>Changing a binding, and retiring, revokes the member's proximity grants, runs the {@link GameBindingHook}s,
 * and advances the member state and session revisions, all in one transaction.</li>
 * </ul>
 *
 * <p>After commit, a binding change tells the session's members and both accounts, the one it bound and the one it
 * removed, to read their status again: an account learns that it gained or lost its membership without waiting for
 * its next periodic read. Revoked proximity grants refresh their recipients' device access.
 */
@Singleton
public class GameMemberService {

    public static final Set<String> ROLES = Set.of(
            GameMember.PLAYER, GameMember.SUPPORTER, GameMember.HUNTER, GameMember.SPECTATOR);

    /** The game membership of an account: the model-2 counterpart of the model-1 request membership. */
    public record Membership(long gameSessionId, long memberId, String role, int number, Long trackerId,
                             String displayName) {
    }

    private final GameTransactions transactions;
    private final Set<GameBindingHook> hooks;
    private final GamePublisher publisher;

    @Inject
    public GameMemberService(GameTransactions transactions, Set<GameBindingHook> hooks, GamePublisher publisher) {
        this.transactions = transactions;
        this.hooks = hooks;
        this.publisher = publisher;
    }

    public GameMemberService(GameTransactions transactions, Set<GameBindingHook> hooks) {
        this(transactions, hooks, GamePublisher.NONE);
    }

    public GameMemberService(GameTransactions transactions) {
        this(transactions, Set.of());
    }

    /** After commit: the session's members read their status again (roster, labels, revisions). */
    private void publishStatus(GameTransaction transaction, long sessionId) {
        transaction.afterCommit(() -> publisher.invalidate(
                new GamePublisher.Invalidation(sessionId, GamePublisher.STATUS, null)));
    }

    private static String role(String value) {
        if (!ROLES.contains(value)) {
            throw GameErrors.invalid("Invalid member role");
        }
        return value;
    }

    private static String displayName(String value) {
        return GameErrors.text(value, 128, "member display name");
    }

    private static GameMember member(GameTransaction transaction, long sessionId, long memberId) throws Exception {
        return GameMemberRepository.find(transaction, sessionId, memberId)
                .orElseThrow(() -> GameErrors.notFound("Game member not found"));
    }

    /** Locks the session, then the member's state, and returns the member. */
    private static GameMember lock(GameTransaction transaction, long sessionId, long memberId) throws Exception {
        transaction.lockSession(sessionId);
        transaction.lockMemberStates(List.of(memberId));
        return member(transaction, sessionId, memberId);
    }

    private static GameMember active(GameMember member) {
        if (!member.active()) {
            throw GameErrors.conflict("The " + member.label() + " is retired");
        }
        return member;
    }

    // Reads -------------------------------------------------------------------------------------------------

    public List<GameMember> list(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            if (GameSessionRepository.find(transaction, sessionId).isEmpty()) {
                throw GameErrors.notFound("Game session not found");
            }
            return GameMemberRepository.list(transaction, sessionId);
        });
    }

    public GameMember get(long sessionId, long memberId) throws Exception {
        return transactions.read(transaction -> member(transaction, sessionId, memberId));
    }

    /** The membership of an account, if it is bound to a member. */
    public Optional<Membership> find(long userId) throws Exception {
        return transactions.read(transaction -> GameMemberRepository.byUser(transaction, userId).map(
                member -> new Membership(member.gameSessionId(), member.id(), member.role(), member.number(),
                        member.trackerId(), member.displayName())));
    }

    /** The member an account is bound to; 403 when the account takes part in no game. */
    public GameMember caller(long userId) throws Exception {
        return transactions.read(transaction -> GameMemberRepository.byUser(transaction, userId))
                .orElseThrow(() -> new ForbiddenException("This account does not take part in a game"));
    }

    /** The membership of an account; 403 when the account takes part in no game. */
    public Membership membership(long userId) throws Exception {
        return find(userId).orElseThrow(() -> new ForbiddenException("This account does not take part in a game"));
    }

    // Planning ----------------------------------------------------------------------------------------------

    private static GameMember plan(GameTransaction transaction, long sessionId, String role, Integer number,
                                   String displayName) throws Exception {
        int value;
        if (GameMember.SPECTATOR.equals(role)) {
            if (number != null && number != 1) {
                throw GameErrors.invalid("The spectator is number 1");
            }
            if (GameMemberRepository.numberUsed(transaction, sessionId, role, 1)) {
                throw GameErrors.conflict("This game session already has a spectator");
            }
            value = 1;
        } else if (number == null) {
            value = GameMemberRepository.nextNumber(transaction, sessionId, role);
        } else {
            if (number < 1) {
                throw GameErrors.invalid("Invalid member number");
            }
            if (GameMemberRepository.numberUsed(transaction, sessionId, role, number)) {
                throw GameErrors.conflict("The " + role + " " + number
                        + " already exists in this game session; member numbers are never reused");
            }
            value = number;
        }
        long id = GameMemberRepository.insert(transaction, sessionId, role, value, displayName);
        return member(transaction, sessionId, id);
    }

    /**
     * Plans a member without account or tracker. Without a number, the member gets the next number of its role
     * that was never used in the session.
     */
    public GameMember plan(long sessionId, String role, Integer number, String displayName) throws Exception {
        String validRole = role(role);
        String validName = displayName(displayName);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameMember member = plan(transaction, sessionId, validRole, number, validName);
            transaction.bumpSession();
            publishStatus(transaction, sessionId);
            return member;
        });
    }

    public GameMember rename(long sessionId, long memberId, String displayName) throws Exception {
        String validName = displayName(displayName);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameMember member = active(member(transaction, sessionId, memberId));
            if (member.displayName().equals(validName)) {
                return member;
            }
            GameMemberRepository.setDisplayName(transaction, sessionId, memberId, validName);
            transaction.bumpSession();
            publishStatus(transaction, sessionId);
            return member(transaction, sessionId, memberId);
        });
    }

    // Bindings ----------------------------------------------------------------------------------------------

    private static void checkAccount(GameTransaction transaction, GameMember member, long userId) throws Exception {
        if (!GameMemberRepository.userExists(transaction, userId)) {
            throw GameErrors.invalid("Account not found");
        }
        Optional<GameMember> bound = GameMemberRepository.byUser(transaction, userId);
        if (bound.isPresent() && bound.get().id() != member.id()) {
            GameMember other = bound.get();
            throw GameErrors.conflict(other.gameSessionId() == member.gameSessionId()
                    ? "The account is already bound to the " + other.label() + " of this game session"
                    : "The account is already bound to a member of game session " + other.gameSessionId()
                            + "; an account takes part in one game session");
        }
    }

    private static void checkTracker(GameTransaction transaction, GameMember member, long trackerId)
            throws Exception {
        if (GameMember.SPECTATOR.equals(member.role())) {
            throw GameErrors.conflict("The spectator has no tracker");
        }
        if (!GameMemberRepository.deviceExists(transaction, trackerId)) {
            throw GameErrors.invalid("Tracker not found");
        }
        Optional<GameMember> bound = GameMemberRepository.byTracker(transaction, member.gameSessionId(), trackerId);
        if (bound.isPresent() && bound.get().id() != member.id()) {
            throw GameErrors.conflict("The tracker is already bound to the " + bound.get().label()
                    + " of this game session");
        }
        if (GameResourceRepository.mirrorByDevice(transaction, trackerId).isPresent()) {
            throw GameErrors.conflict("The device is a game mirror and cannot be a tracker");
        }
    }

    /**
     * Applies the consequences of a changed binding to a locked member: revokes its proximity grants, runs the
     * hooks, and advances the member state and session revisions.
     */
    private GameMember bindingChanged(GameTransaction transaction, GameMember before) throws Exception {
        long sessionId = before.gameSessionId();
        GameMember after = member(transaction, sessionId, before.id());
        Set<Long> recipients = Set.copyOf(transaction.list("SELECT DISTINCT userid FROM tc_game_proximity_grants"
                + " WHERE gamesessionid = ? AND (recipientmemberid = ? OR targetmemberid = ?)",
                rows -> rows.getLong(1), sessionId, before.id(), before.id()));
        if (GameProximityRepository.revokeMember(transaction, sessionId, before.id()) > 0) {
            transaction.afterCommit(() -> publisher.proximityAccess(sessionId, recipients));
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.PROXIMITY, null)));
        }
        for (GameBindingHook hook : hooks) {
            hook.bindingChanged(transaction, before, after);
        }
        transaction.bumpMemberState(before.id());
        transaction.bumpSession();
        publishStatus(transaction, sessionId);
        Set<Long> accounts = new java.util.HashSet<>();
        if (before.userId() != null) {
            accounts.add(before.userId());
        }
        if (after.userId() != null) {
            accounts.add(after.userId());
        }
        if (!accounts.isEmpty()) {
            Set<Long> affected = Set.copyOf(accounts);
            transaction.afterCommit(() -> publisher.accounts(affected, GamePublisher.STATUS));
        }
        return member(transaction, sessionId, before.id());
    }

    private GameMember bindAccount(GameTransaction transaction, GameMember member, Long userId) throws Exception {
        if (userId != null) {
            checkAccount(transaction, member, userId);
        }
        if (Objects.equals(member.userId(), userId)) {
            return member;
        }
        GameMemberRepository.setUser(transaction, member.gameSessionId(), member.id(), userId);
        return bindingChanged(transaction, member);
    }

    private GameMember bindTracker(GameTransaction transaction, GameMember member, Long trackerId) throws Exception {
        if (trackerId != null) {
            checkTracker(transaction, member, trackerId);
        }
        if (Objects.equals(member.trackerId(), trackerId)) {
            return member;
        }
        GameMemberRepository.setTracker(transaction, member.gameSessionId(), member.id(), trackerId);
        return bindingChanged(transaction, member);
    }

    /** Binds an account to an active member, replacing its current account. Binding the same account is a no-op. */
    public GameMember bindAccount(long sessionId, long memberId, long userId) throws Exception {
        return transactions.write(transaction -> bindAccount(
                transaction, active(lock(transaction, sessionId, memberId)), userId));
    }

    public GameMember unbindAccount(long sessionId, long memberId) throws Exception {
        return transactions.write(transaction -> bindAccount(
                transaction, active(lock(transaction, sessionId, memberId)), null));
    }

    /** Binds a tracker to an active member, replacing its current tracker. Binding the same tracker is a no-op. */
    public GameMember bindTracker(long sessionId, long memberId, long trackerId) throws Exception {
        return transactions.write(transaction -> bindTracker(
                transaction, active(lock(transaction, sessionId, memberId)), trackerId));
    }

    public GameMember unbindTracker(long sessionId, long memberId) throws Exception {
        return transactions.write(transaction -> bindTracker(
                transaction, active(lock(transaction, sessionId, memberId)), null));
    }

    // Retirement --------------------------------------------------------------------------------------------

    /** Why a retired member's running speedhunt, live uploads and the requests on it ended. */
    static final String RETIRED = "The member was retired";

    private GameMember retire(GameTransaction transaction, GameMember member) throws Exception {
        if (!member.active()) {
            return member;
        }
        if (GameMember.SPECTATOR.equals(member.role())) {
            throw GameErrors.conflict("The spectator cannot be retired, because number 1 is never reused;"
                    + " unbind its account instead");
        }
        endActivity(transaction, member);
        GameMemberRepository.retire(transaction, member.gameSessionId(), member.id());
        return bindingChanged(transaction, member);
    }

    /**
     * Ends what a retiring member takes part in, so nothing waits for a member that can no longer act or be acted
     * on: its running speedhunt (terminated, and armed fakes on the pings it did not reach are returned), its active
     * live uploads as their source or relay recipient, and hunters' open requests on it. Its catch, breaks and
     * history stay; its own requests and plans end through the binding hooks.
     */
    private void endActivity(GameTransaction transaction, GameMember member) throws Exception {
        long sessionId = member.gameSessionId();
        Set<Long> owners = new java.util.HashSet<>();
        boolean history = false;
        var run = GameStateRepository.activeRun(transaction, sessionId, member.id());
        if (run.isPresent()) {
            GameStateRepository.endRun(transaction, sessionId, run.get().id(), GameState.TERMINATED, RETIRED);
            for (var plan : GameJokerRepository.plans(transaction, sessionId, "p.boundrunid = ? AND "
                    + GameJokerRepository.ARMED, run.get().id())) {
                GameJokerRepository.release(transaction,
                        GameJokerRepository.reservation(transaction, sessionId, plan.reservationId()).orElseThrow(),
                        "Server", "The speedhunt ended when its target was retired");
                owners.add(plan.memberId());
            }
            history = true;
        }
        for (GameState.LiveUpload upload : GameStateRepository.activeUploads(transaction, sessionId)) {
            if (Objects.equals(upload.sourceMemberId(), member.id())
                    || Objects.equals(upload.recipientMemberId(), member.id())) {
                GameStateRepository.endUpload(transaction, sessionId, upload.id(), "retired");
                history |= GameState.SPEEDHUNT_PING.equals(upload.purpose());
            }
        }
        boolean requests = false;
        boolean pool = false;
        for (GameRequest request : GameRequestRepository.open(transaction, sessionId)) {
            if (request.targetMemberId() == null || request.targetMemberId() != member.id()
                    || Objects.equals(request.requesterMemberId(), member.id())) {
                continue;
            }
            String status = GameRequest.EXECUTING.equals(request.status()) ? GameRequest.FAILED : GameRequest.EXPIRED;
            var settled = GameRequestRepository.finish(transaction, request, status, RETIRED, null, false);
            owners.addAll(settled.members());
            pool |= settled.pool();
            requests = true;
        }
        if (pool) {
            owners.addAll(GameStateService.hunters(transaction, sessionId));
        }
        if (history) {
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.SPEEDHUNT_HISTORY, null)));
        }
        if (requests) {
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.REQUESTS, null)));
        }
        if (!owners.isEmpty()) {
            Set<Long> recipients = Set.copyOf(owners);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, recipients)));
        }
    }

    /**
     * Retires a member: clears its account and tracker and keeps its state and history. Retiring a retired member
     * is a no-op.
     */
    public GameMember retire(long sessionId, long memberId) throws Exception {
        return transactions.write(transaction -> retire(transaction, lock(transaction, sessionId, memberId)));
    }

    /**
     * Changes a role the only way it can change: retires the member and plans a new one with the new role, which
     * takes over the account and, unless it is the spectator, the tracker. Jokers are not carried over.
     */
    public GameMember changeRole(long sessionId, long memberId, String role, Integer number, String displayName)
            throws Exception {
        String validRole = role(role);
        String validName = displayName(displayName);
        return transactions.write(transaction -> {
            GameMember member = active(lock(transaction, sessionId, memberId));
            if (member.role().equals(validRole)) {
                throw GameErrors.conflict("The " + member.label() + " already has this role");
            }
            retire(transaction, member);
            GameMember created = plan(transaction, sessionId, validRole, number, validName);
            if (member.userId() != null) {
                created = bindAccount(transaction, created, member.userId());
            }
            if (member.trackerId() != null && !GameMember.SPECTATOR.equals(validRole)) {
                created = bindTracker(transaction, created, member.trackerId());
            }
            transaction.bumpSession();
            return created;
        });
    }

}
