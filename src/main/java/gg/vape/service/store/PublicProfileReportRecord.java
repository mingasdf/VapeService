package gg.vape.service.store;

import com.google.gson.JsonObject;

public final class PublicProfileReportRecord {
    public long reportId;
    public long profileId;
    public long userId;
    public String reason;
    public String details;
    public long createdDate;
    public boolean resolved = false;
    public Long resolvedBy;

    public PublicProfileReportRecord() {
        this.createdDate = System.currentTimeMillis();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("reportId", reportId);
        json.addProperty("profileId", profileId);
        json.addProperty("userId", userId);
        json.addProperty("reason", reason);
        json.addProperty("details", details);
        json.addProperty("createdDate", createdDate);
        json.addProperty("resolved", resolved);
        if (resolvedBy != null) {
            json.addProperty("resolvedBy", resolvedBy);
        }
        return json;
    }
}