package com.mechgaming.lootr4rlcraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;

public final class ChestViewsTest {
    private ChestViewsTest() {}

    public static void verify() {
        BlockPos unopened = new BlockPos(-17, 31, 48);
        BlockPos opened = new BlockPos(-32, 123, 63);
        ByteBuf snapshot = header(1, true, 2);
        ByteBuf encoded = Unpooled.buffer();
        try {
            snapshot.writeLong(unopened.toLong()).writeByte(0);
            snapshot.writeLong(opened.toLong()).writeByte(1);
            ChestViews.View decoded = decode(snapshot);
            check(decoded.isSnapshot() && decoded.getDimension() == -1
                    && decoded.getChunkX() == -2 && decoded.getChunkZ() == 3, "Snapshot header changed");
            check(Boolean.FALSE.equals(decoded.getStates().get(unopened))
                    && Boolean.TRUE.equals(decoded.getStates().get(opened)), "Snapshot states changed");
            decoded.toBytes(encoded);
            check(decode(encoded).getStates().equals(decoded.getStates()), "Snapshot did not round-trip");
        } finally {
            encoded.release();
            snapshot.release();
        }

        ByteBuf delta = header(1, false, 1);
        try {
            delta.writeLong(opened.toLong()).writeByte(1);
            ChestViews.View change = decode(delta);
            check(!change.isSnapshot() && Boolean.TRUE.equals(change.getStates().get(opened)),
                    "Single-position opened update did not decode");
        } finally {
            delta.release();
        }

        reject(header(2, true, 0));
        reject(header(1, true, 1));
        reject(header(1, false, 0));
        reject(header(1, true, 1).writeLong(opened.toLong()).writeByte(2));
        reject(header(1, true, 1).writeLong(BlockPos.ORIGIN.toLong()).writeByte(1));
    }

    private static ByteBuf header(int version, boolean snapshot, int entries) {
        return Unpooled.buffer().writeByte(version).writeInt(-1).writeInt(-2)
                .writeInt(3).writeBoolean(snapshot).writeShort(entries);
    }

    private static ChestViews.View decode(ByteBuf buffer) {
        ChestViews.View view = new ChestViews.View();
        view.fromBytes(buffer);
        return view;
    }

    private static void reject(ByteBuf buffer) {
        try {
            decode(buffer);
            throw new AssertionError("Invalid indicator packet was accepted");
        } catch (IllegalArgumentException expected) {
            // The packet was rejected before reaching the client view state.
        } finally {
            buffer.release();
        }
    }

    private static void check(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }
}
