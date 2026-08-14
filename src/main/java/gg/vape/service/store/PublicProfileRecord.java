package gg.vape.service.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class PublicProfileRecord {
    public long profileId;
    public long userId;
    public String name;
    public String description;
    public List<String> tags = new ArrayList<>();
    public JsonObject data;
    public String shareCode;
    public long version = 1L;
    public long likes = 0L;
    public long dislikes = 0L;
    public long downloads = 0L;
    public long creationDate;
    public long updatedDate;
    public boolean listedPublicly = true;
    public boolean shareCodeFriendsOnly = false;
    public boolean uploadAnonymously = false;
    public Long derivedFrom;
    public long unreadNotifications = 0L;

    public PublicProfileRecord() {
        this.creationDate = System.currentTimeMillis();
        this.updatedDate = System.currentTimeMillis();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", profileId);
        json.addProperty("userId", userId);
        json.addProperty("name", name);
        json.addProperty("description", description != null ? description : "");
        JsonArray tagsArray = new JsonArray();
        for (String tag : tags) {
            tagsArray.add(tag);
        }
        json.add("tags", tagsArray);
        if (data != null) {
            json.add("data", data.deepCopy());
        }
        json.addProperty("shareCode", shareCode);
        json.addProperty("version", version);
        json.addProperty("likes", likes);
        json.addProperty("dislikes", dislikes);
        json.addProperty("downloads", downloads);
        json.addProperty("creationDate", creationDate);
        json.addProperty("updatedDate", updatedDate);
        json.addProperty("listedPublicly", listedPublicly);
        json.addProperty("shareCodeFriendsOnly", shareCodeFriendsOnly);
        json.addProperty("uploadAnonymously", uploadAnonymously);
        if (derivedFrom != null) {
            json.addProperty("derivedFrom", derivedFrom);
        }
        json.addProperty("unreadNotifications", unreadNotifications);
        return json;
    }

    public static PublicProfileRecord fromJson(JsonObject json) {
        PublicProfileRecord record = new PublicProfileRecord();
        if (json.has("profileId")) record.profileId = json.get("profileId").getAsLong();
        if (json.has("userId")) record.userId = json.get("userId").getAsLong();
        if (json.has("name")) record.name = json.get("name").getAsString();
        if (json.has("description")) record.description = json.get("description").getAsString();
        if (json.has("tags")) {
            JsonArray tagsArray = json.getAsJsonArray("tags");
            for (JsonElement tag : tagsArray) {
                record.tags.add(tag.getAsString());
            }
        }
        if (json.has("data")) record.data = json.get("data").getAsJsonObject().deepCopy();
        if (json.has("shareCode")) record.shareCode = json.get("shareCode").getAsString();
        if (json.has("version")) record.version = json.get("version").getAsLong();
        if (json.has("likes")) record.likes = json.get("likes").getAsLong();
        if (json.has("dislikes")) record.dislikes = json.get("dislikes").getAsLong();
        if (json.has("downloads")) record.downloads = json.get("downloads").getAsLong();
        if (json.has("creationDate")) record.creationDate = json.get("creationDate").getAsLong();
        if (json.has("updatedDate")) record.updatedDate = json.get("updatedDate").getAsLong();
        if (json.has("listedPublicly")) record.listedPublicly = json.get("listedPublicly").getAsBoolean();
        if (json.has("shareCodeFriendsOnly")) record.shareCodeFriendsOnly = json.get("shareCodeFriendsOnly").getAsBoolean();
        if (json.has("uploadAnonymously")) record.uploadAnonymously = json.get("uploadAnonymously").getAsBoolean();
        if (json.has("derivedFrom")) record.derivedFrom = json.get("derivedFrom").getAsLong();
        if (json.has("unreadNotifications")) record.unreadNotifications = json.get("unreadNotifications").getAsLong();
        return record;
    }
}