package com.mechgaming.lootr4rlcraft.adapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;

/** Exposes all slots of a capability inventory without materializing its pending loot. */
final class ItemHandlerInventory implements IInventory {
    private final TileEntity tile;
    private final IItemHandler handler;

    ItemHandlerInventory(TileEntity tile, IItemHandler handler) {
        this.tile = tile;
        this.handler = handler;
    }

    @Override
    public int getSizeInventory() {
        return handler.getSlots();
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack decrStackSize(int slot, int count) {
        ItemStack result = handler.extractItem(slot, count, false);
        markDirty();
        return result;
    }

    @Override
    public ItemStack removeStackFromSlot(int slot) {
        return decrStackSize(slot, Integer.MAX_VALUE);
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (!(handler instanceof IItemHandlerModifiable)) {
            throw new IllegalArgumentException("This inventory does not expose a direct slot setter");
        }
        ((IItemHandlerModifiable) handler).setStackInSlot(slot, stack);
        markDirty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
        tile.markDirty();
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player) {
        return tile.getWorld() == player.world && !tile.isInvalid()
                && tile.getWorld().getTileEntity(tile.getPos()) == tile
                && player.getDistanceSq(tile.getPos()) <= 64.0D;
    }

    @Override
    public void openInventory(EntityPlayer player) {}

    @Override
    public void closeInventory(EntityPlayer player) {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (!handler.isItemValid(slot, stack)) {
            return false;
        }
        // Older Charm handlers enforce their whitelist/blacklist through this protected hook.
        // Calling it also avoids mistaking an occupied physical automation slot for an invalid item.
        if (handler instanceof ItemStackHandler) {
            try {
                Method limit = stackLimitMethod(handler.getClass());
                return ((Number) limit.invoke(handler, slot, stack)).intValue() > 0;
            } catch (ReflectiveOperationException error) {
                throw reflectionFailure("Could not inspect the inventory's slot rule", error);
            }
        }
        return handler.getSlotLimit(slot) > 0;
    }

    int slotLimit(int slot) {
        return handler.getSlotLimit(slot);
    }

    private static Method stackLimitMethod(Class<?> type) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("getStackLimit", int.class, ItemStack.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // The first implementation in the hierarchy supplies the actual container rule.
            }
        }
        throw new NoSuchMethodException("getStackLimit");
    }

    private static IllegalStateException reflectionFailure(String message, ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
        return new IllegalStateException(message, cause);
    }

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {}

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (handler instanceof IItemHandlerModifiable) {
                ((IItemHandlerModifiable) handler).setStackInSlot(slot, ItemStack.EMPTY);
            } else {
                handler.extractItem(slot, Integer.MAX_VALUE, false);
                if (!handler.getStackInSlot(slot).isEmpty()) {
                    throw new IllegalArgumentException("The inventory refused to release its generated items");
                }
            }
        }
        markDirty();
    }

    @Override
    public String getName() {
        return "lootr";
    }

    @Override
    public boolean hasCustomName() {
        return true;
    }

    @Override
    public ITextComponent getDisplayName() {
        return new TextComponentString(getName());
    }
}
