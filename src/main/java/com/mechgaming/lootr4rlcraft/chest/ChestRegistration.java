package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Explicit conversion and the shared, positive-provenance world-generation registration path. */
public final class ChestRegistration {
    private ChestRegistration() {}

    public static String inspect(WorldServer world, BlockPos pos) {
        requireLoaded(world, pos);
        TileEntity tile = world.getTileEntity(pos);
        String details = "Block " + world.getBlockState(pos).getBlock().getRegistryName()
                + ", tile " + (tile == null ? "none" : tile.getClass().getName())
                + ". Automatic new-chest registration " + (Settings.registerNewChests ? "enabled" : "disabled") + ". ";
        if (tile instanceof InstancedChestTile) {
            InstancedChestTile managed = (InstancedChestTile) tile;
            return details + "Managed " + (managed.isPaired() ? "double" : "single") + " Lootr4RLCraft chest, "
                    + (managed.isFixed() ? "fixed template" : "original loot table")
                    + ", " + (managed.canDisplay() ? "valid identity" : "missing or mismatched partner/identity") + ".";
        }
        try {
            List<TileEntityChest> originals = originals(world, pos);
            long tables = originals.stream().filter(chest -> chest.getLootTable() != null).count();
            String sources = "";
            for (TileEntityChest chest : originals) {
                sources += chest.getPos() + "=" + (chest.getLootTable() == null ? "fixed contents" : chest.getLootTable()) + " ";
            }
            return details + originals.size() + " chest half/halves. " + sources
                    + (tables > 0 ? "Eligible for convert." : "Original loot table unavailable. Use convert-fixed to preserve the current slots for each player.");
        } catch (IllegalArgumentException refusal) {
            return details + refusal.getMessage();
        }
    }

    public static String convert(WorldServer world, BlockPos pos, boolean fixed) {
        List<TileEntityChest> originals = originals(world, pos);
        boolean hasTable = originals.stream().anyMatch(chest -> chest.getLootTable() != null);
        if (fixed && hasTable) {
            throw new IllegalArgumentException("This chest still has an original loot table. Use convert to retain its rules.");
        }
        if (!fixed && !hasTable) {
            throw new IllegalArgumentException("The original loot table is unavailable. Use convert-fixed to allocate the current slots to each player.");
        }
        replace(world, originals);
        return "Converted " + (originals.size() == 2 ? "double" : "single") + " chest at "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " into a Lootr4RLCraft inventory"
                + (fixed ? " using its current fixed contents." : " using its original loot behavior.");
    }

    static boolean registerGenerated(WorldServer world, BlockPos pos, Set<BlockPos> newlyGenerated) {
        if (!Settings.includeTrapped && world.getBlockState(pos).getBlock() == Blocks.TRAPPED_CHEST) {
            return false;
        }
        List<TileEntityChest> originals = originals(world, pos);
        boolean table = originals.stream().anyMatch(chest -> chest.getLootTable() != null);
        if (!originals.stream().allMatch(chest -> newlyGenerated.contains(chest.getPos()))
                || (!Settings.generatedFixedChests
                        && originals.stream().anyMatch(chest -> chest.getLootTable() == null && !chest.isEmpty()))
                || (!table && (!Settings.generatedFixedChests || originals.stream().allMatch(TileEntityChest::isEmpty)))) {
            return false;
        }
        replace(world, originals);
        return true;
    }

