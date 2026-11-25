package com.mechgaming.lootr4rlcraft.adapter;

import com.mechgaming.lootr4rlcraft.chest.InstancedChestTile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.block.BlockChest;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ILockableContainer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Optional integrations use the container classes that are already installed in the pack. */
public final class ContainerAdapters {
    private static final InventoryAdapter NATIVE = new StorageAdapter("native", Kind.NATIVE);
    private static final InventoryAdapter CHARM = new StorageAdapter("charm", Kind.CHARM);
    private static final InventoryAdapter STORAGE = new StorageAdapter("storage", Kind.STORAGE);
    private static final InventoryAdapter GENERATED = new StorageAdapter("generated", Kind.GENERATED);

    private ContainerAdapters() {}

    /** New registrations need an audited storage implementation. Older generic records remain readable. */
    @Nullable
    public static InventoryAdapter forEnrollment(@Nullable TileEntity tile) {
        if (tile == null || tile instanceof InstancedChestTile) {
            return null;
        }
        if (tile instanceof TileEntityChest && tile.getBlockType() instanceof BlockChest
                && (tile.getClass() == TileEntityChest.class
                        || inherits(tile, "vazkii.quark.decoration.tile.TileCustomChest"))) {
            return NATIVE;
        }
        switch (tile.getClass().getName()) {
            case "rustic.common.tileentity.TileEntityBarrel":
            case "rustic.common.tileentity.TileEntityCabinet":
                return NATIVE;
            case "svenhjol.charm.crafting.tile.TileBarrel":
            case "svenhjol.charm.crafting.tile.TileBookshelfChest":
                return CHARM;
            case "svenhjol.charm.crafting.tile.TileCrate":
                // Charm uses exactly the same tile for its break-to-open sealed crate.
                // A private storage menu would bypass the sealed block's native behavior.
                Method sealed = method(tile.getBlockType().getClass(), "isSealedCrate");
                return sealed != null && Boolean.FALSE.equals(invoke(sealed, tile.getBlockType())) ? CHARM : null;
            case "noppes.vc.blocks.tiles.TileBarrel":
                return STORAGE;
            default:
                return null;
        }
    }

    public static boolean opensFrom(TileEntity tile, IBlockState state, EnumFacing face, EntityPlayer player) {
        if (player.isSneaking() && inherits(tile, "svenhjol.meson.MesonTileInventory")) {
            return false;
        }
        // Rustic cabinets open only from the door face, even if a partner is attached.
        if (!inherits(tile, "rustic.common.tileentity.TileEntityCabinet")) {
            return true;
        }
        for (net.minecraft.block.properties.IProperty<?> property : state.getPropertyKeys()) {
            if ("facing".equals(property.getName())) {
                return state.getValue(property) == face;
            }
        }
        return false;
    }

    @Nullable
    public static InventoryAdapter find(TileEntity tile) {
        if (tile == null || tile instanceof InstancedChestTile) {
            return null;
        }
        if (NATIVE.supports(tile)) {
            return NATIVE;
        }
        if (CHARM.supports(tile)) {
            return CHARM;
        }
        if (STORAGE.supports(tile)) {
            return STORAGE;
        }
        if (GENERATED.supports(tile) && GENERATED.hasLootTable(tile)) {
            return GENERATED;
        }
        return null;
    }

    /** Call only for a container positively identified by a structure-generation event or operator. */
    @Nullable
    public static InventoryAdapter findGenerated(TileEntity tile) {
        InventoryAdapter known = find(tile);
        if (known != null) {
            return known;
        }
        return tile != null && !(tile instanceof InstancedChestTile)
                && GENERATED.supports(tile) ? GENERATED : null;
    }

    private static boolean hasPhysicalInventory(TileEntity tile) {
        return tile instanceof IInventory || tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
    }

    private static boolean inherits(TileEntity tile, String name) {
        for (Class<?> type = tile.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Object invoke(Method method, Object instance, Object... arguments) {
        try {
            return method.invoke(instance, arguments);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getCause() : error;
            throw new IllegalStateException("Container API call failed: " + method.getName(), cause);
        }
    }

    private static Object call(Object instance, String name, Class<?>[] parameters, Object... arguments) {
        Method method = method(instance.getClass(), name, parameters);
        if (method == null) {
            throw new IllegalArgumentException("Installed container is missing its expected API: " + name);
        }
        return invoke(method, instance, arguments);
    }

    private static void requireLoaded(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos)) {
            throw new IllegalArgumentException("Every connected container chunk must already be loaded");
        }
    }

    private enum Kind {
        NATIVE, CHARM, STORAGE, GENERATED
    }

    private static final class StorageAdapter implements InventoryAdapter {
        private final String id;
        private final Kind kind;

