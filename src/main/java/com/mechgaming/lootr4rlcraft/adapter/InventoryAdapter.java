package com.mechgaming.lootr4rlcraft.adapter;

import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Accesses the installed container implementation while its block and renderer stay native. */
public interface InventoryAdapter {
    String id();

    boolean supports(TileEntity tile);

    List<TileEntity> parts(WorldServer world, BlockPos pos);

    IInventory physical(TileEntity tile);

    boolean hasLootTable(TileEntity tile);

    void generate(WorldServer world, EntityPlayerMP player, NBTTagCompound source, IInventory destination);

    void seal(TileEntity tile);

    void restoreLootTable(TileEntity tile, NBTTagCompound source);

    boolean valid(TileEntity tile, int slot, ItemStack stack);

    int slotLimit(TileEntity tile, int slot);

    boolean eligibleFixed(TileEntity tile);
}
