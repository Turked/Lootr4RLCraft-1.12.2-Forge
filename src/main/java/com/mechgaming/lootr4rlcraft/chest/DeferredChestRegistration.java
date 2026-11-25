package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants.NBT;

/** Preserves proven generation eligibility while Locks performs its own deferred generation. */
final class DeferredChestRegistration {
    static final String MARKER = "lootr4rlcraft:pending_registration";
    private final Map<WorldServer, PendingWorld> worlds = new IdentityHashMap<>();

    void add(WorldServer world, Set<BlockPos> generated) {
        if (generated.isEmpty()) {
            return;
        }
        PendingWorld pending = worlds.computeIfAbsent(world, ignored -> new PendingWorld());
        boolean ready = false;
        for (BlockPos pos : generated) {
            TileEntity tile = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
            if (tile != null && ContainerLoot.eligibleGenerated(tile)
                    && !pending.chests.containsKey(pos)) {
                TileEntity original = tile;
                Block block = world.getBlockState(pos).getBlock();
                String digest = fingerprint(original);
                NBTTagCompound marker = new NBTTagCompound();
                marker.setInteger("Version", 1);
                marker.setLong("Position", pos.toLong());
                marker.setInteger("Dimension", world.provider.getDimension());
                marker.setString("Block", block.getRegistryName().toString());
                marker.setString("Fingerprint", digest);
                original.getTileData().setTag(MARKER, marker);
                original.markDirty();
                pending.chests.put(pos.toImmutable(), new PendingChest(original, block, digest));
                Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
                ready |= chunk != null && LockAccess.worldgenReady(chunk, original);
                Settings.debug("Observed generated {} at {} in dimension {}", block.getRegistryName(), pos,
                        world.provider.getDimension());
            }
        }
        if (pending.chests.isEmpty()) {
            worlds.remove(world);
        } else if (ready) {
            flush(world, pending);
        }
        wake(world);
    }

