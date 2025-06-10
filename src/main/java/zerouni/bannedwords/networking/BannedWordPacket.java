package zerouni.bannedwords.networking;

import zerouni.bannedwords.BannedWords;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Defines the custom networking packet for sending transcribed text from client to server.
 */
public record BannedWordPacket(String transcript) implements CustomPayload {
    public static final Identifier BANNED_WORD_PAYLOAD_ID = Identifier.of(BannedWords.MOD_ID, "banned_word_transcript");
    public static final CustomPayload.Id<BannedWordPacket> ID = new CustomPayload.Id<>(BANNED_WORD_PAYLOAD_ID);
    
    /**
     * Codec for serializing/deserializing the packet data.
     */
    public static final PacketCodec<PacketByteBuf, BannedWordPacket> CODEC = PacketCodec.of(
        (value, buf) -> buf.writeString(value.transcript),
        buf -> new BannedWordPacket(buf.readString())
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Writes a transcribed text string into a PacketByteBuf for sending.
     * @param transcript The transcribed text string.
     * @return A PacketByteBuf containing the transcript.
     */
    public static PacketByteBuf write(String transcript) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(transcript);
        return buf;
    }

    /**
     * Reads a transcribed text string from a PacketByteBuf.
     * @param buf The PacketByteBuf to read from.
     * @return The transcribed text string.
     */
    public static String read(PacketByteBuf buf) {
        return buf.readString();
    }
}