        private StorageAdapter(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean supports(TileEntity tile) {
            switch (kind) {
                case NATIVE:
                    return tile instanceof TileEntityLockableLoot;
                case CHARM:
                    return inherits(tile, "svenhjol.meson.MesonTileInventory");
                case STORAGE:
                    return inherits(tile, "noppes.vc.blocks.tiles.TileBasicContainer");
                default:
                    return hasPhysicalInventory(tile) && supportsGenericSource(tile);
            }
        }

        private boolean supportsGenericSource(TileEntity tile) {
            NBTTagCompound source = tile.writeToNBT(new NBTTagCompound());
            return source.getString("lootTable").isEmpty() || !source.getString("LootTable").isEmpty()
                    || method(tile.getClass(), "fillWithLoot", EntityPlayer.class) != null
                    || method(tile.getClass(), "generateLoot", EntityPlayer.class) != null;
        }

        @Override
        public List<TileEntity> parts(WorldServer world, BlockPos pos) {
            requireLoaded(world, pos);
            TileEntity tile = world.getTileEntity(pos);
            if (tile == null || !supports(tile)) {
                throw new IllegalArgumentException("The container no longer matches its adapter");
            }
            if (tile instanceof TileEntityChest && world.getBlockState(pos).getBlock() instanceof BlockChest) {
                return chestParts(world, pos, (TileEntityChest) tile);
            }
            if (inherits(tile, "rustic.common.tileentity.TileEntityCabinet")) {
                return cabinetParts(world, tile);
            }
            return Collections.singletonList(tile);
        }

        private List<TileEntity> chestParts(WorldServer world, BlockPos pos, TileEntityChest tile) {
            for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
                requireLoaded(world, pos.offset(facing));
            }
            ILockableContainer combined = ((BlockChest) world.getBlockState(pos).getBlock()).getContainer(world, pos, true);
            if (combined == null) {
                throw new IllegalArgumentException("The native chest cannot resolve its connected inventory");
            }
            List<TileEntity> parts = new ArrayList<>(2);
            parts.add(tile);
            if (combined instanceof InventoryLargeChest) {
                for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
                    TileEntity neighbor = world.getTileEntity(pos.offset(facing));
                    if (neighbor instanceof TileEntityChest && neighbor != tile
                            && ((InventoryLargeChest) combined).isPartOfLargeChest((IInventory) neighbor)) {
                        parts.add(neighbor);
                    }
                }
            }
            if (parts.size() > 2) {
                throw new IllegalArgumentException("The connected chest layout has more than two halves");
            }
            parts.sort(Comparator.comparingInt((TileEntity part) -> part.getPos().getX())
                    .thenComparingInt(part -> part.getPos().getZ()));
            return parts;
        }

        private List<TileEntity> cabinetParts(WorldServer world, TileEntity tile) {
            requireLoaded(world, tile.getPos().up());
            requireLoaded(world, tile.getPos().down());
            try {
                Class<?> cabinet = Class.forName("rustic.common.tileentity.TileEntityCabinet");
                Class<?> handlerType = Class.forName("rustic.common.inventory.DoubleCabinetItemHandler");
                Object handler = invoke(handlerType.getMethod("get", cabinet), null, tile);
                Object other = call(handler, "getOtherCabinet", new Class<?>[0]);
                if (!(other instanceof TileEntity)) {
                    return Collections.singletonList(tile);
                }
                List<TileEntity> parts = new ArrayList<>(2);
                parts.add((TileEntity) call(handler, "getCabinet", new Class<?>[] { boolean.class }, true));
                parts.add((TileEntity) call(handler, "getCabinet", new Class<?>[] { boolean.class }, false));
                return parts;
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("The installed Rustic cabinet API is unavailable", error);
            }
        }

        @Override
        public IInventory physical(TileEntity tile) {
            if (tile instanceof IInventory) {
                return (IInventory) tile;
            }
            if (inherits(tile, "svenhjol.meson.MesonTileInventory")) {
                Object handler = call(tile, "getInventory", new Class<?>[0]);
                if (handler instanceof IItemHandler) {
                    return new ItemHandlerInventory(tile, (IItemHandler) handler);
                }
            }
            IItemHandler handler = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (handler == null) {
                throw new IllegalArgumentException("The container does not expose an item inventory");
            }
            return new ItemHandlerInventory(tile, handler);
        }

        @Override
        public boolean hasLootTable(TileEntity tile) {
            if (tile instanceof TileEntityLockableLoot) {
                return ((TileEntityLockableLoot) tile).getLootTable() != null;
            }
            if (inherits(tile, "svenhjol.meson.MesonTileInventory")) {
                return Boolean.TRUE.equals(call(tile, "hasLootTable", new Class<?>[0]));
            }
            NBTTagCompound source = tile.writeToNBT(new NBTTagCompound());
            return !source.getString("LootTable").isEmpty() || !source.getString("lootTable").isEmpty();
        }