    boolean preparing(WorldServer world, BlockPos pos) {
        PendingWorld pending = worlds.get(world);
        PendingChest candidate = pending == null ? null : pending.chests.get(pos);
        if (candidate == null) {
            return false;
        }
        if (!candidate.matches(world, pos)) {
            pending.chests.remove(pos);
            clearMarker(candidate.original);
            return false;
        }
        Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !LockAccess.worldgenReady(chunk, candidate.original)) {
            return true;
        }
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.offset(side);
            Chunk other = world.getChunkProvider().getLoadedChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4);
            if (other != null && world.getBlockState(neighbor).getBlock() == candidate.block
                    && !LockAccess.worldgenReady(other, candidate.original)) {
                return true;
            }
        }
        flush(world, pending);
        return false;
    }

    void load(Chunk chunk) {
        if (!(chunk.getWorld() instanceof WorldServer)) {
            return;
        }
        WorldServer world = (WorldServer) chunk.getWorld();
        for (TileEntity tile : chunk.getTileEntityMap().values()) {
            if (!tile.getTileData().hasKey(MARKER, NBT.TAG_COMPOUND)) {
                continue;
            }
            NBTTagCompound marker = tile.getTileData().getCompoundTag(MARKER);
            Block block = world.getBlockState(tile.getPos()).getBlock();
            if (marker.getInteger("Version") != 1) {
                continue;
            }
            if (!marker.hasKey("Position", NBT.TAG_LONG) || marker.getLong("Position") != tile.getPos().toLong()
                    || !marker.hasKey("Dimension", NBT.TAG_INT)
                    || marker.getInteger("Dimension") != world.provider.getDimension()
                    || !ContainerLoot.eligibleGenerated(tile)
                    || !block.getRegistryName().toString().equals(marker.getString("Block"))
                    || !fingerprint(tile).equals(marker.getString("Fingerprint"))) {
                clearMarker(tile);
                continue;
            }
            PendingWorld pending = worlds.computeIfAbsent(world, ignored -> new PendingWorld());
            pending.chests.put(tile.getPos().toImmutable(),
                    new PendingChest(tile, block, marker.getString("Fingerprint")));
        }
        wake(world);
    }

    void wake(WorldServer world) {
        PendingWorld pending = worlds.get(world);
        if (pending != null && !pending.chests.isEmpty()) {
            long due = world.getTotalWorldTime() + LockAccess.generationRetryDelay();
            pending.nextAttempt = Math.min(pending.nextAttempt, due);
            pending.lastWakeDue = Math.max(pending.lastWakeDue, due);
        }
    }

    void tick(WorldServer world) {
        PendingWorld pending = worlds.get(world);
        if (pending != null && pending.nextAttempt <= world.getTotalWorldTime()) {
            flush(world, pending);
        }
    }

    private void flush(WorldServer world, PendingWorld pending) {
        // A later chunk may have begun Locks' delay while an earlier check was scheduled.
        pending.nextAttempt = pending.lastWakeDue > world.getTotalWorldTime() ? pending.lastWakeDue : Long.MAX_VALUE;
        Set<BlockPos> ready = new HashSet<>();
        for (Map.Entry<BlockPos, PendingChest> candidate : pending.chests.entrySet()) {
            BlockPos pos = candidate.getKey();
            Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && LockAccess.worldgenReady(chunk, candidate.getValue().original)) {
                ready.add(pos);
            } else if (chunk != null) {
                PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(chunk.x, chunk.z);
                if (entry != null && !entry.getWatchingPlayers().isEmpty()) {
                    // Locks resets its own delay while a cardinal neighbour is unavailable. A lone
                    // initial check can miss its phase transition for the entire stationary visit.
                    pending.nextAttempt = Math.min(pending.nextAttempt,
                            world.getTotalWorldTime() + Math.max(20, LockAccess.generationRetryDelay()));
                }
            }
        }
        int registered = 0;
        // Validate every ready part before any pair can register, including a changed partner.
        ready.removeIf(pos -> {
            PendingChest candidate = pending.chests.get(pos);
            if (candidate.matches(world, pos) && ContainerLoot.eligibleGenerated(candidate.original)) {
                return false;
            }
            pending.chests.remove(pos);
            clearMarker(candidate.original);
            return true;
        });
        for (BlockPos pos : ready) {
            PendingChest candidate = pending.chests.get(pos);
            if (ContainerLoot.isManaged(candidate.original)) {
                continue;
            }
            try {
                if (ContainerLoot.registerGenerated(world, pos, ready)) {
                    registered++;
                }
            } catch (IllegalArgumentException waiting) {
                Settings.debug("Deferred generated inventory at {} in dimension {}: {}", pos,
                        world.provider.getDimension(), waiting.getMessage());
            } catch (RuntimeException failure) {
                pending.chests.remove(pos);
                clearMarker(candidate.original);
                Lootr4RLCraft.LOGGER.error("Could not register generated chest at {}", pos, failure);
            }
        }
        pending.chests.entrySet().removeIf(entry -> ContainerLoot.isManaged(entry.getValue().original));
        if (pending.chests.isEmpty()) {
            worlds.remove(world);
        }
        if (registered > 0) {
            Settings.debug("Registered {} generated loot container(s) after native lock generation in dimension {}",
                    registered, world.provider.getDimension());
        }
    }

    static void clearMarker(TileEntity tile) {
        if (tile.getTileData().hasKey(MARKER)) {
            tile.getTileData().removeTag(MARKER);
            tile.markDirty();
        }
    }

    void unload(Chunk chunk) {
        if (!(chunk.getWorld() instanceof WorldServer)) {
            return;
        }
        WorldServer world = (WorldServer) chunk.getWorld();
        PendingWorld pending = worlds.get(world);
        if (pending != null) {
            pending.chests.keySet().removeIf(pos -> pos.getX() >> 4 == chunk.x && pos.getZ() >> 4 == chunk.z);
            if (pending.chests.isEmpty()) {
                worlds.remove(world);
            }
        }
    }

    void unload(WorldServer world) {
        worlds.remove(world);
    }

    static String fingerprint(TileEntity tile) {
        NBTTagCompound contents = tile.writeToNBT(new NBTTagCompound()).copy();
        NBTTagCompound forgeData = contents.getCompoundTag("ForgeData");
        forgeData.removeTag(MARKER);
        if (forgeData.hasNoTags()) {
            contents.removeTag("ForgeData");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeCanonical(contents, output);
            }
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                text.append(Character.forDigit((part >>> 4) & 15, 16));
                text.append(Character.forDigit(part & 15, 16));
            }
            return text.toString();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Could not fingerprint generated chest data", failure);
        }
    }

    private static void writeCanonical(NBTBase value, DataOutputStream output) throws IOException {
        output.writeByte(value.getId());
        if (value instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) value;
            List<String> keys = new ArrayList<>(compound.getKeySet());
            Collections.sort(keys);
            output.writeInt(keys.size());
            for (String key : keys) {
                output.writeUTF(key);
                writeCanonical(compound.getTag(key), output);
            }
        } else if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) value;
            output.writeInt(list.getTagType());
            output.writeInt(list.tagCount());
            for (NBTBase child : list) {
                writeCanonical(child, output);
            }
        } else {
            NBTTagCompound scalar = new NBTTagCompound();
            scalar.setTag("Value", value);
            CompressedStreamTools.write(scalar, output);
        }
    }

    private static final class PendingWorld {
        private final Map<BlockPos, PendingChest> chests = new LinkedHashMap<>();
        private long nextAttempt = Long.MAX_VALUE;
        private long lastWakeDue = Long.MIN_VALUE;
    }

    private static final class PendingChest {
        private final TileEntity original;
        private final Block block;
        private final String fingerprint;

        private PendingChest(TileEntity original, Block block, String fingerprint) {
            this.original = original;
            this.block = block;
            this.fingerprint = fingerprint;
        }

        private boolean matches(WorldServer world, BlockPos pos) {
            return world.isBlockLoaded(pos) && world.getTileEntity(pos) == original && !original.isInvalid()
                    && world.getBlockState(pos).getBlock() == block
                    && fingerprint.equals(DeferredChestRegistration.fingerprint(original));
        }
    }
}