    static boolean isVanillaChest(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private static List<TileEntityChest> originals(WorldServer world, BlockPos pos) {
        requireLoaded(world, pos);
        if (!world.getMinecraftServer().isCallingFromMinecraftThread()) {
            throw new IllegalArgumentException("Chest conversion must run on the server thread.");
        }
        if (!LockAccess.available()) {
            throw new IllegalArgumentException("The installed Locks authorization API is unavailable.");
        }
        Block block = world.getBlockState(pos).getBlock();
        if (!isVanillaChest(block)) {
            throw new IllegalArgumentException("This container type has no supported adapter yet.");
        }
        List<TileEntityChest> originals = new ArrayList<>(2);
        originals.add(original(world, pos));
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.offset(side);
            requireLoaded(world, neighbor);
            if (world.getBlockState(neighbor).getBlock() == block) {
                if (originals.size() == 2) {
                    throw new IllegalArgumentException("Unsupported chest arrangement with more than two connected halves.");
                }
                originals.add(original(world, neighbor));
            }
        }
        for (TileEntityChest chest : originals) {
            if (!LockAccess.worldgenReady(world.getChunkFromBlockCoords(chest.getPos()))) {
                throw new IllegalArgumentException("Locks is still preparing this chunk. Keep the surrounding chunks loaded, wait for lock generation, then retry conversion.");
            }
            for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
                BlockPos neighbor = chest.getPos().offset(side);
                requireLoaded(world, neighbor);
                if (world.getBlockState(neighbor).getBlock() == block
                        && originals.stream().noneMatch(part -> part.getPos().equals(neighbor))) {
                    throw new IllegalArgumentException("Unsupported chest arrangement with more than two connected halves.");
                }
            }
        }
        originals.sort(Comparator.comparingInt((TileEntityChest chest) -> chest.getPos().getX())
                .thenComparingInt(chest -> chest.getPos().getZ()));
        return originals;
    }

    private static TileEntityChest original(WorldServer world, BlockPos pos) {
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof InstancedChestTile) {
            throw new IllegalArgumentException("This chest is already managed. Existing private inventories are preserved.");
        }
        if (tile == null || tile.getClass() != TileEntityChest.class || tile.isInvalid()) {
            throw new IllegalArgumentException("The chest tile implementation has no supported adapter yet.");
        }
        TileEntityChest chest = (TileEntityChest) tile;
        if (chest.numPlayersUsing > 0 || isInUse(world, chest)) {
            throw new IllegalArgumentException("Close every open menu for this chest before conversion.");
        }
        if (chest.getLootTable() != null && !chest.isEmpty()) {
            throw new IllegalArgumentException("A pending loot table conflicts with existing physical items. Conversion was refused.");
        }
        return chest;
    }

    private static boolean isInUse(WorldServer world, TileEntityChest chest) {
        for (EntityPlayer player : world.playerEntities) {
            if (player.openContainer instanceof ContainerChest) {
                IInventory inventory = ((ContainerChest) player.openContainer).getLowerChestInventory();
                if (inventory == chest || inventory instanceof InventoryLargeChest
                        && ((InventoryLargeChest) inventory).isPartOfLargeChest(chest)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void replace(WorldServer world, List<TileEntityChest> originals) {
        List<InstancedChestTile> replacements = new ArrayList<>(originals.size());
        List<NBTTagCompound> backups = new ArrayList<>(originals.size());
        for (TileEntityChest original : originals) {
            backups.add(original.writeToNBT(new NBTTagCompound()));
            InstancedChestTile replacement = InstancedChestTile.prepare(world, original);
            DeferredChestRegistration.clearMarker(replacement);
            replacements.add(replacement);
        }
        if (replacements.size() == 2) {
            InstancedChestTile.link(replacements.get(0), replacements.get(1));
        }
        try {
            for (InstancedChestTile replacement : replacements) {
                world.setTileEntity(replacement.getPos(), replacement);
                if (world.getTileEntity(replacement.getPos()) != replacement) {
                    throw new IllegalStateException("World did not accept the chest tile replacement");
                }
                replacement.markDirty();
            }
        } catch (RuntimeException failure) {
            for (int index = 0; index < originals.size(); index++) {
                TileEntityChest original = originals.get(index);
                original.readFromNBT(backups.get(index));
                original.validate();
                world.setTileEntity(original.getPos(), original);
                original.markDirty();
            }
            throw failure;
        }
        // Retire stale physical references after all replacements were installed successfully.
        for (TileEntityChest original : originals) {
            original.setLootTable(null, 0L);
            original.clear();
        }
        for (InstancedChestTile replacement : replacements) {
            PlayerChunkMapEntry watching = world.getPlayerChunkMap().getEntry(
                    replacement.getPos().getX() >> 4, replacement.getPos().getZ() >> 4);
            if (watching != null) {
                for (EntityPlayerMP player : watching.getWatchingPlayers()) {
                    ChestViews.send(player, replacement.getPos(), false);
                }
            }
        }
    }

    private static void requireLoaded(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            throw new IllegalArgumentException("The chest and every connected neighboring chunk must already be loaded.");
        }
    }
}
