/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

/**
 * Work that a change of a member's account or tracker binding requires in the same transaction, including
 * retirement, which clears both. Step 3 invalidates the member's pending requests and armed plans that depended on
 * the old binding here (plan §2.2).
 *
 * <p>A hook runs after the session and the member state are locked, the binding is changed, and the member's
 * proximity grants are revoked, and before the revisions advance. It may change rows of this member only: locking
 * another member's state would break the lock order (plan §3.1).
 */
@FunctionalInterface
public interface GameBindingHook {

    void bindingChanged(GameTransaction transaction, GameMember before, GameMember after) throws Exception;

}
