package com.mechgaming.lootr4rlcraft.container;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.adapter.ContainerAdapters;
import com.mechgaming.lootr4rlcraft.adapter.InventoryAdapter;
import com.mechgaming.lootr4rlcraft.chest.InstancedChestTile;
import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.block.BlockChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.ILockableContainer;
import net.minecraft.world.LockCode;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.fml.common.Loader;

/** Keeps each installed container's tile and rendering while its private loot stays in server chunk storage. */
public final class ContainerLoot {
    public static final String MARKER = "lootr4rlcraft:container";
    private static final String PENDING = "lootr4rlcraft:pending_registration";
    private static final ITextComponent TITLE = new TextComponentString("lootr");

    private ContainerLoot() {}

    public static boolean supports(TileEntity tile) {
        if (tile == null || tile instanceof InstancedChestTile) {
            return false;
        }
        InventoryAdapter adapter = ContainerAdapters.forEnrollment(tile);
        if (adapter == null || tile.getBlockType().getRegistryName() == null
                || !Settings.blockAllowed(tile.getBlockType().getRegistryName().toString(), adapter.id())) {
            return false;
        }
        int slots = adapter.physical(tile).getSizeInventory();
        return slots > 0 && slots <= 256;
    }

    public static boolean isManaged(@Nullable TileEntity tile) {
        return tile != null && tile.getTileData().hasKey(MARKER, 10);
    }

    public static boolean eligibleGenerated(TileEntity tile) {
        if (!supports(tile) || isManaged(tile)) {
            return false;
        }
        InventoryAdapter adapter = requireAdapter(tile);
        if (!Settings.includeTrapped && tile.getBlockType() instanceof BlockChest
                && ((BlockChest) tile.getBlockType()).chestType == BlockChest.Type.TRAP) {
            return false;
        }
        if (adapter.hasLootTable(tile) || Settings.generatedFixedChests && !adapter.physical(tile).isEmpty()) {
            return true;
        }
        if (!(tile.getWorld() instanceof WorldServer)) {
            return false;
        }
        try {
            for (TileEntity other : adapter.parts((WorldServer) tile.getWorld(), tile.getPos())) {
                InventoryAdapter otherAdapter = requireAdapter(other);
                if (otherAdapter.hasLootTable(other)
                        || Settings.generatedFixedChests && !otherAdapter.physical(other).isEmpty()) {
                    return true;
                }
            }
        } catch (IllegalArgumentException unavailable) {
            // A connected chunk may become available at the next generation/tracking event.
            return true;
        }
        return false;
    }

    public static boolean registerGenerated(WorldServer world, BlockPos pos, Set<BlockPos> generated) {
        List<TileEntity> tiles = originals(world, pos);
        for (TileEntity tile : tiles) {
            if (!generated.contains(tile.getPos())) {
                return false;
            }
        }
        if (!eligibleGenerated(world.getTileEntity(pos))) {
            return false;
        }
        if (!Settings.generatedFixedChests) {
            for (TileEntity tile : tiles) {
                InventoryAdapter adapter = requireAdapter(tile);
                if (!adapter.hasLootTable(tile) && !adapter.physical(tile).isEmpty()) {
                    return false;
                }
            }
        }
        register(world, tiles);
        return true;
    }

    public static String inspect(WorldServer world, BlockPos pos) {
        requireLoaded(world, pos);
        TileEntity tile = world.getTileEntity(pos);
        if (tile == null) {
            return "This block has no inventory tile.";
        }
        String prefix = "Block " + world.getBlockState(pos).getBlock().getRegistryName()
                + ", tile " + tile.getClass().getName() + ". ";
        if (isManaged(tile)) {
            Record record = record(world, pos);
            return prefix + (record == null ? "Private loot data is unavailable in the currently loaded chunks."
                    : "Managed lootr inventory with " + record.parts.size() + " part(s), "
                            + record.size() + " private slots and an independent native automation buffer.");
        }
        try {
            List<TileEntity> parts = originals(world, pos);
            boolean table = parts.stream().anyMatch(part -> requireAdapter(part).hasLootTable(part));
            return prefix + "Adapter " + requireAdapter(tile).id() + ", " + parts.size() + " part(s). "
                    + (table ? "Retains native loot generation; use convert."
                            : "Contains fixed slots; use convert-fixed to give each player these starting contents.");
        } catch (IllegalArgumentException refusal) {
            return prefix + refusal.getMessage();
        }
    }