        @Override
        public void generate(WorldServer world, EntityPlayerMP player, NBTTagCompound source, IInventory destination) {
            TileEntity clone = TileEntity.create(world, source.copy());
            if (clone == null) {
                throw new IllegalArgumentException("The original container implementation could not be recreated");
            }
            // TileEntity.create only calls setWorldCreate, whose base implementation is empty in 1.12.
            clone.setWorld(world);
            if (!supports(clone)) {
                throw new IllegalArgumentException("The recreated container no longer matches its adapter");
            }
            if (clone instanceof TileEntityLockableLoot) {
                ((TileEntityLockableLoot) clone).fillWithLoot(player);
            } else if (inherits(clone, "svenhjol.meson.MesonTileInventory")) {
                neutralizeCharmCallbacks(clone);
                call(clone, "generateLoot", new Class<?>[] { EntityPlayer.class }, player);
            } else {
                Method generator = method(clone.getClass(), "fillWithLoot", EntityPlayer.class);
                if (generator == null) {
                    generator = method(clone.getClass(), "generateLoot", EntityPlayer.class);
                }
                if (generator != null) {
                    invoke(generator, clone, player);
                } else if (!source.getString("LootTable").isEmpty()) {
                    long seed = source.getLong("LootTableSeed");
                    LootContext context = new LootContext.Builder(world).withPlayer(player).withLuck(player.getLuck()).build();
                    world.getLootTableManager().getLootTableFromLocation(new ResourceLocation(source.getString("LootTable")))
                            .fillInventory(destination, seed == 0L ? new java.util.Random() : new java.util.Random(seed), context);
                    return;
                } else if (!source.getString("lootTable").isEmpty()) {
                    throw new IllegalArgumentException("The custom loot table has no supported native generation method");
                }
            }
            IInventory generated = physical(clone);
            if (generated.getSizeInventory() != destination.getSizeInventory()) {
                throw new IllegalArgumentException("The source container's slot count changed");
            }
            for (int slot = 0; slot < generated.getSizeInventory(); slot++) {
                destination.setInventorySlotContents(slot, generated.getStackInSlot(slot).copy());
            }
        }

        private void neutralizeCharmCallbacks(TileEntity clone) {
            IItemHandler original = (IItemHandler) call(clone, "getInventory", new Class<?>[0]);
            ItemStackHandler detached = new ItemStackHandler(original.getSlots());
            for (int slot = 0; slot < original.getSlots(); slot++) {
                detached.setStackInSlot(slot, original.getStackInSlot(slot).copy());
            }
            try {
                clone.getClass().getField("inventory").set(clone, detached);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("The installed Charm inventory cannot be detached safely", error);
            }
        }

        @Override
        public void seal(TileEntity tile) {
            setPendingLoot(tile, new NBTTagCompound());
            IInventory inventory = physical(tile);
            if (inventory instanceof ItemHandlerInventory) {
                inventory.clear();
            } else {
                // Varied Commodities implements clear() as a no-op. Use the actual slot setters.
                for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                    inventory.setInventorySlotContents(slot, ItemStack.EMPTY);
                }
            }
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) {
                    throw new IllegalArgumentException("The container refused to release its original source items");
                }
            }
            tile.markDirty();
        }

        @Override
        public void restoreLootTable(TileEntity tile, NBTTagCompound source) {
            setPendingLoot(tile, source);
            tile.markDirty();
        }

        private void setPendingLoot(TileEntity tile, NBTTagCompound source) {
            if (tile instanceof TileEntityLockableLoot) {
                String table = source.getString("LootTable");
                ((TileEntityLockableLoot) tile).setLootTable(table.isEmpty() ? null : new ResourceLocation(table),
                        source.getLong("LootTableSeed"));
            } else if (inherits(tile, "svenhjol.meson.MesonTileInventory")) {
                call(tile, "setLootTable", new Class<?>[] { String.class }, source.getString("lootTable"));
                call(tile, "setLootSize", new Class<?>[] { int.class }, source.getInteger("lootSize"));
            } else {
                NBTTagCompound live = tile.writeToNBT(new NBTTagCompound()).copy();
                live.removeTag("LootTable");
                live.removeTag("LootTableSeed");
                live.removeTag("lootTable");
                live.removeTag("lootSize");
                for (String key : new String[] { "LootTable", "LootTableSeed", "lootTable", "lootSize" }) {
                    if (source.hasKey(key)) {
                        live.setTag(key, source.getTag(key).copy());
                    }
                }
                tile.readFromNBT(live);
            }
        }

        @Override
        public boolean valid(TileEntity tile, int slot, ItemStack stack) {
            IInventory inventory = physical(tile);
            return slot >= 0 && slot < inventory.getSizeInventory() && inventory.isItemValidForSlot(slot, stack);
        }

        @Override
        public int slotLimit(TileEntity tile, int slot) {
            IInventory inventory = physical(tile);
            return inventory instanceof ItemHandlerInventory
                    ? ((ItemHandlerInventory) inventory).slotLimit(slot) : inventory.getInventoryStackLimit();
        }

        @Override
        public boolean eligibleFixed(TileEntity tile) {
            return supports(tile);
        }
    }
}
