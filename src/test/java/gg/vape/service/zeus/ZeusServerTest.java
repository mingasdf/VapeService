package gg.vape.service.zeus;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class ZeusServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completesCoreOnlineSequence() throws Exception {
        FileStore store = new FileStore(temporaryDirectory.resolve("state.json"));
        try (ZeusServer server = new ZeusServer("127.0.0.1", 0, store)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());

                UUID handshakeId = UUID.randomUUID();
                writeFrame(output, 1, payload -> {
                    ZeusBuffer.writeUuid(payload, handshakeId);
                    ZeusBuffer.writeVarInt(payload, 10);
                });
                ByteBuf handshake = readFrame(input);
                assertEquals(2, ZeusBuffer.readVarInt(handshake));
                assertEquals(handshakeId, ZeusBuffer.readUuid(handshake));
                assertEquals(0, ZeusBuffer.readVarInt(handshake));
                handshake.release();

                UUID authenticationId = UUID.randomUUID();
                UUID minecraftId = UUID.randomUUID();
                writeFrame(output, 2, payload -> {
                    ZeusBuffer.writeUuid(payload, authenticationId);
                    ZeusBuffer.writeString(payload, "0");
                    ZeusBuffer.writeUuid(payload, minecraftId);
                    ZeusBuffer.writeString(payload, "Player");
                });
                ByteBuf authentication = readFrame(input);
                assertEquals(3, ZeusBuffer.readVarInt(authentication));
                assertEquals(authenticationId, ZeusBuffer.readUuid(authentication));
                assertEquals(1, ZeusBuffer.readVarInt(authentication));
                assertEquals(1L, authentication.readLong());
                assertEquals("Developer", ZeusBuffer.readString(authentication, 16));
                authentication.release();

                UUID friendsId = UUID.randomUUID();
                writeFrame(output, 3, payload -> ZeusBuffer.writeUuid(payload, friendsId));
                ByteBuf friends = readFrame(input);
                assertEquals(3, ZeusBuffer.readVarInt(friends));
                assertEquals(friendsId, ZeusBuffer.readUuid(friends));
                assertEquals(0, friends.readInt());
                assertEquals(0, friends.readInt());
                assertEquals(0, friends.readInt());
                friends.release();

                UUID heartbeatId = UUID.randomUUID();
                writeFrame(output, 0, payload -> ZeusBuffer.writeUuid(payload, heartbeatId));
                ByteBuf heartbeat = readFrame(input);
                assertEquals(0, ZeusBuffer.readVarInt(heartbeat));
                assertEquals(heartbeatId, ZeusBuffer.readUuid(heartbeat));
                heartbeat.release();
            }
        }
    }

    private static void writeFrame(DataOutputStream output, int packetId, Consumer<ByteBuf> writer) {
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

    private static ByteBuf readFrame(DataInputStream input) throws Exception {
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
}
