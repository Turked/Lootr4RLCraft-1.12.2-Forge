package com.mechgaming.lootr4rlcraft.loot;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/** Private slots and items held on the menu cursor are saved alongside the owner's vanilla inventory. */
public final class PlayerCheckpoints {
    private static final String ROOT = "lootr4rlcraft:checkpoints";
    private static final String CURSOR = "PrivateMenuCursor";
    private static final String PENDING = "RecoveredCursors";

    private static NBTTagCompound compound(NBTTagCompound parent, String key, boolean create) {
        if (parent.hasKey(key) && !parent.hasKey(key, NBT.TAG_COMPOUND)) {
            throw new IllegalStateException("Invalid player checkpoint compound " + key);
        }
        if (create && !parent.hasKey(key)) parent.setTag(key, new NBTTagCompound());
        return parent.getCompoundTag(key);
    }

    private static NBTTagCompound root(EntityPlayer player, boolean create) {
        NBTTagCompound saved = compound(player.getEntityData(), EntityPlayer.PERSISTED_NBT_TAG, create);
        return compound(saved, ROOT, create);
    }

    static NBTTagCompound records(EntityPlayer player, boolean create) {
        return compound(root(player, create), "Containers", create);
    }

    public static void captureCursor(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) return;
        ItemStack stack = player.inventory.getItemStack();
        if (stack.isEmpty()) root(player, false).removeTag(CURSOR);
        else root(player, true).setTag(CURSOR, stack.writeToNBT(new NBTTagCompound()));
    }

    public static void closeMenuCursor(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP) || player.inventory.getItemStack().isEmpty()) return;
        captureCursor(player);
        player.inventory.setItemStack(ItemStack.EMPTY);
        restoreCursor((EntityPlayerMP) player);
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) restoreCursor((EntityPlayerMP) event.player);
    }

    public static void restoreCursor(EntityPlayerMP player) {
        NBTTagCompound state = root(player, false);
        NBTTagList saved = state.getTagList(PENDING, NBT.TAG_COMPOUND);
        if (state.hasKey(CURSOR, NBT.TAG_COMPOUND)) {
            saved.appendTag(state.getCompoundTag(CURSOR));
            state.removeTag(CURSOR);
        }
        NBTTagList overflow = new NBTTagList();
        for (int i = 0; i < saved.tagCount(); i++) {
            ItemStack stack = new ItemStack(saved.getCompoundTagAt(i));
            // Pick a real destination before calling add; creative's normal full-inventory path discards overflow.
            while (!stack.isEmpty()) {
                int slot = player.inventory.storeItemStack(stack);
                if (slot < 0) slot = player.inventory.getFirstEmptyStack();
                if (slot < 0) break;
                int space = Math.min(stack.getMaxStackSize(), player.inventory.getInventoryStackLimit())
                        - player.inventory.getStackInSlot(slot).getCount();
                if (space <= 0) break;
                ItemStack part = stack.splitStack(Math.min(space, stack.getCount()));
                player.inventory.add(slot, part);
                if (!part.isEmpty()) {
                    stack.grow(part.getCount());
                    break;
                }
            }
            if (!stack.isEmpty()) overflow.appendTag(stack.writeToNBT(new NBTTagCompound()));
        }
        if (overflow.tagCount() == 0) state.removeTag(PENDING);
        else state.setTag(PENDING, overflow);
    }

    /** A player with a completely full inventory gets the saved overflow on their next private menu cursor. */
    public static void openedMenu(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP) || !player.inventory.getItemStack().isEmpty()) return;
        NBTTagCompound state = root(player, false);
        NBTTagList overflow = state.getTagList(PENDING, NBT.TAG_COMPOUND);
        if (overflow.tagCount() != 0) {
            player.inventory.setItemStack(new ItemStack(overflow.getCompoundTagAt(0)));
            overflow.removeTag(0);
            if (overflow.tagCount() == 0) state.removeTag(PENDING);
            captureCursor(player);
            ((EntityPlayerMP) player).updateHeldItem();
        }
    }
}
