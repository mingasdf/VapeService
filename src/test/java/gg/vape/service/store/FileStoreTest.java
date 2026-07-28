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
        JsonObject global = new JsonObject();
        global.addProperty("cache", true);
        global.addProperty("firstRun", false);
        first.saveGlobalSettings("0", global);

        JsonObject privateData = new JsonObject();
        JsonArray friends = new JsonArray();
        friends.add("Alice");
        JsonArray otherData = new JsonArray();
        otherData.add("setting");
        privateData.add("friends", friends);
        privateData.add("otherData", otherData);
        first.savePrivateUserData("0", privateData);

        FileStore reloaded = new FileStore(dataFile);
        assertTrue(reloaded.globalSettings("0").get("cache").getAsBoolean());
        assertEquals("Alice", reloaded.privateData("0").getAsJsonArray("friends").get(0).getAsString());
        assertEquals("setting", reloaded.privateData("0").getAsJsonArray("otherData").get(0).getAsString());
    }

    @Test
    void appliesAndPersistsPrivateProfileSyncPayload() throws Exception {
        Path dataFile = temporaryDirectory.resolve("state.json");
        FileStore first = new FileStore(dataFile);
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

        JsonObject createResult = first.savePrivateProfiles("0", createPayload);
        assertEquals(1, createResult.size());
        Map.Entry<String, com.google.gson.JsonElement> created = createResult.entrySet().iterator().next();
        String profileId = created.getKey();
        assertEquals(profileId, created.getValue().getAsJsonObject().get("profileId").getAsString());

        FileStore reloaded = new FileStore(dataFile);
        assertTrue(reloaded.privateData("0").getAsJsonObject("profiles").has(profileId));

        JsonObject deletePayload = new JsonObject();
        deletePayload.add("updatedProfiles", new JsonArray());
        JsonArray deletedProfiles = new JsonArray();
        deletedProfiles.add(profileId);
        deletePayload.add("deletedProfiles", deletedProfiles);
        assertFalse(reloaded.savePrivateProfiles("0", deletePayload).has(profileId));
    }
}
