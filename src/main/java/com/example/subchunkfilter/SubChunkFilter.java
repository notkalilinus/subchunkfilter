package com.example.subchunkfilter;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;

/**
 * SubChunkFilter — PaperMC 1.21.1 + ProtocolLib
 *
 * Intercepts MAP_CHUNK packets. If the player is above Y=0,
 * strips sections 0-3 (Y=-64 to Y=0) and replaces them with
 * empty/air sections. Underground content is never sent to
 * surface players — base protection without xray.
 *
 * World height in 1.21: Y=-64 to Y=320 = 384 blocks = 24 sections
 * Section index = (Y + 64) / 16
 *   Section 0  → Y -64 to -48
 *   Section 1  → Y -48 to -32
 *   Section 2  → Y -32 to -16
 *   Section 3  → Y -16 to   0   ← deepslate zone
 *   Section 4  → Y   0 to  16   ← surface starts here
 */
public class SubChunkFilter extends JavaPlugin {

    private static final int TOTAL_SECTIONS   = 24; // 1.21 world height
    private static final int SURFACE_SECTION  = 4;  // sections 0-3 are below Y=0

    @Override
    public void onEnable() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Server.MAP_CHUNK) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    Player player = event.getPlayer();
                    if (player.getLocation().getY() >= 0) {
                        try {
                            processChunkPacket(event);
                        } catch (IOException e) {
                            getLogger().warning("Failed to strip chunk sections for "
                                + player.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        );
        getLogger().info("SubChunkFilter enabled — underground sections hidden from surface players.");
    }

    // -----------------------------------------------------------------------
    //  Packet processing
    // -----------------------------------------------------------------------

    private void processChunkPacket(PacketEvent event) throws IOException {
        var packet = event.getPacket();
        byte[] original = packet.getByteArrays().read(0);

        DataInputStream  in  = new DataInputStream(new ByteArrayInputStream(original));
        ByteArrayOutputStream baos = new ByteArrayOutputStream(original.length);
        DataOutputStream out = new DataOutputStream(baos);

        for (int s = 0; s < TOTAL_SECTIONS; s++) {
            if (s < SURFACE_SECTION) {
                skipSection(in);          // discard real underground data
                writeEmptySection(out);   // send air instead
            } else {
                copySection(in, out);     // pass through untouched
            }
        }

        packet.getByteArrays().write(0, baos.toByteArray());
    }

    // -----------------------------------------------------------------------
    //  Section-level IO
    // -----------------------------------------------------------------------

    /** Read and discard one chunk section from the stream. */
    private void skipSection(DataInputStream in) throws IOException {
        in.readShort();                      // block count (discard)
        skipPalettedContainer(in, false);    // block states
        skipPalettedContainer(in, true);     // biomes
    }

    /** Read one section from 'in' and write it unchanged to 'out'. */
    private void copySection(DataInputStream in, DataOutputStream out) throws IOException {
        out.writeShort(in.readShort());      // block count
        copyPalettedContainer(in, out, false); // block states
        copyPalettedContainer(in, out, true);  // biomes
    }

    /**
     * Write a fully empty section (all air, default biome).
     * Single-value palette format:
     *   blockCount (short=0), bits=0, value=0, dataLen=0,
     *   bits=0, value=0, dataLen=0
     */
    private void writeEmptySection(DataOutputStream out) throws IOException {
        out.writeShort(0);    // block count = 0
        // block states — single value palette (air = 0)
        out.writeByte(0);     // bits per entry = 0 → single value mode
        writeVarInt(out, 0);  // palette value  = 0 (air)
        writeVarInt(out, 0);  // data length    = 0
        // biomes — single value palette
        out.writeByte(0);     // bits per entry = 0
        writeVarInt(out, 0);  // palette value  = 0 (plains default)
        writeVarInt(out, 0);  // data length    = 0
    }

    // -----------------------------------------------------------------------
    //  Paletted container helpers
    // -----------------------------------------------------------------------

    /**
     * Palette type rules for 1.21:
     *   Block states: bits=0 → single value | bits 1-8 → indirect (has palette array) | bits 9-15 → direct (no palette array)
     *   Biomes:       bits=0 → single value | bits 1-3 → indirect                      | bits 4-6  → direct
     */
    private void skipPalettedContainer(DataInputStream in, boolean biome) throws IOException {
        int bits = in.readUnsignedByte();
        if (bits == 0) {
            readVarInt(in); // single value
            readVarInt(in); // data length (always 0 in single-value mode)
        } else {
            int indirectMax = biome ? 3 : 8;
            if (bits <= indirectMax) {
                // indirect palette — read and discard palette entries
                int len = readVarInt(in);
                for (int i = 0; i < len; i++) readVarInt(in);
            }
            // direct or indirect: always followed by a data array
            int dataLen = readVarInt(in);
            for (int i = 0; i < dataLen; i++) in.readLong();
        }
    }

    private void copyPalettedContainer(DataInputStream in, DataOutputStream out, boolean biome)
            throws IOException {
        int bits = in.readUnsignedByte();
        out.writeByte(bits);

        if (bits == 0) {
            writeVarInt(out, readVarInt(in)); // single value
            writeVarInt(out, readVarInt(in)); // data length
        } else {
            int indirectMax = biome ? 3 : 8;
            if (bits <= indirectMax) {
                int len = readVarInt(in);
                writeVarInt(out, len);
                for (int i = 0; i < len; i++) writeVarInt(out, readVarInt(in));
            }
            int dataLen = readVarInt(in);
            writeVarInt(out, dataLen);
            for (int i = 0; i < dataLen; i++) out.writeLong(in.readLong());
        }
    }

    // -----------------------------------------------------------------------
    //  VarInt codec
    // -----------------------------------------------------------------------

    private int readVarInt(DataInputStream in) throws IOException {
        int value = 0, shift = 0;
        byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt overflow");
        } while ((b & 0x80) != 0);
        return value;
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }
}
