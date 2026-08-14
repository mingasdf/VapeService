package gg.vape.service.store;

import com.google.gson.JsonObject;

public final class PublicProfileReviewResponseRecord {
    public long id;
    public long reviewId;
    public long userId;
    public String response;
    public long createdDate;
    public long updatedDate;

    public PublicProfileReviewResponseRecord() {
        this.createdDate = System.currentTimeMillis();
        this.updatedDate = System.currentTimeMillis();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("reviewId", reviewId);
        json.addProperty("userId", userId);
        json.addProperty("response", response);
        json.addProperty("createdDate", createdDate);
        json.addProperty("updatedDate", updatedDate);
        return json;
    }
}