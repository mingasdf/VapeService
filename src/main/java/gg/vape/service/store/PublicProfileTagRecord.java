package gg.vape.service.store;

import com.google.gson.JsonObject;

public final class PublicProfileTagRecord {
    public String tag;
    public long usageCount = 1L;

    public PublicProfileTagRecord(String tag) {
        this.tag = tag;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("tag", tag);
        json.addProperty("usageCount", usageCount);
        return json;
    }
}