    public static String convert(WorldServer world, BlockPos pos, boolean fixed) {
        List<TileEntity> tiles = originals(world, pos);
        boolean table = tiles.stream().anyMatch(tile -> requireAdapter(tile).hasLootTable(tile));
        if (fixed == table) {
            throw new IllegalArgumentException(table ? "Use convert to preserve the native loot rules."
                    : "The native loot source is gone. Use convert-fixed to preserve the current slots.");
        }
        register(world, tiles);
        return "Converted " + tiles.size() + " container part(s) at " + pos.getX() + " " + pos.getY() + " "
                + pos.getZ() + " to private lootr inventories.";
    }

    private static List<TileEntity> originals(WorldServer world, BlockPos pos) {
        requireLoaded(world, pos);
        if (!world.getMinecraftServer().isCallingFromMinecraftThread()) {
            throw new IllegalArgumentException("Container conversion must run on the server thread.");
        }
        TileEntity first = world.getTileEntity(pos);
        InventoryAdapter adapter = ContainerAdapters.forEnrollment(first);
        if (adapter == null || !supports(first)) {
            throw new IllegalArgumentException("This block has no enabled adapter for new loot containers.");
        }
        List<TileEntity> parts = adapter.parts(world, pos);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("The native container has no available inventory parts.");
        }
        for (TileEntity tile : parts) {
            requireLoaded(world, tile.getPos());
            if (isManaged(tile) || tile instanceof InstancedChestTile) {
                throw new IllegalArgumentException("This inventory is already managed. Its private contents are preserved.");
            }
            if (!supports(tile) || tile.isInvalid() || world.getTileEntity(tile.getPos()) != tile) {
                throw new IllegalArgumentException("The original container is unavailable or has changed.");
            }
            Chunk chunk = world.getChunkProvider().getLoadedChunk(tile.getPos().getX() >> 4, tile.getPos().getZ() >> 4);
            if (!LockAccess.worldgenReady(chunk, tile)) {
                throw new IllegalArgumentException("Locks is still preparing this chunk. Wait for native lock generation.");
            }
            if (busy(world, tile)) {
                throw new IllegalArgumentException("Close every menu for this inventory before conversion.");
            }
        }
        return parts;
    }

    private static boolean busy(WorldServer world, TileEntity tile) {
        if (tile instanceof net.minecraft.tileentity.TileEntityChest
                && ((net.minecraft.tileentity.TileEntityChest) tile).numPlayersUsing > 0) {
            return true;
        }
        try {
            Object viewers = tile.getClass().getField("numUsers").get(tile);
            if (viewers instanceof Number && ((Number) viewers).intValue() > 0) {
                return true;
            }
        } catch (NoSuchFieldException ignored) {
            // Containers without this optional viewer count are checked against live menus.
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalArgumentException("The container's viewer state is unavailable", inaccessible);
        }
        // Some native IInventory containers roll pending loot when their capability is requested.
        IItemHandler handler = tile instanceof IInventory ? null
                : tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        for (EntityPlayer player : world.playerEntities) {
            if (player.openContainer == player.inventoryContainer) {
                continue;
            }
            String menuClass = player.openContainer.getClass().getName();
            if ("rustic.common.tileentity.ContainerBarrel".equals(menuClass)
                    || "rustic.common.tileentity.ContainerCabinet".equals(menuClass)) {
                try {
                    if (player.openContainer.getClass().getMethod("getTile").invoke(player.openContainer) == tile) {
                        return true;
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalArgumentException("The active Rustic menu owner is unavailable", failure);
                }
            }
            for (Slot slot : player.openContainer.inventorySlots) {
                if (slot.inventory == tile || handler != null && slot instanceof SlotItemHandler
                        && ((SlotItemHandler) slot).getItemHandler() == handler) {
                    return true;
                }
            }
            if (tile instanceof IInventory && player.openContainer instanceof ContainerChest) {
                IInventory inventory = ((ContainerChest) player.openContainer).getLowerChestInventory();
                if (inventory instanceof InventoryLargeChest
                        && ((InventoryLargeChest) inventory).isPartOfLargeChest((IInventory) tile)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void register(WorldServer world, List<TileEntity> tiles) {
        List<NBTTagCompound> backups = new ArrayList<>(tiles.size());
        NBTTagCompound entry = new NBTTagCompound();
        UUID id = UUID.randomUUID();
        BlockPos anchor = tiles.get(0).getPos().toImmutable();
        entry.setInteger("Version", 1);
        entry.setUniqueId("Id", id);
        entry.setLong("Anchor", anchor.toLong());
        NBTTagList sources = new NBTTagList();
        for (TileEntity tile : tiles) {
            InventoryAdapter adapter = requireAdapter(tile);
            NBTTagCompound source = tile.writeToNBT(new NBTTagCompound()).copy();
            backups.add(source.copy());
            NBTTagCompound part = new NBTTagCompound();
            part.setLong("Position", tile.getPos().toLong());
            part.setString("Block", world.getBlockState(tile.getPos()).getBlock().getRegistryName().toString());
            part.setString("Adapter", adapter.id());
            part.setTag("Source", source);
            IInventory physical = adapter.physical(tile);
            if (adapter.hasLootTable(tile)) {
                ChestLootData.createGenerated(part, () -> {}, physical.getSizeInventory());
            } else {
                ChestLootData.createFixed(part, () -> {}, copy(physical));
            }
            sources.appendTag(part);
        }
        entry.setTag("Parts", sources);
        ContainerChunkData data = data(world, anchor);
        data.put(id, entry);
        try {
            for (TileEntity tile : tiles) {
                InventoryAdapter adapter = requireAdapter(tile);
                adapter.seal(tile);
                if (adapter.hasLootTable(tile) || !adapter.physical(tile).isEmpty()) {
                    throw new IllegalStateException("The original inventory could not be emptied safely");
                }
                NBTTagCompound marker = new NBTTagCompound();
                marker.setInteger("Version", 1);
                marker.setUniqueId("Id", id);
                marker.setLong("Anchor", anchor.toLong());
                marker.setLong("Position", tile.getPos().toLong());
                marker.setInteger("Dimension", world.provider.getDimension());
                tile.getTileData().removeTag(PENDING);
                tile.getTileData().setTag(MARKER, marker);
                tile.markDirty();
            }
        } catch (RuntimeException | LinkageError failure) {
            for (int index = 0; index < tiles.size(); index++) {
                tiles.get(index).readFromNBT(backups.get(index));
                tiles.get(index).markDirty();
            }
            data.remove(id);
            throw failure;
        }
        Record registered = data.get(id);
        Settings.debug("Registered container {} at {} in dimension {} with adapter {}, {} part(s)",
                id, anchor, world.provider.getDimension(), requireAdapter(tiles.get(0)).id(), tiles.size());
        for (TileEntity tile : tiles) {
            PlayerChunkMapEntry watching = world.getPlayerChunkMap().getEntry(tile.getPos().getX() >> 4, tile.getPos().getZ() >> 4);
            if (watching != null) {
                for (EntityPlayerMP viewer : watching.getWatchingPlayers()) {
                    registered.sync(viewer);
                }
            }
        }
    }

    public static boolean open(EntityPlayerMP player, BlockPos pos) {
        WorldServer world = player.getServerWorld();
        Record record = record(world, pos);
        if (record == null || record.interacting || !record.canOpen(player)) {
            Settings.debug("Denied private open at {} for player {} (locked, busy or unavailable)", pos, player.getUniqueID());
            player.sendStatusMessage(new TextComponentString("This lootr inventory is unavailable or locked."), true);
            return false;
        }
        try {
            List<IInventory> inventories = new ArrayList<>();
            for (Part part : record.parts) {
                TileEntity tile = world.getTileEntity(part.pos);
                InventoryAdapter adapter = requireAdapter(tile);
                IInventory physical = adapter.physical(tile);
                IInventory inventory;
                if (part.loot.isGenerated()) {
                    inventory = part.loot.openGenerated(world, player, TITLE, record::canOpen,
                            physical::openInventory, physical::closeInventory,
                            destination -> part.fill(world, player, adapter, destination));
                } else {
                    inventory = part.loot.open(world, player, TITLE, record::canOpen,
                            physical::openInventory, physical::closeInventory);
                }
                inventories.add(inventory);
            }
            record.tag.setBoolean("MimicConsumed", true);
            record.markDirty.run();
            player.displayGUIChest(new PrivateMenu(record, inventories, player));
            record.sync(player);
            Settings.debug("Opened private inventory {} at {} for player {}", record.id, pos, player.getUniqueID());
            return true;
        } catch (RuntimeException failure) {
            Lootr4RLCraft.LOGGER.error("Could not open private container at {}", pos, failure);
            player.sendStatusMessage(new TextComponentString("Could not generate this inventory; its saved state was preserved."), true);
            return false;
        }
    }

    public static boolean canOpen(EntityPlayerMP player, BlockPos pos) {
        Record record = record(player.getServerWorld(), pos);
        return record != null && record.canOpen(player);
    }

    public static boolean canBreak(EntityPlayerMP player, BlockPos pos) {
        Record record = record(player.getServerWorld(), pos);
        return record != null && record.authorized(player);
    }

    @Nullable
    public static Interaction prepareInteraction(EntityPlayerMP player, BlockPos pos) {
        Record record = record(player.getServerWorld(), pos);
        if (record == null || !record.authorized(player) || record.interacting
                || record.tag.getBoolean("MimicConsumed") || !Loader.isModLoaded("artifacts")) {
            return null;
        }
        WorldServer world = player.getServerWorld();
        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof net.minecraft.tileentity.TileEntityChest)) {
            return null;
        }
        for (Part part : record.parts) {
            if (part.pos.equals(pos) && part.loot.isGenerated()) {
                record.interacting = true;
                try {
                    return new Interaction(world, player, record, part, tile, requireAdapter(tile));
                } catch (RuntimeException failure) {
                    record.interacting = false;
                    throw failure;
                }
            }
        }
        return null;
    }

    public static void collectViews(EntityPlayerMP viewer, Chunk chunk, Map<BlockPos, Boolean> views,
            Map<BlockPos, Boolean> neighboring) {
        for (TileEntity tile : chunk.getTileEntityMap().values()) {
            if (!isManaged(tile)) {
                continue;
            }
            Record record = record(viewer.getServerWorld(), tile.getPos());
            if (record == null || !record.matches(viewer.getServerWorld())) {
                continue;
            }
            boolean opened = record.opened(viewer);
            views.put(tile.getPos(), opened);
            for (Part part : record.parts) {
                if (part.pos.getX() >> 4 != chunk.x || part.pos.getZ() >> 4 != chunk.z) {
                    PlayerChunkMapEntry watched = viewer.getServerWorld().getPlayerChunkMap()
                            .getEntry(part.pos.getX() >> 4, part.pos.getZ() >> 4);
                    if (watched != null && watched.getWatchingPlayers().contains(viewer)) {
                        neighboring.put(part.pos, opened);
                    }
                }
            }
        }
    }

    @Nullable
    static Record record(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity tile = world.getTileEntity(pos);
        if (!isManaged(tile)) {
            return null;
        }
        NBTTagCompound marker = tile.getTileData().getCompoundTag(MARKER);
        if (marker.getInteger("Version") != 1 || !marker.hasUniqueId("Id")
                || marker.getLong("Position") != pos.toLong() || marker.getInteger("Dimension") != world.provider.getDimension()) {
            return null;
        }
        BlockPos anchor = BlockPos.fromLong(marker.getLong("Anchor"));
        Chunk chunk = world.getChunkProvider().getLoadedChunk(anchor.getX() >> 4, anchor.getZ() >> 4);
        if (chunk == null || ContainerChunkData.CAPABILITY == null) {
            return null;
        }
        ContainerChunkData data = chunk.getCapability(ContainerChunkData.CAPABILITY, null);
        if (data == null) {
            return null;
        }
        Record record = data.get(marker.getUniqueId("Id"));
        return record != null && record.parts.stream().anyMatch(part -> part.pos.equals(pos)) ? record : null;
    }

    private static ContainerChunkData data(WorldServer world, BlockPos pos) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
        ContainerChunkData data = chunk == null || ContainerChunkData.CAPABILITY == null
                ? null : chunk.getCapability(ContainerChunkData.CAPABILITY, null);
        if (data == null) {
            throw new IllegalArgumentException("The server's private chunk storage is unavailable.");
        }
        return data;
    }

    private static InventoryAdapter requireAdapter(@Nullable TileEntity tile) {
        InventoryAdapter adapter = tile == null ? null : ContainerAdapters.findGenerated(tile);
        if (adapter == null) {
            throw new IllegalArgumentException("This block does not expose a supported item inventory.");
        }
        return adapter;
    }

    private static void requireLoaded(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            throw new IllegalArgumentException("The target and connected chunks must already be loaded.");
        }
    }

    static NonNullList<ItemStack> copy(IInventory inventory) {
        NonNullList<ItemStack> copy = NonNullList.withSize(inventory.getSizeInventory(), ItemStack.EMPTY);
        for (int slot = 0; slot < copy.size(); slot++) {
            copy.set(slot, inventory.getStackInSlot(slot).copy());
        }
        return copy;
    }

    static final class Record {
        private final NBTTagCompound tag;
        private final UUID id;
        private final Runnable markDirty;
        private final List<Part> parts = new ArrayList<>();
        private boolean interacting;

        private Record(NBTTagCompound tag, Runnable markDirty) {
            this.tag = tag;
            id = tag.getUniqueId("Id");
            this.markDirty = markDirty;
            NBTTagList list = tag.getTagList("Parts", 10);
            for (int index = 0; index < list.tagCount(); index++) {
                parts.add(new Part(list.getCompoundTagAt(index), markDirty));
            }
        }

        static Record read(NBTTagCompound tag, Runnable markDirty) {
            if (tag.getInteger("Version") != 1 || !tag.hasUniqueId("Id") || !tag.hasKey("Parts", 9)
                    || tag.getTagList("Parts", 10).tagCount() == 0) {
                throw new IllegalStateException("Invalid or unsupported private container record");
            }
            return new Record(tag, markDirty);
        }

        private boolean matches(WorldServer world) {
            for (Part part : parts) {
                if (!world.isBlockLoaded(part.pos)) {
                    return false;
                }
                TileEntity tile = world.getTileEntity(part.pos);
                InventoryAdapter adapter = tile == null ? null : ContainerAdapters.findGenerated(tile);
                NBTTagCompound marker = tile == null ? new NBTTagCompound() : tile.getTileData().getCompoundTag(MARKER);
                net.minecraft.util.ResourceLocation tileId = tile == null ? null : TileEntity.getKey(tile.getClass());
                if (tile == null || tile.isInvalid() || !isManaged(tile)
                        || !id.equals(marker.getUniqueId("Id")) || marker.getInteger("Version") != 1
                        || marker.getLong("Position") != part.pos.toLong()
                        || marker.getInteger("Dimension") != world.provider.getDimension()
                        || marker.getLong("Anchor") != tag.getLong("Anchor")
                        || tileId == null || !tileId.toString().equals(part.tag.getCompoundTag("Source").getString("id"))
                        || adapter == null || !part.adapter.equals(adapter.id())) {
                    return false;
                }
                List<TileEntity> nativeParts;
                try {
                    nativeParts = adapter.parts(world, part.pos);
                } catch (IllegalArgumentException unavailable) {
                    return false;
                }
                if (nativeParts.size() != parts.size()
                        || nativeParts.stream().anyMatch(nativePart -> parts.stream().noneMatch(
                                savedPart -> savedPart.pos.equals(nativePart.getPos())))) {
                    return false;
                }
            }
            return true;
        }

        private boolean canOpen(EntityPlayer player) {
            if (!authorized(player)) {
                return false;
            }
            WorldServer world = (WorldServer) player.world;
            for (Part part : parts) {
                if (world.getBlockState(part.pos).getBlock() instanceof BlockChest
                        && ((BlockChest) world.getBlockState(part.pos).getBlock()).getLockableContainer(world, part.pos) == null) {
                    return false;
                }
            }
            return true;
        }

        private boolean authorized(EntityPlayer player) {
            if (!Settings.privateLootEnabled || !(player instanceof EntityPlayerMP) || player instanceof FakePlayer || player.isSpectator()
                    || !(player.world instanceof WorldServer) || !matches((WorldServer) player.world)) {
                return false;
            }
            WorldServer world = (WorldServer) player.world;
            for (Part part : parts) {
                TileEntity tile = world.getTileEntity(part.pos);
                if (player.getDistanceSq(part.pos) > 64.0D || !LockAccess.canOpen(world, part.pos)) {
                    return false;
                }
                if (tile instanceof ILockableContainer) {
                    ILockableContainer lockable = (ILockableContainer) tile;
                    if (lockable.isLocked() && !player.canOpen(lockable.getLockCode())) {
                        return false;
                    }
                }
            }
            return true;
        }

        private int size() {
            return parts.stream().mapToInt(part -> part.loot.getSize()).sum();
        }

        private boolean opened(EntityPlayerMP player) {
            return parts.stream().allMatch(part -> part.loot.hasOpened(player));
        }

        private void sync(EntityPlayerMP viewer) {
            for (Part part : parts) {
                ChestViews.send(viewer, part.pos, opened(viewer));
            }
        }
    }

    /** Temporarily exposes the original pending flag only during the native first-use Mimic event. */
    public static final class Interaction {
        private final WorldServer world;
        private final EntityPlayerMP player;
        private final Record record;
        private final Part part;
        private final TileEntity tile;
        private final InventoryAdapter adapter;
        private final NonNullList<ItemStack> automationBuffer;
        private final NBTTagCompound originalState;
        private boolean finished;

        private Interaction(WorldServer world, EntityPlayerMP player, Record record, Part part,
                TileEntity tile, InventoryAdapter adapter) {
            this.world = world;
            this.player = player;
            this.record = record;
            this.part = part;
            this.tile = tile;
            this.adapter = adapter;
            originalState = tile.writeToNBT(new NBTTagCompound()).copy();
            IInventory physical = adapter.physical(tile);
            automationBuffer = copy(physical);
            try {
                physical.clear();
                adapter.restoreLootTable(tile, part.tag.getCompoundTag("Source"));
            } catch (RuntimeException failure) {
                tile.readFromNBT(originalState.copy());
                tile.markDirty();
                throw failure;
            }
        }

        public void finish(boolean canceled) {
            if (finished) {
                return;
            }
            finished = true;
            try {
                if (world.getTileEntity(part.pos) != tile) {
                    InventoryBasic buffer = new InventoryBasic("buffer", false, automationBuffer.size());
                    for (int slot = 0; slot < automationBuffer.size(); slot++) {
                        buffer.setInventorySlotContents(slot, automationBuffer.get(slot));
                    }
                    InventoryHelper.dropInventoryItems(world, part.pos, buffer);
                    record.tag.setBoolean("MimicConsumed", true);
                    removeDestroyedPart();
                    return;
                }
                boolean consumed = !adapter.hasLootTable(tile);
                if (consumed) {
                    NBTTagCompound prepared = ItemStackHelper.saveAllItems(new NBTTagCompound(), copy(adapter.physical(tile)));
                    prepared.setUniqueId("Player", player.getUniqueID());
                    part.tag.setTag("Prepared", prepared.copy());
                }
                adapter.seal(tile);
                IInventory physical = adapter.physical(tile);
                for (int slot = 0; slot < automationBuffer.size(); slot++) {
                    physical.setInventorySlotContents(slot, automationBuffer.get(slot));
                }
                // A canceled item action or refused break can leave the original native roll untouched.
                // Normal private GUI activation consumes the opportunity in open().
                record.tag.setBoolean("MimicConsumed", consumed);
                tile.markDirty();
            } catch (RuntimeException failure) {
                if (world.getTileEntity(part.pos) == tile) {
                    tile.readFromNBT(originalState.copy());
                    tile.markDirty();
                }
                throw failure;
            } finally {
                record.interacting = false;
                record.markDirty.run();
            }
        }

        private void removeDestroyedPart() {
            NBTTagList stored = record.tag.getTagList("Parts", 10);
            int index = record.parts.indexOf(part);
            stored.removeTag(index);
            record.parts.remove(index);
            if (record.parts.isEmpty()) {
                data(world, BlockPos.fromLong(record.tag.getLong("Anchor"))).remove(record.id);
                return;
            }
            BlockPos previousAnchor = BlockPos.fromLong(record.tag.getLong("Anchor"));
            BlockPos nextAnchor = record.parts.get(0).pos;
            if (previousAnchor.equals(nextAnchor)) {
                return;
            }
            ContainerChunkData previousData = data(world, previousAnchor);
            ContainerChunkData nextData = data(world, nextAnchor);
            Record survivor = record;
            if (previousData == nextData) {
                record.tag.setLong("Anchor", nextAnchor.toLong());
            } else {
                NBTTagCompound migrated = record.tag.copy();
                migrated.setLong("Anchor", nextAnchor.toLong());
                nextData.put(record.id, migrated);
                survivor = nextData.get(record.id);
            }
            for (Part remaining : record.parts) {
                TileEntity survivingTile = world.getTileEntity(remaining.pos);
                survivingTile.getTileData().getCompoundTag(MARKER).setLong("Anchor", nextAnchor.toLong());
                survivingTile.markDirty();
            }
            if (previousData != nextData) {
                previousData.remove(record.id);
            }
            for (Part remaining : record.parts) {
                PlayerChunkMapEntry watched = world.getPlayerChunkMap().getEntry(
                        remaining.pos.getX() >> 4, remaining.pos.getZ() >> 4);
                if (watched != null && survivor != null) {
                    for (EntityPlayerMP viewer : watched.getWatchingPlayers()) {
                        survivor.sync(viewer);
                    }
                }
            }
        }
    }

    private static final class Part {
        private final NBTTagCompound tag;
        private final BlockPos pos;
        private final String adapter;
        private final ChestLootData loot;

        private Part(NBTTagCompound tag, Runnable markDirty) {
            this.tag = tag;
            pos = BlockPos.fromLong(tag.getLong("Position"));
            adapter = tag.getString("Adapter");
            loot = ChestLootData.read(tag, markDirty);
            if (loot == null) {
                throw new IllegalStateException("Private container part has no inventory record");
            }
        }

        private void fill(WorldServer world, EntityPlayerMP player, InventoryAdapter adapter, IInventory destination) {
            NBTTagCompound prepared = tag.getCompoundTag("Prepared");
            if (prepared.hasUniqueId("Player") && prepared.getUniqueId("Player").equals(player.getUniqueID())) {
                NonNullList<ItemStack> contents = NonNullList.withSize(loot.getSize(), ItemStack.EMPTY);
                ItemStackHelper.loadAllItems(prepared, contents);
                for (int slot = 0; slot < contents.size(); slot++) {
                    destination.setInventorySlotContents(slot, contents.get(slot).copy());
                }
            } else {
                adapter.generate(world, player, tag.getCompoundTag("Source"), destination);
            }
        }
    }

    private static final class PrivateMenu extends InventoryLargeChest {
        private final Record record;
        private final List<IInventory> inventories;
        private final EntityPlayerMP owner;
        private final int size;

        private PrivateMenu(Record record, List<IInventory> inventories, EntityPlayerMP owner) {
            super("lootr", new EmptyLockable(), null);
            this.record = record;
            this.inventories = inventories;
            this.owner = owner;
            size = (record.size() + 8) / 9 * 9;
        }

        private int partIndex(int slot) {
            int limit = 0;
            for (int index = 0; index < inventories.size(); index++) {
                limit += inventories.get(index).getSizeInventory();
                if (slot < limit) {
                    return index;
                }
            }
            return -1;
        }

        private int localSlot(int slot, int index) {
            for (int part = 0; part < index; part++) {
                slot -= inventories.get(part).getSizeInventory();
            }
            return slot;
        }

        private int slotLimit(int slot) {
            int index = partIndex(slot);
            if (index < 0) {
                return 0;
            }
            TileEntity tile = owner.world.getTileEntity(record.parts.get(index).pos);
            return tile == null ? 0 : requireAdapter(tile).slotLimit(tile, localSlot(slot, index));
        }

        @Override public int getSizeInventory() { return size; }
        @Override public String getName() { return "lootr"; }
        @Override public boolean hasCustomName() { return true; }
        @Override public ITextComponent getDisplayName() { return TITLE; }
        @Override public boolean isLocked() { return false; }
        @Override public LockCode getLockCode() { return LockCode.EMPTY_CODE; }
        @Override public void setLockCode(LockCode code) {}
        @Override public String getGuiID() { return "minecraft:chest"; }

        @Override
        public ItemStack getStackInSlot(int slot) {
            int index = partIndex(slot);
            return index < 0 ? ItemStack.EMPTY : inventories.get(index).getStackInSlot(localSlot(slot, index));
        }

        @Override
        public ItemStack decrStackSize(int slot, int count) {
            int index = partIndex(slot);
            return index < 0 ? ItemStack.EMPTY : inventories.get(index).decrStackSize(localSlot(slot, index), count);
        }

        @Override
        public ItemStack removeStackFromSlot(int slot) {
            int index = partIndex(slot);
            return index < 0 ? ItemStack.EMPTY : inventories.get(index).removeStackFromSlot(localSlot(slot, index));
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            int index = partIndex(slot);
            if (index >= 0) {
                inventories.get(index).setInventorySlotContents(localSlot(slot, index), stack);
            }
        }

        @Override
        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            int index = partIndex(slot);
            if (index < 0) {
                return false;
            }
            TileEntity tile = owner.world.getTileEntity(record.parts.get(index).pos);
            return tile != null && requireAdapter(tile).valid(tile, localSlot(slot, index), stack);
        }

        @Override public boolean isEmpty() { return inventories.stream().allMatch(IInventory::isEmpty); }
        @Override public boolean isUsableByPlayer(EntityPlayer player) { return player == owner && record.canOpen(player); }
        @Override public void openInventory(EntityPlayer player) { inventories.forEach(inventory -> inventory.openInventory(player)); }
        @Override public void closeInventory(EntityPlayer player) { inventories.forEach(inventory -> inventory.closeInventory(player)); }
        @Override public void clear() { inventories.forEach(IInventory::clear); }

        @Override
        public void markDirty() {
            if (inventories != null) {
                inventories.forEach(IInventory::markDirty);
            }
        }

        @Override
        public boolean isPartOfLargeChest(IInventory inventory) {
            return record.parts.stream().anyMatch(part -> owner.world.getTileEntity(part.pos) == inventory);
        }

        @Override
        public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
            ContainerChest container = new GuardedChestContainer(inventory, this, player);
            for (int index = 0; index < size; index++) {
                Slot original = container.inventorySlots.get(index);
                final int inventorySlot = index;
                Slot limited = new Slot(this, index, original.xPos, original.yPos) {
                    @Override
                    public boolean isItemValid(ItemStack stack) {
                        return isItemValidForSlot(inventorySlot, stack);
                    }

                    @Override
                    public int getSlotStackLimit() {
                        return slotLimit(inventorySlot);
                    }
                };
                limited.slotNumber = original.slotNumber;
                container.inventorySlots.set(index, limited);
            }
            return container;
        }
    }

    private static final class EmptyLockable extends InventoryBasic implements ILockableContainer {
        private EmptyLockable() { super("lootr", true, 0); }
        @Override public boolean isLocked() { return false; }
        @Override public LockCode getLockCode() { return LockCode.EMPTY_CODE; }
        @Override public void setLockCode(LockCode code) {}
        @Override public String getGuiID() { return "minecraft:chest"; }
        @Override public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
            return new ContainerChest(inventory, this, player);
        }
    }
}
