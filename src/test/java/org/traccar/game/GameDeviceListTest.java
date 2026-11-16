package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The device list of a session (game execution plan, phase 5): an empty list without a document, every validation
 * rule listed together (nothing stored on a rejection), malformed documents, revisions and the idempotent retry,
 * that saving applies nothing to the members, and that deleting a session deletes its list. Runs on H2
 * ({@link GameDeviceListH2Test}) and MySQL ({@link GameDeviceListMySqlTest}).
 */
public abstract class GameDeviceListTest extends GameTestBase {

    private GameDeviceListService lists;

    @Override
    protected void createServices() {
        lists = new GameDeviceListService(transactions, mapper);
    }

    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    private ObjectNode list(Object... rows) {
        ObjectNode document = mapper.createObjectNode();
        ArrayNode devices = document.putArray("devices");
        for (Object row : rows) {
            devices.add((JsonNode) row);
        }
        return document;
    }

    private ObjectNode device(Object name, Object identifier, Object role, Object number) {
        ObjectNode row = mapper.createObjectNode();
        row.set("name", mapper.valueToTree(name));
        row.set("deviceIdentifier", mapper.valueToTree(identifier));
        row.set("role", mapper.valueToTree(role));
        row.set("roleNumber", mapper.valueToTree(number));
        return row;
    }

    private List<GameDeviceListService.RowError> rejected(long session, long expected, JsonNode document) {
        return assertThrows(GameDeviceListService.Rejected.class,
                () -> lists.update(session, expected, document)).errors();
    }

    private static List<String> codes(List<GameDeviceListService.RowError> errors) {
        return errors.stream().map(GameDeviceListService.RowError::code).toList();
    }

    @Test
    void aSessionWithoutAListHasAnEmptyOneAndSavingStoresItTrimmed() throws Exception {
        long session = session();
        GameDeviceListService.DeviceList empty = lists.get(session);
        assertEquals(0, empty.revision());
        assertNull(empty.updatedAt());
        assertEquals(json("{\"devices\": []}"), empty.document());

        long before = revision(session);
        clock.advance(Duration.ofMinutes(2));
        GameDeviceListService.DeviceList saved = lists.update(session, 0, list(
                device("  Anna ", " 4711 ", "player", 1),
                device("Ben", "4712", "player", 3),
                device("Sue", "s-1", "supporter", 1),
                device("Hugo", "h-1", "hunter", 1)));
        assertEquals(1, saved.revision());
        assertEquals(T0.plus(Duration.ofMinutes(2)), saved.updatedAt());
        JsonNode expected = json("{\"devices\": ["
                + "{\"name\": \"Anna\", \"deviceIdentifier\": \"4711\", \"role\": \"player\", \"roleNumber\": 1},"
                + "{\"name\": \"Ben\", \"deviceIdentifier\": \"4712\", \"role\": \"player\", \"roleNumber\": 3},"
                + "{\"name\": \"Sue\", \"deviceIdentifier\": \"s-1\", \"role\": \"supporter\", \"roleNumber\": 1},"
                + "{\"name\": \"Hugo\", \"deviceIdentifier\": \"h-1\", \"role\": \"hunter\", \"roleNumber\": 1}]}");
        assertEquals(expected, saved.document());
        GameDeviceListService.DeviceList read = lists.get(session);
        assertEquals(1, read.revision());
        assertEquals(expected, read.document());
        assertEquals(T0.plus(Duration.ofMinutes(2)), read.updatedAt());
        // The write advances the session's aggregate revision (the snapshot reads again).
        assertEquals(before + 1, revision(session));
        // Role numbers need not be contiguous: numbers of removed members are never reused.
        assertEquals(3, read.document().get("devices").get(1).get("roleNumber").asInt());
    }

