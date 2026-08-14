package gg.vape.service.store;

import com.google.gson.JsonObject;

public final class PublicProfileReviewRecord {
    public long reviewId;
    public long profileId;
    public long userId;
    public String message;
    public boolean liked;
    public long createdDate;
    public long updatedDate;
    public long version = 1L;
    public boolean latest = true;
    public boolean read = false;
    public Long responseId;

    public PublicProfileReviewRecord() {
        this.createdDate = System.currentTimeMillis();
        this.updatedDate = System.currentTimeMillis();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("reviewId", reviewId);
        json.addProperty("profileId", profileId);
        json.addProperty("userId", userId);
        json.addProperty("message", message);
        json.addProperty("liked", liked);
        json.addProperty("createdDate", createdDate);
        json.addProperty("updatedDate", updatedDate);
        json.addProperty("version", version);
        json.addProperty("latest", latest);
        json.addProperty("read", read);
        if (responseId != null) {
            json.addProperty("responseId", responseId);
        }
        return json;
    }
}