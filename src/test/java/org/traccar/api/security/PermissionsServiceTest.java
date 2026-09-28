/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.security;

import org.junit.jupiter.api.Test;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PermissionsServiceTest {

    private PermissionsService service(User currentUser) throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(currentUser);
        return new PermissionsService(storage);
    }

    @Test
    public void testDrawingRestrictionRequiresAdministrator() throws Exception {
        User currentUser = new User();
        currentUser.setId(1);
        User before = new User();
        before.setId(2);
        User after = new User();
        after.setId(2);
        after.setDisableDrawings(false);

        assertThrows(SecurityException.class, () -> service(currentUser).checkUserUpdate(1, before, after));

        currentUser.setAdministrator(true);
        assertDoesNotThrow(() -> service(currentUser).checkUserUpdate(1, before, after));
    }

    @Test
    public void testToolMarkersRequireAdministrator() throws Exception {
        User currentUser = new User();
        currentUser.setId(1);
        User before = new User();
        before.setId(2);
        User after = new User();
        after.setId(2);
        after.getAttributes().put("traccarToolUserRole", "hunter");
        after.getAttributes().put("traccarToolSessionDeviceId", 7);

        assertThrows(SecurityException.class, () -> service(currentUser).checkUserUpdate(1, before, after));

        currentUser.setAdministrator(true);
        assertDoesNotThrow(() -> service(currentUser).checkUserUpdate(1, before, after));
    }

}
