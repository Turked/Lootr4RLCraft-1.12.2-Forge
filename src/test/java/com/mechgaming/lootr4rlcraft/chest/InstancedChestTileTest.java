package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.GameRegistry;

public final class InstancedChestTileTest {
    private InstancedChestTileTest() {}

    public static void verify(ItemStack probe) {
        GameRegistry.registerTileEntity(InstancedChestTile.class, new ResourceLocation("lootr4rlcraft", "structure_chest"));
        InstancedChestTile tile = new InstancedChestTile();
        ResourceLocation table = new ResourceLocation("minecraft", "chests/simple_dungeon");
        NBTTagCompound diskTag = new NBTTagCompound();
        diskTag.setString("LootTable", table.toString());
        diskTag.setLong("LootTableSeed", 1234L);
        NBTTagCompound record = new NBTTagCompound();
        ChestLootData.create(record, () -> {}, table, 1234L, 27);
        diskTag.setTag("Lootr4RLCraftData", record);
        tile.readFromNBT(diskTag);

        tile.fillWithLoot(null);
        tile.setInventorySlotContents(0, probe);
        if (!table.equals(tile.getLootTable()) || !tile.getStackInSlot(0).isEmpty()
                || !tile.decrStackSize(0, 1).isEmpty() || tile.createUnSidedHandler().getSlots() != 0) {
            throw new AssertionError("Physical access consumed the pending loot or inserted shared items");
        }

        if (tile.getUpdateTag().hasKey("Lootr4RLCraftData")
                || !record.equals(tile.writeToNBT(new NBTTagCompound()).getCompoundTag("Lootr4RLCraftData"))) {
            throw new AssertionError("Private tile data leaked in the update tag or was omitted from disk NBT");
        }
    }
}
