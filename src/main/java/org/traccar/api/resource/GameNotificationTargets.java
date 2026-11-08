/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.GameNotificationService;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

/**
 * Verifies the recipient and source of targeted game notifications. Shared by the notification endpoint and
 * fenced effects deliveries, so both archive only alerts a participant may see.
 */
public class GameNotificationTargets {

    private final Storage storage;
    private final PermissionsService permissionsService;

    @Inject
    public GameNotificationTargets(Storage storage, PermissionsService permissionsService) {
        this.storage = storage;
        this.permissionsService = permissionsService;
    }

    public void check(long operatorUserId, long sessionDeviceId, GameNotificationService.Alert alert)
            throws Exception {
        if ("user".equals(alert.audience())) {
            checkTarget(alert, sessionDeviceId);
        } else if (GameNotificationService.isLiveRecipientAlert(alert)) {
            checkLiveRecipient(operatorUserId, alert, sessionDeviceId);
        }
    }

    private void checkTarget(GameNotificationService.Alert alert, long sessionDeviceId) throws Exception {
        try {
            User recipient = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", alert.recipientUserId())));
            String role = recipient == null ? null : String.valueOf(
                    recipient.getAttributes().get("traccarToolUserRole"));
            if (recipient == null || recipient.getAdministrator()
                    || !("player".equals(role) || "supporter".equals(role) || "hunter".equals(role))
                    || !GameNotificationService.canReceive(recipient, sessionDeviceId)
                    || !Long.toString(alert.sourceDeviceId()).equals(String.valueOf(
                            recipient.getAttributes().get(GameNotificationService.ASSIGNED_DEVICE_ATTRIBUTE)))) {
                throw new SecurityException("Notification recipient does not match the source device");
            }
            Device permittedSource = storage.getObject(Device.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("id", alert.sourceDeviceId()),
                            new Condition.Permission(User.class, recipient.getId(), Device.class))));
            if (permittedSource == null) {
                throw new SecurityException("Notification recipient cannot access the source device");
            }
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    private void checkLiveRecipient(long operatorUserId, GameNotificationService.Alert alert, long sessionDeviceId)
            throws Exception {
        try {
            User recipient = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", alert.recipientUserId())));
            String role = recipient == null ? null : String.valueOf(
                    recipient.getAttributes().get("traccarToolUserRole"));
            if (!GameNotificationService.canReceive(recipient, sessionDeviceId)
                    || !GameNotificationService.liveRecipientAudience(role).equals(alert.audience())
                    || alert.sourceDeviceId().equals(GameNotificationService.assignedDeviceId(recipient))) {
                throw new SecurityException("Live notification recipient is not eligible");
            }
            permissionsService.checkPermission(Device.class, operatorUserId, alert.sourceDeviceId());
            Device source = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", alert.sourceDeviceId())));
            if (source == null || Boolean.TRUE.equals(source.getAttributes().get("traccarToolMirror"))) {
                throw new SecurityException("Live notification source is not a real device");
            }
            var owners = storage.getObjects(User.class, new Request(new Columns.All())).stream()
                    .filter(user -> GameNotificationService.canReceive(user, sessionDeviceId))
                    .filter(user -> "player".equals(user.getAttributes().get("traccarToolUserRole"))
                            || "supporter".equals(user.getAttributes().get("traccarToolUserRole")))
                    .filter(user -> alert.sourceDeviceId().equals(GameNotificationService.assignedDeviceId(user)))
                    .toList();
            if (owners.size() != 1) {
                throw new SecurityException("Live notification source has no unique participant in this session");
            }
            Device permittedSource = storage.getObject(Device.class, new Request(new Columns.Include("id"),
                    new Condition.And(new Condition.Equals("id", alert.sourceDeviceId()),
                            new Condition.Permission(User.class, owners.getFirst().getId(), Device.class))));
            if (permittedSource == null) {
                throw new SecurityException("Live notification source owner cannot access the device");
            }
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }
}
