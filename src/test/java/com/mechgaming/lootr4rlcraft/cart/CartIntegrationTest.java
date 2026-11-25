package com.mechgaming.lootr4rlcraft.cart;

import com.mechgaming.lootr4rlcraft.Settings;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

public final class CartIntegrationTest {
    private CartIntegrationTest() {}

    public static void verify(WorldServer world, EntityPlayerMP[] viewers, ResourceLocation table) {
        EntityMinecartChest ordinary = new EntityMinecartChest(world, 5, 72, 5);
        ordinary.setLootTable(table, 19L);
        new CartEvents((w, e) -> false).join(new EntityJoinWorldEvent(ordinary, world));
        require(ordinary.getLootTable().equals(table), "Loot-table cart without generation provenance was enrolled");

        EntityMinecartChest cart = new EntityMinecartChest(world, 6, 72, 5);
        cart.setLootTable(table, 91L);
        new CartEvents((w, e) -> e == cart).join(new EntityJoinWorldEvent(cart, world));
        require(cart.getLootTable() == null && cart.isEmpty(), "Native cart retained a public roll");
        require(world.spawnEntity(cart), "Could not spawn enrolled cart");
        CartRecords store = CartRecords.get(world);
        IInventory[] menus = new IInventory[viewers.length];
        for (int i = 0; i < viewers.length; i++) {
            EntityPlayerMP viewer = viewers[i];
            viewer.setPosition(6, 72, 5);
            menus[i] = store.open(cart, viewer);
            menus[i].openInventory(viewer);
            int count = 0;
            for (int slot = 0; slot < menus[i].getSizeInventory(); slot++) {
                ItemStack stack = menus[i].getStackInSlot(slot);
                if (stack.getItem() == Items.APPLE) count += stack.getCount();
            }
            require(count == 4, "Native cart loot table did not give four apples");
            menus[i].setInventorySlotContents(0, new ItemStack(Items.STICK, i + 1));
        }
        Settings.privateLootEnabled = false;
        require(!menus[0].isUsableByPlayer(viewers[0]), "Suspended private cart menu accepted a player");
        Settings.privateLootEnabled = true;
        cart.setPosition(22, 72, 5);
        require(!menus[0].isUsableByPlayer(viewers[0]), "Old menu stayed usable after cart moved out of range");
        for (int i = 0; i < viewers.length; i++) {
            require(menus[i].getStackInSlot(0).getCount() == i + 1, "Viewers shared cart inventory");
            menus[i].closeInventory(viewers[i]);
            viewers[i].setPosition(22, 72, 5);
        }
        NBTTagCompound entity = cart.writeToNBT(new NBTTagCompound());
        require(!entity.toString().contains("Players") && !entity.toString().contains("minecraft:stick"),
                "Private items leaked into entity serialization");
        CartRecords reload = new CartRecords("cart_test_reload");
        reload.readFromNBT(store.writeToNBT(new NBTTagCompound()).copy());
        for (int i = 0; i < viewers.length; i++) {
            require(reload.open(cart, viewers[i]).getStackInSlot(0).getCount() == i + 1,
                    "Saved minecart player allocation changed");
        }

        EntityMinecartChest fixed = new EntityMinecartChest(world, 22, 72, 4);
        fixed.setInventorySlotContents(12, new ItemStack(Items.EMERALD, 7));
        CartEvents.convert(world, fixed, true);
        require(fixed.isEmpty() && world.spawnEntity(fixed), "Fixed minecart was not safely sealed");
        require(store.open(fixed, viewers[0]).getStackInSlot(12).getCount() == 7, "Lost fixed minecart source slot");
        System.out.println("Verified minecart provenance gate, table and fixed sources, three private windows, movement, private NBT and record reload");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