    @Test
    void savingTheListAppliesNothingToTheMembers() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        lists.update(session, 0, list(device("Anna Renamed", "other-tracker", "player", 4),
                device("Hugo", "h-1", "hunter", 1)));
        GameMember stored = members.list(session).getFirst();
        assertEquals(anna.id(), stored.id());
        assertEquals("player", stored.role());
        assertEquals(anna.displayName(), stored.displayName());
        assertEquals(anna.trackerId(), stored.trackerId());
        assertEquals(1, members.list(session).size());
    }

    @Test
    void everyRuleIsCheckedAndEveryFailingRowListed() throws Exception {
        long session = session();
        var errors = rejected(session, 0, list(
                device("Anna", "1", "player", 1),
                device(" anna ", "2", "player", 2),
                device("  ", "3", "player", 3),
                device("Carl", " 1 ", "player", 4),
                device("Dora", "", "player", 5),
                device("Emil", "6", "Player", 6),
                device("Fred", "7", null, 7),
                device("Gina", "8", "hunter", 0),
                device("Hans", "9", "hunter", 1.5),
                device("Ida", "10", "hunter", "3"),
                device("Jon", "11", "hunter", 2147483648L),
                device("Kai", "12", "hunter", null),
                device("Lea", "13", "supporter", 1),
                device("Max", "14", "supporter", 1),
                device("Nora\tX", "15", "supporter", 2),
                device("O".repeat(129), "16", "supporter", 3),
                device(17, "17", "supporter", 4),
                device("Pia", 18, "supporter", 5)));
        assertEquals(List.of("duplicateName", "missingName", "duplicateDeviceIdentifier", "missingDeviceIdentifier",
                "invalidRole", "missingRole", "invalidRoleNumber", "invalidRoleNumber", "invalidRoleNumber",
                "invalidRoleNumber", "missingRoleNumber", "duplicateRoleNumber", "invalidName", "invalidName",
                "invalidName", "invalidDeviceIdentifier"), codes(errors));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17),
                errors.stream().map(GameDeviceListService.RowError::index).toList());
        assertEquals(List.of("name", "name", "deviceIdentifier", "deviceIdentifier", "role", "role", "roleNumber",
                "roleNumber", "roleNumber", "roleNumber", "roleNumber", "roleNumber", "name", "name", "name",
                "deviceIdentifier"), errors.stream().map(GameDeviceListService.RowError::field).toList());
        assertEquals("Another device is named 'anna' (row 1).", errors.get(0).message());
        assertEquals("Device identifier '1' is listed twice (row 1).", errors.get(2).message());
        assertEquals("Supporter 1 is listed twice (row 13).", errors.get(11).message());
        // Nothing was stored.
        assertEquals(0, lists.get(session).revision());

        // At least one player, also when every row is otherwise valid.
        errors = rejected(session, 0, list(device("Hugo", "h", "hunter", 1), device("Sue", "s", "supporter", 1)));
        assertEquals(List.of("noPlayer"), codes(errors));
        assertNull(errors.getFirst().index());
        assertEquals("The device list needs at least one player.", errors.getFirst().message());
        assertEquals(List.of("noPlayer"), codes(rejected(session, 0, list())));

        // The same number in different roles is fine.
        assertEquals(1, lists.update(session, 0, list(device("Anna", "a", "player", 1),
                device("Hugo", "h", "hunter", 1), device("Sue", "s", "supporter", 1))).revision());
    }

    @Test
    void aListHoldsAtMostAThousandDevices() throws Exception {
        long session = session();
        Object[] rows = new Object[GameDeviceListService.MAX_DEVICES + 1];
        for (int index = 0; index < rows.length; index++) {
            rows[index] = device("Player " + index, "id-" + index, "player", index + 1);
        }
        var errors = rejected(session, 0, list(rows));
        assertEquals(List.of("tooManyDevices"), codes(errors));
        Object[] fitting = java.util.Arrays.copyOf(rows, GameDeviceListService.MAX_DEVICES);
        assertEquals(1, lists.update(session, 0, list(fitting)).revision());
    }

    @Test
    void malformedDocumentsAreRefusedAsBadRequests() throws Exception {
        long session = session();
        for (String document : List.of("[]", "{}", "{\"devices\": {}}", "{\"devices\": [1]}",
                "{\"devices\": [], \"extra\": 1}",
                "{\"devices\": [{\"name\": \"Anna\", \"deviceIdentifier\": \"1\", \"role\": \"player\","
                        + " \"roleNumber\": 1, \"group\": \"player\"}]}")) {
            assertStatus(400, () -> lists.update(session, 0, json(document)));
        }
        assertStatus(400, () -> lists.update(session, 0, null));
        assertEquals(0, lists.get(session).revision());
    }

    @Test
    void writesNameTheirRevisionAndARetryOfTheSavedWriteAnswersTheStoredList() throws Exception {
        long session = session();
        JsonNode first = list(device("Anna", "a", "player", 1));
        assertEquals(1, lists.update(session, 0, first).revision());
        // The same write again (its answer was lost): the stored list, no new revision.
        long aggregate = revision(session);
        GameDeviceListService.DeviceList retried = lists.update(session, 0, list(device(" Anna", "a ", "player", 1)));
        assertEquals(1, retried.revision());
        assertEquals(aggregate, revision(session));
        // Another write based on the old revision, or a revision from the future: 409.
        assertStatus(409, () -> lists.update(session, 0, list(device("Ben", "b", "player", 1))));
        assertStatus(409, () -> lists.update(session, 5, first));
        assertEquals(2, lists.update(session, 1, list(device("Ben", "b", "player", 1))).revision());
        assertEquals("Ben", lists.get(session).document().get("devices").get(0).get("name").asText());
        // Unknown sessions.
        assertStatus(404, () -> lists.get(session + 1000));
        assertStatus(404, () -> lists.update(session + 1000, 0, first));
    }

    @Test
    void deletingASessionDeletesItsList() throws Exception {
        long session = session();
        lists.update(session, 0, list(device("Anna", "a", "player", 1)));
        sessions.delete(session);
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_device_lists WHERE gamesessionid = ?", session));
        assertStatus(404, () -> lists.get(session));
    }

}
