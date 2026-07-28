package gg.vape.service.zeus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.vape.service.store.AuthChallengeRecord;
import gg.vape.service.store.FileStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZeusBusinessFlowTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void routesFriendPartyPingAndActivityTrafficBetweenClients() throws Exception {
        FileStore store = new FileStore(temporaryDirectory.resolve("state.json"));
        AuthChallengeRecord challenge = store.createChallenge("v4", "test");
        assertTrue(store.approveChallenge(challenge.challenge));
        String secondToken = store.challenge(challenge.challenge).orElseThrow().accessToken;
        store.register(secondToken, "Alice");

        try (ZeusServer server = new ZeusServer("127.0.0.1", 0, store)) {
            server.start();
            try (WireClient developer = new WireClient(server.port(), "0", "DeveloperMc");
                 WireClient alice = new WireClient(server.port(), secondToken, "AliceMc")) {
                developer.initialize();
                alice.initialize();

                UUID friendRequestId = developer.sendTracked(5,
                        payload -> ZeusBuffer.writeString(payload, "Alice"));
                ByteBuf requestResponse = developer.readPacket(10);
                assertEquals(friendRequestId, ZeusBuffer.readUuid(requestResponse));
                assertEquals(0, ZeusBuffer.readVarInt(requestResponse));
                long requestId = requestResponse.readLong();
                requestResponse.release();

                ByteBuf requestPush = alice.readPacket(8);
                assertEquals(requestId, requestPush.readLong());
                requestPush.release();

                UUID acceptId = alice.sendTracked(6, payload -> {
                    payload.writeLong(requestId);
                    payload.writeBoolean(true);
                });
                ByteBuf acceptResponse = alice.readPacket(11);
                assertEquals(acceptId, ZeusBuffer.readUuid(acceptResponse));
                assertEquals(requestId, acceptResponse.readLong());
                assertEquals(0, ZeusBuffer.readVarInt(acceptResponse));
                acceptResponse.release();
                developer.readPacket(4).release();

                UUID chatId = developer.sendTracked(7, payload -> {
                    payload.writeLong(2L);
                    ZeusBuffer.writeString(payload, "hello");
                });
                ByteBuf chatResponse = developer.readPacket(12);
                assertEquals(chatId, ZeusBuffer.readUuid(chatResponse));
                assertEquals(0, ZeusBuffer.readVarInt(chatResponse));
                assertEquals("hello", ZeusBuffer.readString(chatResponse, 255));
                chatResponse.release();
                ByteBuf chatPush = alice.readPacket(30);
                assertEquals(1L, chatPush.readLong());
                assertEquals("Developer", ZeusBuffer.readString(chatPush, 16));
                assertEquals("hello", ZeusBuffer.readString(chatPush, 255));
                chatPush.release();

                UUID createId = developer.sendTracked(8, ignored -> {
                });
                ByteBuf createResponse = developer.readPacket(14);
                assertEquals(createId, ZeusBuffer.readUuid(createResponse));
                assertEquals(0, ZeusBuffer.readVarInt(createResponse));
                createResponse.release();

                UUID inviteId = developer.sendTracked(11, payload -> {
                    payload.writeLong(2L);
                    ZeusBuffer.writeString(payload, "Alice");
                });
                ByteBuf inviteResponse = developer.readPacket(20);
                assertEquals(inviteId, ZeusBuffer.readUuid(inviteResponse));
                assertEquals(0, ZeusBuffer.readVarInt(inviteResponse));
                inviteResponse.release();
                alice.readPacket(26).release();

                UUID joinId = alice.sendTracked(15, payload -> {
                    payload.writeLong(1L);
                    payload.writeBoolean(true);
                });
                ByteBuf joinResponse = alice.readPacket(27);
                assertEquals(joinId, ZeusBuffer.readUuid(joinResponse));
                assertEquals(0, ZeusBuffer.readVarInt(joinResponse));
                joinResponse.release();
                developer.readPacket(18).release();

                UUID groupChatId = alice.sendTracked(17,
                        payload -> ZeusBuffer.writeString(payload, "party hello"));
                ByteBuf groupChatResponse = alice.readPacket(29);
                assertEquals(groupChatId, ZeusBuffer.readUuid(groupChatResponse));
                assertEquals(0, ZeusBuffer.readVarInt(groupChatResponse));
                groupChatResponse.release();
                ByteBuf groupChatPush = developer.readPacket(13);
                assertEquals(2L, groupChatPush.readLong());
                assertEquals("party hello", ZeusBuffer.readString(groupChatPush, 255));
                groupChatPush.release();

                UUID pingId = developer.sendTracked(18, payload -> {
                    ZeusBuffer.writeVarInt(payload, 0);
                    payload.writeDouble(1.0);
                    payload.writeDouble(2.0);
                    payload.writeDouble(3.0);
                });
                ByteBuf pingResponse = developer.readPacket(31);
                assertEquals(pingId, ZeusBuffer.readUuid(pingResponse));
                assertTrue(pingResponse.readBoolean());
                assertEquals(1, ZeusBuffer.readVarInt(pingResponse));
                pingResponse.release();
                ByteBuf pingPush = alice.readPacket(32);
                assertEquals(1, ZeusBuffer.readVarInt(pingPush));
                assertEquals(1L, pingPush.readLong());
                assertEquals(0, ZeusBuffer.readVarInt(pingPush));
                pingPush.release();

                alice.send(21, payload -> {
                    ZeusBuffer.writeVarInt(payload, 0);
                    ZeusBuffer.writeVarInt(payload, 1);
                    payload.writeLong(1L);
                });
                alice.readPacket(36).release();
                developer.send(29, payload -> ZeusBuffer.writeVarInt(payload, 12));
                ByteBuf cpsPush = alice.readPacket(43);
                assertEquals(1L, cpsPush.readLong());
                assertEquals(12, ZeusBuffer.readVarInt(cpsPush));
                cpsPush.release();

                developer.send(27, payload -> {
                    payload.writeInt(2);
                    ZeusBuffer.writeVarInt(payload, 0);
                });
                ByteBuf inventoryPush = alice.readPacket(41);
                assertEquals(1L, inventoryPush.readLong());
                assertEquals(2, ZeusBuffer.readVarInt(inventoryPush));
                assertEquals(0, ZeusBuffer.readVarInt(inventoryPush));
                inventoryPush.release();

                developer.send(22, payload -> {
                    payload.writeBoolean(false);
                    payload.writeBoolean(false);
                    payload.writeBoolean(false);
                });
                ByteBuf snapshotPush = alice.readPacket(37);
                assertEquals(1, ZeusBuffer.readVarInt(snapshotPush));
                assertEquals(1L, snapshotPush.readLong());
                assertEquals(0, snapshotPush.readUnsignedByte());
                assertEquals(0, snapshotPush.readUnsignedByte());
                assertEquals(0, snapshotPush.readUnsignedByte());
                snapshotPush.release();
            }
        }

        FileStore reloaded = new FileStore(temporaryDirectory.resolve("state.json"));
        assertTrue(reloaded.areFriends(1L, 2L));
        assertTrue(reloaded.partyFor(1L).orElseThrow().members.contains(2L));
    }

    private static final class WireClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final String token;
        private final String minecraftName;

        private WireClient(int port, String token, String minecraftName) throws Exception {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setSoTimeout(3000);
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
            this.token = token;
            this.minecraftName = minecraftName;
        }

        private void initialize() throws Exception {
            UUID handshake = sendTracked(1, payload -> ZeusBuffer.writeVarInt(payload, 10));
            ByteBuf handshakeResponse = readPacket(2);
            assertEquals(handshake, ZeusBuffer.readUuid(handshakeResponse));
            assertEquals(0, ZeusBuffer.readVarInt(handshakeResponse));
            handshakeResponse.release();

            UUID authentication = sendTracked(2, payload -> {
                ZeusBuffer.writeString(payload, token);
                ZeusBuffer.writeUuid(payload, UUID.randomUUID());
                ZeusBuffer.writeString(payload, minecraftName);
            });
            ByteBuf authResponse = readPacket(3);
            assertEquals(authentication, ZeusBuffer.readUuid(authResponse));
            assertEquals(1, ZeusBuffer.readVarInt(authResponse));
            authResponse.release();

            UUID friends = sendTracked(3, ignored -> {
            });
            ByteBuf friendsResponse = readPacket(3);
            assertEquals(friends, ZeusBuffer.readUuid(friendsResponse));
            friendsResponse.release();
        }

        private UUID sendTracked(int packetId, Consumer<ByteBuf> writer) {
            UUID requestId = UUID.randomUUID();
            send(packetId, payload -> {
                ZeusBuffer.writeUuid(payload, requestId);
                writer.accept(payload);
            });
            return requestId;
        }

        private void send(int packetId, Consumer<ByteBuf> writer) {
            try {
                ByteBuf payload = Unpooled.buffer();
                ZeusBuffer.writeVarInt(payload, packetId);
                writer.accept(payload);
                ByteBuf frame = Unpooled.buffer();
                ZeusBuffer.writeVarInt(frame, payload.readableBytes());
                frame.writeBytes(payload);
                byte[] bytes = new byte[frame.readableBytes()];
                frame.readBytes(bytes);
                output.write(bytes);
                output.flush();
                payload.release();
                frame.release();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        private ByteBuf readPacket(int expectedPacketId) throws Exception {
            for (int attempt = 0; attempt < 20; attempt++) {
                ByteBuf frame = readFrame();
                int packetId = ZeusBuffer.readVarInt(frame);
                if (packetId == expectedPacketId) {
                    return frame;
                }
                frame.release();
            }
            throw new AssertionError("Packet " + expectedPacketId + " was not received");
        }

        private ByteBuf readFrame() throws Exception {
            int length = 0;
            for (int index = 0; index < 3; index++) {
                byte current = input.readByte();
                length |= (current & 0x7f) << index * 7;
                if ((current & 0x80) == 0) {
                    return Unpooled.wrappedBuffer(input.readNBytes(length));
                }
            }
            throw new IllegalStateException("Frame length is too wide");
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
