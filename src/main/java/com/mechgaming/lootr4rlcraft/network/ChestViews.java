package com.mechgaming.lootr4rlcraft.network;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.chest.ChestEvents;
import io.netty.buffer.ByteBuf;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** Transfers eligibility and opened state for one recipient without sending item data. */
public final class ChestViews {
    private static final int WIRE_VERSION = 1;
    private static final int ENTRY_BYTES = Long.BYTES + 1;
    private static final int MAX_ENTRIES = 65535;
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Lootr4RLCraft.ID);

    private ChestViews() {}

    public static void register() {
        CHANNEL.registerMessage((message, context) -> {
            Lootr4RLCraft.proxy.acceptView(message, context);
            return null;
        }, View.class, 0, Side.CLIENT);
        CHANNEL.registerMessage((Refresh message, net.minecraftforge.fml.common.network.simpleimpl.MessageContext context) -> {
            EntityPlayerMP player = context.getServerHandler().player;
            WorldServer world = player.getServerWorld();
            world.addScheduledTask(() -> {
                if (player.world != world || player.dimension != message.dimension
                        || !world.getPlayerChunkMap().isPlayerWatchingChunk(player, message.chunkX, message.chunkZ)) {
                    return;
                }
                Chunk chunk = world.getChunkProvider().getLoadedChunk(message.chunkX, message.chunkZ);
                if (chunk != null) {
                    ChestEvents.sendViews(player, chunk, true);
                }
            });
            return null;
        }, Refresh.class, 1, Side.SERVER);
    }

    public static void refresh(int dimension, int chunkX, int chunkZ) {
        CHANNEL.sendToServer(new Refresh(dimension, chunkX, chunkZ));
    }

    public static void sendChunk(EntityPlayerMP player, ChunkPos chunk, Map<BlockPos, Boolean> states) {
        Settings.debug("Sending {} loot indicators in {} to {}", states.size(), chunk, player.getUniqueID());
        CHANNEL.sendTo(new View(player.dimension, chunk.x, chunk.z, true, new LinkedHashMap<>(states)), player);
    }

    public static void send(EntityPlayerMP player, BlockPos pos, boolean opened) {
        Settings.debug("Sending indicator {} opened={} to {}", pos, opened, player.getUniqueID());
        CHANNEL.sendTo(new View(player.dimension, pos.getX() >> 4, pos.getZ() >> 4, false,
                Collections.singletonMap(pos.toImmutable(), opened)), player);
    }

    /** A client whose local tile was recreated may ask for current state in an already watched chunk. */
    public static final class Refresh implements IMessage {
        private int dimension;
        private int chunkX;
        private int chunkZ;

        public Refresh() {}

        private Refresh(int dimension, int chunkX, int chunkZ) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public void toBytes(ByteBuf out) {
            out.writeByte(WIRE_VERSION).writeInt(dimension).writeInt(chunkX).writeInt(chunkZ);
        }

        @Override
        public void fromBytes(ByteBuf in) {
            if (in.readableBytes() != 13 || in.readUnsignedByte() != WIRE_VERSION) {
                throw new IllegalArgumentException("Invalid Lootr4RLCraft refresh request");
            }
            dimension = in.readInt();
            chunkX = in.readInt();
            chunkZ = in.readInt();
        }
    }

    public static final class View implements IMessage {
        private int dimension;
        private int chunkX;
        private int chunkZ;
        private boolean snapshot;
        private Map<BlockPos, Boolean> states;

        public View() {}

        private View(int dimension, int chunkX, int chunkZ, boolean snapshot, Map<BlockPos, Boolean> states) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.snapshot = snapshot;
            this.states = states;
        }

        public int getDimension() {
            return dimension;
        }

        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        public boolean isSnapshot() {
            return snapshot;
        }

        public Map<BlockPos, Boolean> getStates() {
            return Collections.unmodifiableMap(states);
        }

        @Override
        public void toBytes(ByteBuf out) {
            if (states.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many loot indicators in a chunk");
            }
            out.writeByte(WIRE_VERSION);
            out.writeInt(dimension);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeBoolean(snapshot);
            out.writeShort(states.size());
            states.forEach((pos, opened) -> {
                out.writeLong(pos.toLong());
                out.writeBoolean(opened);
            });
        }

        @Override
        public void fromBytes(ByteBuf in) {
            if (in.readUnsignedByte() != WIRE_VERSION) {
                throw new IllegalArgumentException("Unsupported Lootr4RLCraft packet version");
            }
            dimension = in.readInt();
            chunkX = in.readInt();
            chunkZ = in.readInt();
            snapshot = in.readBoolean();
            int count = in.readUnsignedShort();
            if (in.readableBytes() != count * ENTRY_BYTES || (!snapshot && count != 1)) {
                throw new IllegalArgumentException("Invalid Lootr4RLCraft indicator packet length");
            }
            states = new LinkedHashMap<>();
            for (int entry = 0; entry < count; entry++) {
                BlockPos pos = BlockPos.fromLong(in.readLong());
                int code = in.readUnsignedByte();
                if (pos.getX() >> 4 != chunkX || pos.getZ() >> 4 != chunkZ
                        || pos.getY() < 0 || pos.getY() > 255 || code > 1
                        || states.putIfAbsent(pos, code == 1) != null) {
                    throw new IllegalArgumentException("Invalid Lootr4RLCraft indicator entry");
                }
            }
        }
    }
}
