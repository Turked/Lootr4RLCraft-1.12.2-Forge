package com.mechgaming.lootr4rlcraft.container;

import com.mechgaming.lootr4rlcraft.loot.PlayerCheckpoints;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Enforces native ownership and slot rules on the server when the player actually transfers an item. */
public final class GuardedChestContainer extends ContainerChest {
    public GuardedChestContainer(InventoryPlayer inventory, IInventory contents, EntityPlayer owner) {
        super(inventory, contents, owner);
        PlayerCheckpoints.openedMenu(owner);
    }

    @Override
    public ItemStack slotClick(int slot, int button, ClickType click, EntityPlayer player) {
        if (!canInteractWith(player) || slot >= inventorySlots.size()) {
            return ItemStack.EMPTY;
        }
        if (click == ClickType.THROW || (slot == -999 && click == ClickType.PICKUP)) {
            if (player instanceof EntityPlayerMP) ((EntityPlayerMP) player).sendContainerToPlayer(this);
            return ItemStack.EMPTY;
        }
        ItemStack result = super.slotClick(slot, button, click, player);
        PlayerCheckpoints.captureCursor(player);
        return result;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        PlayerCheckpoints.closeMenuCursor(player);
        super.onContainerClosed(player);
        PlayerCheckpoints.captureCursor(player);
    }

    @Override
    protected boolean mergeItemStack(ItemStack stack, int start, int end, boolean reverse) {
        if (start != 0 || end != getLowerChestInventory().getSizeInventory() || reverse) {
            return super.mergeItemStack(stack, start, end, reverse);
        }
        // Vanilla's occupied-slot pass ignores isItemValid. Check both passes, since a
        // generated item can occupy a slot that its storage implementation forbids refilling.
        boolean changed = false;
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int index = start; index < end && !stack.isEmpty(); index++) {
                Slot slot = inventorySlots.get(index);
                ItemStack existing = slot.getStack();
                if (existing.isEmpty() != (pass == 1) || !slot.isItemValid(stack)) {
                    continue;
                }
                if (!existing.isEmpty() && (!stack.isStackable() || !ItemStack.areItemsEqual(existing, stack)
                        || !ItemStack.areItemStackTagsEqual(existing, stack))) {
                    continue;
                }
                int limit = Math.min(stack.getMaxStackSize(), slot.getItemStackLimit(stack));
                int moved = Math.min(stack.getCount(), limit - existing.getCount());
                if (moved <= 0) {
                    continue;
                }
                if (existing.isEmpty()) {
                    slot.putStack(stack.splitStack(moved));
                } else {
                    existing.grow(moved);
                    stack.shrink(moved);
                    slot.onSlotChanged();
                }
                changed = true;
            }
        }
        return changed;
    }
}
