package gg.vape.service.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileStore {
    private static final long CHALLENGE_TTL_MILLIS = 5 * 60 * 1000L;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final SecureRandom random = new SecureRandom();
    private final Path file;
    private ServiceState state;

    public FileStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.state = load();
        normalizeLoadedState();
        save();
    }

    public synchronized Optional<AccountRecord> account(String token) {
        return Optional.ofNullable(state.accountsByToken.get(token));
    }

    public synchronized Optional<AccountRecord> account(long userId) {
        return state.accountsByToken.values().stream()
                .filter(account -> account.userId == userId)
                .findFirst();
    }

    public synchronized Optional<AccountRecord> accountByName(String username) {
        return state.accountsByToken.values().stream()
                .filter(account -> account.username.equalsIgnoreCase(username))
                .findFirst();
    }

    public synchronized LoaderLoginResult loginByUsername(String username) throws IOException {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty() || normalized.length() > 16) {
            throw new IllegalArgumentException("Username must contain 1 to 16 characters");
        }
        for (Map.Entry<String, AccountRecord> entry : state.accountsByToken.entrySet()) {
            if (entry.getValue().username.equalsIgnoreCase(normalized)) {
                return new LoaderLoginResult(entry.getKey(), entry.getValue());
            }
        }

        String token;
        do {
            token = randomToken();
        } while (state.accountsByToken.containsKey(token));
        AccountRecord account = new AccountRecord();
        account.userId = state.nextUserId++;
        account.username = normalized;
        account.accountCreation = AccountRecord.nowTimestamp();
        account.registered = true;
        state.accountsByToken.put(token, account);
        save();
        return new LoaderLoginResult(token, account);
    }

    public synchronized boolean areFriends(long firstUserId, long secondUserId) {
        return account(firstUserId).map(account -> account.onlineFriends.contains(secondUserId)).orElse(false);
    }

    public synchronized Set<Long> friendIds(long userId) {
        return account(userId).map(account -> Set.copyOf(account.onlineFriends)).orElse(Set.of());
    }

    public synchronized FriendRequestCreation createFriendRequest(long senderId, String targetName)
            throws IOException {
        AccountRecord sender = account(senderId).orElseThrow();
        AccountRecord target = accountByName(targetName).orElse(null);
        if (target == null) {
            return new FriendRequestCreation(4, null, null);
        }
        if (senderId == target.userId) {
            return new FriendRequestCreation(1, null, target);
        }
        if (sender.onlineFriends.contains(target.userId)) {
            return new FriendRequestCreation(2, null, target);
        }
        FriendRequestRecord existing = state.friendRequests.values().stream()
                .filter(request -> request.senderId == senderId && request.receiverId == target.userId)
                .findFirst().orElse(null);
        if (existing != null) {
            return new FriendRequestCreation(3, existing, target);
        }
        FriendRequestRecord request = new FriendRequestRecord();
        request.requestId = state.nextFriendRequestId++;
        request.senderId = senderId;
        request.receiverId = target.userId;
        state.friendRequests.put(request.requestId, request);
        save();
        return new FriendRequestCreation(0, request, target);
    }

    public synchronized FriendRequestUpdate updateFriendRequest(long actorId, long requestId, boolean accepted)
            throws IOException {
        FriendRequestRecord request = state.friendRequests.get(requestId);
        if (request == null || request.receiverId != actorId) {
            return new FriendRequestUpdate(2, null, null);
        }
        state.friendRequests.remove(requestId);
        AccountRecord other = account(request.senderId).orElse(null);
        if (accepted && other != null) {
            AccountRecord actor = account(actorId).orElseThrow();
            actor.onlineFriends.add(other.userId);
            other.onlineFriends.add(actor.userId);
        }
        save();
        return new FriendRequestUpdate(accepted ? 0 : 1, request, other);
    }

    public synchronized boolean deleteFriend(long actorId, long otherId) throws IOException {
        AccountRecord actor = account(actorId).orElseThrow();
        AccountRecord other = account(otherId).orElse(null);
        boolean removed = actor.onlineFriends.remove(otherId);
        if (other != null) {
            other.onlineFriends.remove(actorId);
        }
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized java.util.List<FriendRequestRecord> incomingRequests(long userId) {
        return state.friendRequests.values().stream()
                .filter(request -> request.receiverId == userId).toList();
    }

    public synchronized java.util.List<FriendRequestRecord> outgoingRequests(long userId) {
        return state.friendRequests.values().stream()
                .filter(request -> request.senderId == userId).toList();
    }

    public synchronized PartyRecord createParty(long leaderId) throws IOException {
        PartyRecord existing = partyFor(leaderId).orElse(null);
        if (existing != null) {
            return null;
        }
        PartyRecord party = new PartyRecord();
        party.partyId = state.nextPartyId++;
        party.leaderId = leaderId;
        party.members.add(leaderId);
        state.parties.put(party.partyId, party);
        save();
        return party;
    }

    public synchronized Optional<PartyRecord> partyFor(long userId) {
        return state.parties.values().stream()
                .filter(party -> party.members.contains(userId))
                .findFirst();
    }

    public synchronized PartyInviteResult inviteToParty(long actorId, long targetId) throws IOException {
        PartyRecord party = partyFor(actorId).orElse(null);
        if (party == null) {
            party = createParty(actorId);
        }
        if (party.members.contains(targetId) || party.invitedUsers.contains(targetId)) {
            return new PartyInviteResult(1, party);
        }
        if (party.invitedUsers.size() >= 20) {
            return new PartyInviteResult(2, party);
        }
        party.invitedUsers.add(targetId);
        save();
        return new PartyInviteResult(0, party);
    }

    public synchronized PartyInviteDecision decidePartyInvite(long actorId, long inviterId, boolean accepted)
            throws IOException {
        PartyRecord party = partyFor(inviterId).orElse(null);
        if (party == null || !party.invitedUsers.remove(actorId)) {
            return new PartyInviteDecision(3, null);
        }
        if (accepted) {
            if (party.members.size() >= 20) {
                return new PartyInviteDecision(2, party);
            }
            party.members.add(actorId);
        }
        save();
        return new PartyInviteDecision(accepted ? 0 : 1, party);
    }

    public synchronized boolean uninvite(long actorId, long targetId) throws IOException {
        PartyRecord party = partyFor(actorId).orElse(null);
        if (party == null || (party.leaderId != actorId && !party.openInvites)) {
            return false;
        }
        boolean removed = party.invitedUsers.remove(targetId);
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized PartyLeaveResult leaveParty(long userId) throws IOException {
        PartyRecord party = partyFor(userId).orElse(null);
        if (party == null) {
            return new PartyLeaveResult(false, null, -1L);
        }
        party.members.remove(userId);
        long newLeaderId = party.leaderId;
        if (party.members.isEmpty()) {
            state.parties.remove(party.partyId);
            newLeaderId = -1L;
        } else if (party.leaderId == userId) {
            party.leaderId = party.members.iterator().next();
            newLeaderId = party.leaderId;
        }
        save();
        return new PartyLeaveResult(true, party, newLeaderId);
    }

    public synchronized PartyRecord deleteParty(long actorId) throws IOException {
        PartyRecord party = partyFor(actorId).orElse(null);
        if (party == null || party.leaderId != actorId) {
            return null;
        }
        state.parties.remove(party.partyId);
        save();
        return party;
    }

    public synchronized boolean kickPartyMember(long actorId, long targetId) throws IOException {
        PartyRecord party = partyFor(actorId).orElse(null);
        if (party == null || party.leaderId != actorId || targetId == actorId) {
            return false;
        }
        boolean removed = party.members.remove(targetId);
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized PartyRecord promotePartyMember(long actorId, long targetId) throws IOException {
        PartyRecord party = partyFor(actorId).orElse(null);
        if (party == null || party.leaderId != actorId || !party.members.contains(targetId)) {
            return null;
        }
        party.leaderId = targetId;
        save();
        return party;
    }

    public synchronized PartyRecord updatePartyOpenInvites(long actorId, boolean openInvites) throws IOException {
        PartyRecord party = partyFor(actorId).orElse(null);
        if (party == null || party.leaderId != actorId) {
            return null;
        }
        party.openInvites = openInvites;
        save();
        return party;
    }

    public synchronized void updatePresence(long userId, int presence) throws IOException {
        account(userId).orElseThrow().presence = presence;
        save();
    }

    public synchronized void updateServerAddress(long userId, String serverAddress) throws IOException {
        account(userId).orElseThrow().serverAddress = serverAddress;
        save();
    }

    public synchronized void updateShowUsername(long userId, boolean showUsername) throws IOException {
        account(userId).orElseThrow().showUsername = showUsername;
        save();
    }

    public synchronized void updateActiveProfile(long userId, long profileId) throws IOException {
        account(userId).orElseThrow().activeProfileId = profileId;
        save();
    }

    public synchronized AuthChallengeRecord createChallenge(String edition, String hwid) throws IOException {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        AuthChallengeRecord challenge = new AuthChallengeRecord();
        challenge.challenge = HexFormat.of().formatHex(bytes);
        challenge.edition = edition;
        challenge.hwid = hwid;
        challenge.expiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MILLIS;
        state.challenges.put(challenge.challenge, challenge);
        save();
        return challenge;
    }

    public synchronized boolean approveChallenge(String challengeValue) throws IOException {
        AuthChallengeRecord challenge = state.challenges.get(challengeValue);
        if (challenge == null || challenge.expiresAt < System.currentTimeMillis()) {
            return false;
        }
        if (!challenge.approved) {
            challenge.approved = true;
            challenge.accessToken = randomToken();
            AccountRecord account = new AccountRecord();
            account.userId = state.nextUserId++;
            account.username = "User" + account.userId;
            account.accountCreation = AccountRecord.nowTimestamp();
            state.accountsByToken.put(challenge.accessToken, account);
            save();
        }
        return true;
    }

    public synchronized Optional<AuthChallengeRecord> challenge(String challengeValue) {
        return Optional.ofNullable(state.challenges.get(challengeValue));
    }

    public synchronized void register(String token, String username) throws IOException {
        AccountRecord account = requireAccount(token);
        account.registered = true;
        if (username != null && !username.isBlank()) {
            account.username = username.substring(0, Math.min(username.length(), 16));
        }
        save();
    }

    public synchronized void updateMinecraftIdentity(String token, UUID uuid, String username) throws IOException {
        AccountRecord account = requireAccount(token);
        account.minecraftUuid = uuid.toString();
        account.minecraftUsername = username;
        save();
    }

    public synchronized boolean updateDisplayName(String token, String displayName) throws IOException {
        for (Map.Entry<String, AccountRecord> entry : state.accountsByToken.entrySet()) {
            if (!entry.getKey().equals(token) && entry.getValue().username.equalsIgnoreCase(displayName)) {
                return false;
            }
        }
        requireAccount(token).username = displayName;
        save();
        return true;
    }

    public synchronized JsonObject globalSettings(String token) {
        return requireAccount(token).globalSettings.deepCopy();
    }

    public synchronized void saveGlobalSettings(String token, JsonObject settings) throws IOException {
        requireAccount(token).globalSettings = settings.deepCopy();
        save();
    }

    public synchronized JsonObject onlineSettings(String token) {
        return requireAccount(token).onlineSettings.deepCopy();
    }

    public synchronized void saveOnlineSettings(String token, JsonObject settings) throws IOException {
        requireAccount(token).onlineSettings = settings.deepCopy();
        save();
    }

    public synchronized JsonObject privateData(String token) {
        AccountRecord account = requireAccount(token);
        JsonObject data = new JsonObject();
        data.add("friends", account.localFriends.deepCopy());
        JsonObject profiles = new JsonObject();
        account.privateProfiles.forEach((key, value) -> profiles.add(key, value.deepCopy()));
        data.add("profiles", profiles);
        data.add("publicProfiles", new JsonObject());
        data.add("otherData", account.otherData.deepCopy());
        return data;
    }

    public synchronized void savePrivateUserData(String token, JsonElement userData) throws IOException {
        if (!userData.isJsonObject()) {
            throw new IllegalArgumentException("Private user data must be a JSON object");
        }
        JsonObject source = userData.getAsJsonObject();
        AccountRecord account = requireAccount(token);
        if (source.has("friends") && source.get("friends").isJsonArray()) {
            account.localFriends = source.getAsJsonArray("friends").deepCopy();
        }
        JsonElement otherData = source.has("otherData") ? source.get("otherData") : source.get("otherdata");
        if (otherData != null && otherData.isJsonArray()) {
            account.otherData = otherData.getAsJsonArray().deepCopy();
        }
        save();
    }

    public synchronized JsonObject savePrivateProfiles(String token, JsonObject profiles) throws IOException {
        AccountRecord account = requireAccount(token);
        JsonArray deletedProfiles = profiles.getAsJsonArray("deletedProfiles");
        if (deletedProfiles != null) {
            for (JsonElement deletedProfile : deletedProfiles) {
                if (deletedProfile.isJsonPrimitive()) {
                    account.privateProfiles.remove(deletedProfile.getAsString());
                }
            }
        }

        JsonArray updatedProfiles = profiles.getAsJsonArray("updatedProfiles");
        if (updatedProfiles != null) {
            for (JsonElement updatedProfile : updatedProfiles) {
                if (!updatedProfile.isJsonObject()) {
                    continue;
                }
                JsonObject storedProfile = updatedProfile.getAsJsonObject().deepCopy();
                String profileId = storedProfile.has("profileId")
                        ? storedProfile.get("profileId").getAsString()
                        : UUID.randomUUID().toString();
                UUID.fromString(profileId);
                storedProfile.addProperty("profileId", profileId);
                account.privateProfiles.put(profileId, storedProfile);
            }
        }
        save();
        JsonObject result = new JsonObject();
        account.privateProfiles.forEach((key, value) -> result.add(key, value.deepCopy()));
        return result;
    }

    public synchronized String reserveProfileId(String token) throws IOException {
        requireAccount(token);
        String id = UUID.randomUUID().toString();
        save();
        return id;
    }

    public synchronized JsonObject publicProfiles() {
        JsonObject profiles = new JsonObject();
        state.publicProfiles.forEach((key, value) -> profiles.add(key, value.deepCopy()));
        return profiles;
    }

    public synchronized JsonObject savePublicProfile(String token, JsonObject profile) throws IOException {
        AccountRecord account = requireAccount(token);
        JsonObject stored = profile.deepCopy();
        String id = stored.has("id") ? stored.get("id").getAsString()
                : Long.toString(state.nextPublicProfileId++);
        stored.addProperty("id", id);
        stored.addProperty("userId", account.userId);
        state.publicProfiles.put(id, stored);
        save();
        return stored.deepCopy();
    }

    public synchronized Optional<JsonObject> publicProfile(String id) {
        JsonObject profile = state.publicProfiles.get(id);
        return profile == null ? Optional.empty() : Optional.of(profile.deepCopy());
    }

    public synchronized boolean deletePublicProfile(String token, String id) throws IOException {
        AccountRecord account = requireAccount(token);
        JsonObject profile = state.publicProfiles.get(id);
        if (profile == null || !profile.has("userId") || profile.get("userId").getAsLong() != account.userId) {
            return false;
        }
        state.publicProfiles.remove(id);
        save();
        return true;
    }

    // ==================== 公共配置核心功能 ====================

    public synchronized PublicProfileRecord createPublicProfile(String token, JsonObject request) throws IOException {
        AccountRecord account = requireAccount(token);
        
        String name = request.has("name") ? request.get("name").getAsString() : "Untitled";
        if (name.length() > 48) {
            name = name.substring(0, 47);
        }
        
        PublicProfileRecord record = new PublicProfileRecord();
        record.profileId = state.nextPublicProfileId++;
        record.userId = account.userId;
        record.name = name;
        record.description = request.has("description") ? request.get("description").getAsString() : "";
        
        if (request.has("tags")) {
            JsonArray tagsArray = request.getAsJsonArray("tags");
            for (JsonElement tag : tagsArray) {
                String normalized = tag.getAsString().trim().toLowerCase();
                if (!normalized.isEmpty() && normalized.length() <= 16) {
                    record.tags.add(normalized);
                    updateTagUsage(normalized, 1);
                }
            }
        }
        
        if (request.has("profileData") || request.has("data")) {
            JsonElement dataElement = request.has("profileData") ? request.get("profileData") : request.get("data");
            if (dataElement.isJsonObject()) {
                record.data = dataElement.getAsJsonObject().deepCopy();
            }
        }
        
        record.shareCode = generateShareCode();
        record.listedPublicly = request.has("listed") ? request.get("listed").getAsBoolean() : true;
        if (request.has("listedPublicly")) {
            record.listedPublicly = request.get("listedPublicly").getAsBoolean();
        }
        record.shareCodeFriendsOnly = request.has("shareCodeFriendsOnly") ? request.get("shareCodeFriendsOnly").getAsBoolean() : false;
        record.uploadAnonymously = request.has("anonymous") ? request.get("anonymous").getAsBoolean() : false;
        if (request.has("uploadAnonymously")) {
            record.uploadAnonymously = request.get("uploadAnonymously").getAsBoolean();
        }
        
        if (request.has("derivedFrom")) {
            String derivedFromStr = request.get("derivedFrom").getAsString();
            if (derivedFromStr != null && !derivedFromStr.isEmpty()) {
                try {
                    record.derivedFrom = Long.parseLong(derivedFromStr);
                } catch (NumberFormatException ignored) {}
            }
        }
        
        state.profilesById.put(record.profileId, record);
        state.profilesByShareCode.put(record.shareCode, record.profileId);
        save();
        return record;
    }

    public synchronized PublicProfileRecord updatePublicProfile(String token, long profileId, JsonObject request) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileRecord existing = state.profilesById.get(profileId);
        if (existing == null || existing.userId != account.userId) {
            throw new IllegalArgumentException("Profile not found or not owned by user");
        }
        
        if (request.has("name")) {
            String name = request.get("name").getAsString();
            if (name.length() > 48) name = name.substring(0, 47);
            existing.name = name;
        }
        
        if (request.has("description")) {
            existing.description = request.get("description").getAsString();
        }
        
        if (request.has("tags")) {
            for (String oldTag : existing.tags) {
                updateTagUsage(oldTag, -1);
            }
            existing.tags.clear();
            JsonArray tagsArray = request.getAsJsonArray("tags");
            for (JsonElement tag : tagsArray) {
                String normalized = tag.getAsString().trim().toLowerCase();
                if (!normalized.isEmpty() && normalized.length() <= 16) {
                    existing.tags.add(normalized);
                    updateTagUsage(normalized, 1);
                }
            }
        }
        
        if (request.has("profileData") || request.has("data")) {
            JsonElement dataElement = request.has("profileData") ? request.get("profileData") : request.get("data");
            if (dataElement.isJsonObject()) {
                existing.data = dataElement.getAsJsonObject().deepCopy();
            }
        }
        
        if (request.has("listed")) {
            existing.listedPublicly = request.get("listed").getAsBoolean();
        }
        if (request.has("listedPublicly")) {
            existing.listedPublicly = request.get("listedPublicly").getAsBoolean();
        }
        if (request.has("shareCodeFriendsOnly")) {
            existing.shareCodeFriendsOnly = request.get("shareCodeFriendsOnly").getAsBoolean();
        }
        if (request.has("anonymous")) {
            existing.uploadAnonymously = request.get("anonymous").getAsBoolean();
        }
        if (request.has("uploadAnonymously")) {
            existing.uploadAnonymously = request.get("uploadAnonymously").getAsBoolean();
        }
        
        existing.version++;
        existing.updatedDate = System.currentTimeMillis();
        save();
        return existing;
    }

    public synchronized Optional<PublicProfileRecord> getPublicProfile(long profileId) {
        return Optional.ofNullable(state.profilesById.get(profileId));
    }

    public synchronized Optional<PublicProfileRecord> getPublicProfileByShareCode(String shareCode) {
        Long profileId = state.profilesByShareCode.get(shareCode.toUpperCase());
        if (profileId != null) {
            return Optional.ofNullable(state.profilesById.get(profileId));
        }
        return Optional.empty();
    }

    public synchronized boolean deletePublicProfileById(String token, long profileId) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileRecord existing = state.profilesById.get(profileId);
        if (existing == null || existing.userId != account.userId) {
            return false;
        }
        
        for (String tag : existing.tags) {
            updateTagUsage(tag, -1);
        }
        
        List<Long> reviewIdsToDelete = new ArrayList<>();
        for (PublicProfileReviewRecord review : state.reviewsById.values()) {
            if (review.profileId == profileId) {
                reviewIdsToDelete.add(review.reviewId);
            }
        }
        for (Long reviewId : reviewIdsToDelete) {
            state.reviewsById.remove(reviewId);
        }
        
        state.profilesById.remove(profileId);
        if (existing.shareCode != null) {
            state.profilesByShareCode.remove(existing.shareCode);
        }
        save();
        return true;
    }

    public synchronized PublicProfileRecord incrementDownloads(long profileId) throws IOException {
        PublicProfileRecord record = state.profilesById.get(profileId);
        if (record == null) {
            throw new IllegalArgumentException("Profile not found");
        }
        record.downloads++;
        save();
        return record;
    }

    public synchronized JsonObject listPublicProfiles(long userId, int page, int size, String sortBy, String searchQuery, List<String> tags) {
        Stream<PublicProfileRecord> stream = state.profilesById.values().stream()
            .filter(p -> p.listedPublicly);
        
        if (searchQuery != null && !searchQuery.isEmpty()) {
            String query = searchQuery.toLowerCase();
            stream = stream.filter(p -> 
                p.name.toLowerCase().contains(query) || 
                (p.description != null && p.description.toLowerCase().contains(query))
            );
        }
        
        if (tags != null && !tags.isEmpty()) {
            stream = stream.filter(p -> p.tags.stream().anyMatch(tags::contains));
        }
        
        if (sortBy != null) {
            switch (sortBy) {
                case "downloads":
                case "downloaded":
                    stream = stream.sorted(Comparator.comparingLong((PublicProfileRecord p) -> p.downloads).reversed());
                    break;
                case "createdDate":
                case "newest":
                    stream = stream.sorted(Comparator.comparingLong((PublicProfileRecord p) -> p.creationDate).reversed());
                    break;
                case "rating":
                case "rated":
                    stream = stream.sorted((a, b) -> {
                        double ratingA = (double)(a.likes + 1) / (a.likes + a.dislikes + 2);
                        double ratingB = (double)(b.likes + 1) / (b.likes + b.dislikes + 2);
                        return Double.compare(ratingB, ratingA);
                    });
                    break;
                case "updatedDate":
                default:
                    stream = stream.sorted(Comparator.comparingLong((PublicProfileRecord p) -> p.updatedDate).reversed());
                    break;
            }
        }
        
        List<PublicProfileRecord> all = stream.collect(Collectors.toList());
        int total = all.size();
        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);
        List<PublicProfileRecord> paged = all.subList(start, end);
        
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        for (PublicProfileRecord record : paged) {
            JsonObject summary = record.toJson();
            account(record.userId).ifPresent(acc -> {
                if (!record.uploadAnonymously) {
                    summary.add("owner", acc.accountJson());
                }
            });
            long currentUserId = userId;
            if (currentUserId > 0) {
                boolean hasReviewed = state.reviewsById.values().stream()
                    .anyMatch(r -> r.profileId == record.profileId && r.userId == currentUserId);
                summary.addProperty("hasReviewed", hasReviewed);
            }
            content.add(summary);
        }
        
        result.add("content", content);
        result.addProperty("last", end >= total);
        result.addProperty("totalPages", (int)Math.ceil((double)total / size));
        result.addProperty("totalElements", total);
        result.addProperty("size", size);
        result.addProperty("numberOfElements", paged.size());
        return result;
    }

    // ==================== 评价功能 ====================

    public synchronized PublicProfileReviewRecord createReview(String token, long profileId, String message, boolean liked) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileRecord profile = state.profilesById.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }
        
        for (PublicProfileReviewRecord existing : state.reviewsById.values()) {
            if (existing.profileId == profileId && existing.userId == account.userId) {
                throw new IllegalArgumentException("Already reviewed this profile");
            }
        }
        
        for (PublicProfileReviewRecord existing : state.reviewsById.values()) {
            if (existing.profileId == profileId && existing.latest) {
                existing.latest = false;
            }
        }
        
        PublicProfileReviewRecord review = new PublicProfileReviewRecord();
        review.reviewId = state.nextReviewId++;
        review.profileId = profileId;
        review.userId = account.userId;
        review.message = message;
        review.liked = liked;
        
        if (liked) {
            profile.likes++;
        } else {
            profile.dislikes++;
        }
        
        profile.unreadNotifications++;
        profile.updatedDate = System.currentTimeMillis();
        
        state.reviewsById.put(review.reviewId, review);
        save();
        return review;
    }

    public synchronized PublicProfileReviewRecord updateReview(String token, long reviewId, String message) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileReviewRecord review = state.reviewsById.get(reviewId);
        if (review == null || review.userId != account.userId) {
            throw new IllegalArgumentException("Review not found or not owned by user");
        }
        
        for (PublicProfileReviewRecord existing : state.reviewsById.values()) {
            if (existing.profileId == review.profileId && existing.latest) {
                existing.latest = false;
            }
        }
        
        review.message = message;
        review.version++;
        review.updatedDate = System.currentTimeMillis();
        review.latest = true;
        
        PublicProfileRecord profile = state.profilesById.get(review.profileId);
        if (profile != null) {
            profile.updatedDate = System.currentTimeMillis();
        }
        
        save();
        return review;
    }

    public synchronized boolean deleteReview(String token, long reviewId) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileReviewRecord review = state.reviewsById.get(reviewId);
        if (review == null || review.userId != account.userId) {
            return false;
        }
        
        PublicProfileRecord profile = state.profilesById.get(review.profileId);
        if (profile != null) {
            if (review.liked) {
                profile.likes = Math.max(0, profile.likes - 1);
            } else {
                profile.dislikes = Math.max(0, profile.dislikes - 1);
            }
        }
        
        state.reviewsById.remove(reviewId);
        save();
        return true;
    }

    public synchronized PublicProfileReviewRecord getReview(long reviewId) {
        return state.reviewsById.get(reviewId);
    }

    public synchronized List<PublicProfileReviewRecord> getReviewsForProfile(long profileId) {
        return state.reviewsById.values().stream()
            .filter(r -> r.profileId == profileId)
            .sorted(Comparator.comparingLong((PublicProfileReviewRecord r) -> r.createdDate).reversed())
            .collect(Collectors.toList());
    }

    public synchronized Optional<PublicProfileReviewRecord> getReviewByUserAndProfile(long userId, long profileId) {
        return state.reviewsById.values().stream()
            .filter(r -> r.profileId == profileId && r.userId == userId)
            .findFirst();
    }

    public synchronized void markReviewRead(long reviewId) throws IOException {
        PublicProfileReviewRecord review = state.reviewsById.get(reviewId);
        if (review != null) {
            review.read = true;
            save();
        }
    }

    public synchronized long getUnreadNotificationCount(long userId) {
        long count = 0;
        for (PublicProfileRecord profile : state.profilesById.values()) {
            if (profile.userId == userId) {
                count += profile.unreadNotifications;
            }
        }
        return count;
    }

    public synchronized void clearUnreadNotifications(long userId) throws IOException {
        for (PublicProfileRecord profile : state.profilesById.values()) {
            if (profile.userId == userId) {
                profile.unreadNotifications = 0L;
            }
        }
        save();
    }

    // ==================== 评价回复功能 ====================

    public synchronized PublicProfileReviewResponseRecord createReviewResponse(String token, long reviewId, String response) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileReviewRecord review = state.reviewsById.get(reviewId);
        if (review == null) {
            throw new IllegalArgumentException("Review not found");
        }
        
        PublicProfileRecord profile = state.profilesById.get(review.profileId);
        if (profile == null || profile.userId != account.userId) {
            throw new IllegalArgumentException("Not authorized to respond to this review");
        }
        
        PublicProfileReviewResponseRecord responseRecord = new PublicProfileReviewResponseRecord();
        responseRecord.id = state.nextReviewResponseId++;
        responseRecord.reviewId = reviewId;
        responseRecord.userId = account.userId;
        responseRecord.response = response;
        
        review.responseId = responseRecord.id;
        review.updatedDate = System.currentTimeMillis();
        
        state.reviewResponsesById.put(responseRecord.id, responseRecord);
        save();
        return responseRecord;
    }

    public synchronized Optional<PublicProfileReviewResponseRecord> getReviewResponse(long responseId) {
        return Optional.ofNullable(state.reviewResponsesById.get(responseId));
    }

    public synchronized boolean deleteReviewResponse(String token, long responseId) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileReviewResponseRecord response = state.reviewResponsesById.get(responseId);
        if (response == null || response.userId != account.userId) {
            return false;
        }
        
        state.reviewResponsesById.remove(responseId);
        
        for (PublicProfileReviewRecord review : state.reviewsById.values()) {
            if (review.responseId != null && review.responseId == responseId) {
                review.responseId = null;
                break;
            }
        }
        
        save();
        return true;
    }

    // ==================== 举报功能 ====================

    public synchronized PublicProfileReportRecord createReport(String token, long profileId, String reason, String details) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileRecord profile = state.profilesById.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile not found");
        }
        
        for (PublicProfileReportRecord existing : state.reportsById.values()) {
            if (existing.profileId == profileId && existing.userId == account.userId && !existing.resolved) {
                throw new IllegalArgumentException("Already reported this profile");
            }
        }
        
        PublicProfileReportRecord report = new PublicProfileReportRecord();
        report.reportId = state.nextReportId++;
        report.profileId = profileId;
        report.userId = account.userId;
        report.reason = reason;
        report.details = details;
        
        state.reportsById.put(report.reportId, report);
        save();
        return report;
    }

    public synchronized List<PublicProfileReportRecord> getReportsForProfile(long profileId) {
        return state.reportsById.values().stream()
            .filter(r -> r.profileId == profileId)
            .collect(Collectors.toList());
    }

    public synchronized boolean resolveReport(String token, long reportId) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileReportRecord report = state.reportsById.get(reportId);
        if (report == null) {
            return false;
        }
        
        report.resolved = true;
        report.resolvedBy = account.userId;
        save();
        return true;
    }

    // ==================== 标签功能 ====================

    public synchronized JsonObject getPopularTags(int limit) {
        JsonArray tagsArray = new JsonArray();
        List<PublicProfileTagRecord> tags = state.tagsByLowercase.values().stream()
            .sorted(Comparator.comparingLong((PublicProfileTagRecord t) -> t.usageCount).reversed())
            .limit(limit > 0 ? limit : 20)
            .collect(Collectors.toList());
        
        for (PublicProfileTagRecord tag : tags) {
            tagsArray.add(tag.toJson());
        }
        
        JsonObject result = new JsonObject();
        result.add("tags", tagsArray);
        return result;
    }

    private void updateTagUsage(String tag, int delta) {
        String normalized = tag.toLowerCase();
        PublicProfileTagRecord tagRecord = state.tagsByLowercase.get(normalized);
        if (tagRecord == null) {
            if (delta > 0) {
                state.tagsByLowercase.put(normalized, new PublicProfileTagRecord(tag));
            }
        } else {
            tagRecord.usageCount += delta;
            if (tagRecord.usageCount <= 0) {
                state.tagsByLowercase.remove(normalized);
            }
        }
    }

    // ==================== 分享码功能 ====================

    public synchronized String regenerateShareCode(String token, long profileId) throws IOException {
        AccountRecord account = requireAccount(token);
        PublicProfileRecord profile = state.profilesById.get(profileId);
        if (profile == null || profile.userId != account.userId) {
            throw new IllegalArgumentException("Profile not found or not owned by user");
        }
        
        if (profile.shareCode != null) {
            state.profilesByShareCode.remove(profile.shareCode);
        }
        
        String newCode = generateShareCode();
        profile.shareCode = newCode;
        state.profilesByShareCode.put(newCode, profileId);
        save();
        return newCode;
    }

    private String generateShareCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        String newCode = code.toString();
        while (state.profilesByShareCode.containsKey(newCode)) {
            code.setLength(0);
            for (int i = 0; i < 8; i++) {
                code.append(chars.charAt(random.nextInt(chars.length())));
            }
            newCode = code.toString();
        }
        return newCode;
    }

    // ==================== 统计功能 ====================

    public synchronized JsonObject getProfileStatistics(long profileId) {
        PublicProfileRecord profile = state.profilesById.get(profileId);
        if (profile == null) {
            return new JsonObject();
        }
        
        JsonObject stats = new JsonObject();
        stats.addProperty("profileId", profileId);
        stats.addProperty("likes", profile.likes);
        stats.addProperty("dislikes", profile.dislikes);
        stats.addProperty("downloads", profile.downloads);
        
        long reviewCount = state.reviewsById.values().stream()
            .filter(r -> r.profileId == profileId)
            .count();
        stats.addProperty("reviewCount", reviewCount);
        
        long reportCount = state.reportsById.values().stream()
            .filter(r -> r.profileId == profileId && !r.resolved)
            .count();
        stats.addProperty("openReportCount", reportCount);
        
        return stats;
    }

    public synchronized JsonObject getProfileWithFullDetails(long profileId, long viewerUserId) {
        PublicProfileRecord profile = state.profilesById.get(profileId);
        if (profile == null || !profile.listedPublicly) {
            return null;
        }
        
        JsonObject full = profile.toJson();
        
        account(profile.userId).ifPresent(acc -> {
            if (!profile.uploadAnonymously) {
                full.add("owner", acc.accountJson());
            }
        });
        
        JsonArray reviewsArray = new JsonArray();
        for (PublicProfileReviewRecord review : getReviewsForProfile(profileId)) {
            JsonObject reviewJson = review.toJson();
            account(review.userId).ifPresent(acc -> {
                reviewJson.add("commenter", acc.accountJson());
            });
            if (review.responseId != null) {
                getReviewResponse(review.responseId).ifPresent(response -> {
                    reviewJson.add("response", response.toJson());
                });
            }
            reviewsArray.add(reviewJson);
        }
        full.add("reviews", reviewsArray);
        
        getReviewByUserAndProfile(viewerUserId, profileId).ifPresent(review -> {
            full.add("viewerReview", review.toJson());
        });
        
        JsonObject shareInfo = new JsonObject();
        shareInfo.addProperty("shareCode", profile.shareCode);
        shareInfo.addProperty("listedPublicly", profile.listedPublicly);
        shareInfo.addProperty("shareCodeFriendsOnly", profile.shareCodeFriendsOnly);
        shareInfo.addProperty("uploadAnonymously", profile.uploadAnonymously);
        if (profile.derivedFrom != null) {
            shareInfo.addProperty("derivedFrom", profile.derivedFrom);
        }
        full.add("shareInfo", shareInfo);
        
        return full;
    }

    public synchronized JsonObject getProfileForUpdate(long profileId) {
        PublicProfileRecord profile = state.profilesById.get(profileId);
        if (profile == null || !profile.listedPublicly) {
            return null;
        }
        
        JsonObject result = new JsonObject();
        result.addProperty("profileId", profile.profileId);
        result.addProperty("name", profile.name);
        result.addProperty("version", profile.version);
        result.addProperty("updatedDate", profile.updatedDate);
        if (profile.data != null) {
            result.add("data", profile.data.deepCopy());
        }
        
        return result;
    }

    // ==================== 分页获取评价 ====================

    public synchronized JsonObject getReviewPage(long profileId, long page) {
        List<PublicProfileReviewRecord> reviews = getReviewsForProfile(profileId);
        int pageSize = 10;
        int start = (int)(page * pageSize);
        int end = Math.min(start + pageSize, reviews.size());
        
        if (start >= reviews.size()) {
            JsonObject emptyResult = new JsonObject();
            emptyResult.add("content", new JsonArray());
            emptyResult.addProperty("last", true);
            emptyResult.addProperty("totalPages", (int)Math.ceil((double)reviews.size() / pageSize));
            emptyResult.addProperty("totalElements", reviews.size());
            emptyResult.addProperty("size", pageSize);
            emptyResult.addProperty("numberOfElements", 0);
            return emptyResult;
        }
        
        List<PublicProfileReviewRecord> paged = reviews.subList(start, end);
        
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        for (PublicProfileReviewRecord review : paged) {
            JsonObject reviewJson = review.toJson();
            account(review.userId).ifPresent(acc -> {
                reviewJson.add("commenter", acc.accountJson());
            });
            if (review.responseId != null) {
                getReviewResponse(review.responseId).ifPresent(response -> {
                    reviewJson.add("response", response.toJson());
                });
            }
            content.add(reviewJson);
        }
        result.add("content", content);
        result.addProperty("last", end >= reviews.size());
        result.addProperty("totalPages", (int)Math.ceil((double)reviews.size() / pageSize));
        result.addProperty("totalElements", reviews.size());
        result.addProperty("size", pageSize);
        result.addProperty("numberOfElements", paged.size());
        return result;
    }

    private AccountRecord requireAccount(String token) {
        AccountRecord account = state.accountsByToken.get(token);
        if (account == null) {
            throw new IllegalArgumentException("Invalid access token");
        }
        return account;
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private ServiceState load() throws IOException {
        if (!Files.exists(file)) {
            return new ServiceState();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        ServiceState loaded = gson.fromJson(json, ServiceState.class);
        return loaded == null ? new ServiceState() : loaded;
    }

    private void normalizeLoadedState() {
        if (state.accountsByToken == null) {
            state.accountsByToken = new LinkedHashMap<>();
        }
        if (state.challenges == null) {
            state.challenges = new LinkedHashMap<>();
        }
        if (state.publicProfiles == null) {
            state.publicProfiles = new LinkedHashMap<>();
        }
        if (state.friendRequests == null) {
            state.friendRequests = new LinkedHashMap<>();
        }
        if (state.parties == null) {
            state.parties = new LinkedHashMap<>();
        }
        if (state.profilesById == null) {
            state.profilesById = new LinkedHashMap<>();
        }
        if (state.profilesByShareCode == null) {
            state.profilesByShareCode = new LinkedHashMap<>();
        }
        if (state.reviewsById == null) {
            state.reviewsById = new LinkedHashMap<>();
        }
        if (state.reviewResponsesById == null) {
            state.reviewResponsesById = new LinkedHashMap<>();
        }
        if (state.reportsById == null) {
            state.reportsById = new LinkedHashMap<>();
        }
        if (state.tagsByLowercase == null) {
            state.tagsByLowercase = new LinkedHashMap<>();
        }
        
        for (PublicProfileRecord profile : state.profilesById.values()) {
            for (String tag : profile.tags) {
                updateTagUsage(tag, 1);
            }
        }
        
        for (AccountRecord account : state.accountsByToken.values()) {
            account.accountCreation = AccountRecord.normalizeTimestamp(account.accountCreation);
            if (account.onlineFriends == null) {
                account.onlineFriends = new java.util.LinkedHashSet<>();
            }
            if (account.localFriends == null) {
                account.localFriends = new JsonArray();
            }
            if (account.otherData == null) {
                account.otherData = new JsonArray();
            }
            if (account.privateProfiles == null) {
                account.privateProfiles = new LinkedHashMap<>();
            }
        }
    }

    private void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(state), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record FriendRequestCreation(int status, FriendRequestRecord request, AccountRecord target) {}
    public record FriendRequestUpdate(int status, FriendRequestRecord request, AccountRecord other) {}
    public record PartyInviteResult(int status, PartyRecord party) {}
    public record PartyInviteDecision(int status, PartyRecord party) {}
    public record PartyLeaveResult(boolean successful, PartyRecord party, long newLeaderId) {}
    public record LoaderLoginResult(String token, AccountRecord account) {}
}