package com.mechgaming.lootr4rlcraft.adapter;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityDispenser;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Exercises capability-only storage and detached native generation without optional mod dependencies. */
public final class ContainerAdaptersTest {
    private ContainerAdaptersTest() {}

    public static void verify() {
        verifyNoOpClearCannotRetainSourceLoot();
        verifyUnsupportedPendingAlgorithmIsPreserved();
        GameRegistry.registerTileEntity(HandlerTile.class, new ResourceLocation("lootr4rlcraft", "adapter_test"));
        HandlerTile tile = new HandlerTile();
        if (ContainerAdapters.find(tile) != null) {
            throw new AssertionError("An ambiguous ordinary inventory was automatically identified as loot");
        }
        InventoryAdapter generated = ContainerAdapters.findGenerated(tile);
        if (generated == null || generated.physical(tile).getSizeInventory() != 18
                || generated.slotLimit(tile, 0) != 16 || !generated.valid(tile, 0, new ItemStack(Items.BOOK))
                || generated.valid(tile, 0, new ItemStack(Items.APPLE))) {
            throw new AssertionError("A positive-generation capability inventory lost its slots or restrictions");
        }
        tile.lootTable = "minecraft:chests/simple_dungeon";
        tile.getTileData().setString("UnrelatedMarker", "keep");
        NBTTagCompound source = tile.writeToNBT(new NBTTagCompound()).copy();
        InventoryAdapter adapter = ContainerAdapters.find(tile);
        if (adapter == null || !adapter.hasLootTable(tile)) {
            throw new AssertionError("A capability inventory with a pending loot source was omitted");
        }
        InventoryBasic first = new InventoryBasic("test", false, 18);
        InventoryBasic second = new InventoryBasic("test", false, 18);
        adapter.generate(null, null, source, first);
        adapter.generate(null, null, source, second);
        if (first.getStackInSlot(17).getCount() != 3 || second.getStackInSlot(17).getCount() != 3
                || !tile.handler.getStackInSlot(17).isEmpty() || !adapter.hasLootTable(tile)
                || !source.equals(tile.writeToNBT(new NBTTagCompound()))) {
            throw new AssertionError("Detached native generation modified the physical source or lost its layout");
        }
        tile.handler.setStackInSlot(4, new ItemStack(Items.APPLE, 7));
        adapter.seal(tile);
        if (adapter.hasLootTable(tile) || !adapter.physical(tile).isEmpty()
                || !"keep".equals(tile.getTileData().getString("UnrelatedMarker"))) {
            throw new AssertionError("Sealing failed to clear source items or damaged unrelated tile data");
        }
        adapter.restoreLootTable(tile, source);
        if (!adapter.hasLootTable(tile) || !adapter.physical(tile).isEmpty()) {
            throw new AssertionError("Restoring the pending table also restored shared items");
        }
    }

    private static void verifyNoOpClearCannotRetainSourceLoot() {
        TileEntityDispenser tile = new TileEntityDispenser() {
            @Override
            public void clear() {
                // Some installed containers, including Varied Commodities, leave this method empty.
            }
        };
        tile.setInventorySlotContents(0, new ItemStack(Items.APPLE, 7));
        tile.setInventorySlotContents(8, new ItemStack(Items.BOOK, 3));
        InventoryAdapter adapter = ContainerAdapters.find(tile);
        if (adapter == null) {
            throw new AssertionError("The native loot inventory adapter is unavailable");
        }
        adapter.seal(tile);
        if (!tile.getStackInSlot(0).isEmpty() || !tile.getStackInSlot(8).isEmpty()) {
            throw new AssertionError("A no-op clear method left source items available to shared automation");
        }
    }

    private static void verifyUnsupportedPendingAlgorithmIsPreserved() {
        GameRegistry.registerTileEntity(UnsupportedPendingTile.class,
                new ResourceLocation("lootr4rlcraft", "unsupported_adapter_test"));
        UnsupportedPendingTile tile = new UnsupportedPendingTile();
        tile.handler.setStackInSlot(0, new ItemStack(Items.APPLE, 6));
        NBTTagCompound before = tile.writeToNBT(new NBTTagCompound()).copy();
        if (ContainerAdapters.find(tile) != null || ContainerAdapters.findGenerated(tile) != null
                || !before.equals(tile.writeToNBT(new NBTTagCompound()))) {
            throw new AssertionError("An unsupported pending loot algorithm was enrolled or modified");
        }
    }

    public static final class UnsupportedPendingTile extends TileEntity {
        private final ItemStackHandler handler = new ItemStackHandler(2);

        public UnsupportedPendingTile() {}

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound tag) {
            super.writeToNBT(tag);
            tag.setString("lootTable", "example:custom_algorithm");
            tag.setTag("inventory", handler.serializeNBT());
            return tag;
        }

        @Override
        public boolean hasCapability(Capability<?> capability, EnumFacing side) {
            return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, side);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getCapability(Capability<T> capability, EnumFacing side) {
            return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                    ? (T) handler : super.getCapability(capability, side);
        }
    }

    public static final class HandlerTile extends TileEntity {
        private final ItemStackHandler handler = new ItemStackHandler(18) {
            @Override
            public int getSlotLimit(int slot) {
                return slot == 0 ? 16 : 64;
            }

            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                return slot == 0 && stack.getItem() != Items.BOOK ? 0 : super.getStackLimit(slot, stack);
            }
        };
        private String lootTable = "";

        public HandlerTile() {}

        public void generateLoot(EntityPlayer player) {
            if (!lootTable.isEmpty()) {
                handler.setStackInSlot(17, new ItemStack(Items.BOOK, 3));
                lootTable = "";
            }
        }

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound tag) {
            super.writeToNBT(tag);
            tag.setTag("inventory", handler.serializeNBT());
            tag.setString("lootTable", lootTable);
            return tag;
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            super.readFromNBT(tag);
            handler.deserializeNBT(tag.getCompoundTag("inventory"));
            lootTable = tag.getString("lootTable");
        }

        @Override
        public boolean hasCapability(Capability<?> capability, EnumFacing side) {
            return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, side);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getCapability(Capability<T> capability, EnumFacing side) {
            return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                    ? (T) handler : super.getCapability(capability, side);
        }
    }
}
