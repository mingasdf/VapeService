package gg.vape.service.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsSettingsAndPrivateDataAcrossInstances() throws Exception {
        Path dataFile = temporaryDirectory.resolve("state.json");
        FileStore first = new FileStore(dataFile);
        String token = first.loginByUsername("Alice").token();
        JsonObject global = new JsonObject();
        global.addProperty("cache", true);
        global.addProperty("firstRun", false);
        first.saveGlobalSettings(token, global);

        JsonObject privateData = new JsonObject();
        JsonArray friends = new JsonArray();
        friends.add("Alice");
        JsonArray otherData = new JsonArray();
        otherData.add("setting");
        privateData.add("friends", friends);
        privateData.add("otherData", otherData);
        first.savePrivateUserData(token, privateData);

        FileStore reloaded = new FileStore(dataFile);
        assertTrue(reloaded.globalSettings(token).get("cache").getAsBoolean());
        assertEquals("Alice", reloaded.privateData(token).getAsJsonArray("friends").get(0).getAsString());
        assertEquals("setting", reloaded.privateData(token).getAsJsonArray("otherData").get(0).getAsString());
    }

    @Test
    void appliesAndPersistsPrivateProfileSyncPayload() throws Exception {
        Path dataFile = temporaryDirectory.resolve("state.json");
        FileStore first = new FileStore(dataFile);
        String token = first.loginByUsername("Profiles").token();
        JsonObject profile = new JsonObject();
        profile.addProperty("uuid", UUID.randomUUID().toString());
        profile.addProperty("name", "Combat");
        profile.addProperty("vapeVersion", "4.21");
        profile.add("data", new JsonObject());

        JsonObject createPayload = new JsonObject();
        JsonArray updatedProfiles = new JsonArray();
        updatedProfiles.add(profile);
        createPayload.add("updatedProfiles", updatedProfiles);
        createPayload.add("deletedProfiles", new JsonArray());

        JsonObject createResult = first.savePrivateProfiles(token, createPayload);
        assertEquals(1, createResult.size());
        Map.Entry<String, com.google.gson.JsonElement> created = createResult.entrySet().iterator().next();
        String profileId = created.getKey();
        assertEquals(profileId, created.getValue().getAsJsonObject().get("profileId").getAsString());

        FileStore reloaded = new FileStore(dataFile);
        assertTrue(reloaded.privateData(token).getAsJsonObject("profiles").has(profileId));

        JsonObject deletePayload = new JsonObject();
        deletePayload.add("updatedProfiles", new JsonArray());
        JsonArray deletedProfiles = new JsonArray();
        deletedProfiles.add(profileId);
        deletePayload.add("deletedProfiles", deletedProfiles);
        assertFalse(reloaded.savePrivateProfiles(token, deletePayload).has(profileId));
    }

    @Test
    void loaderLoginCreatesAndReusesUsernameWithoutDevelopmentAccount() throws Exception {
        FileStore store = new FileStore(temporaryDirectory.resolve("state.json"));
        assertTrue(store.account("0").isEmpty());

        FileStore.LoaderLoginResult first = store.loginByUsername("PlayerOne");
        FileStore.LoaderLoginResult second = store.loginByUsername("playerone");
        assertEquals(first.token(), second.token());
        assertEquals(first.account().userId, second.account().userId);
        assertTrue(first.account().registered);
    }
}
