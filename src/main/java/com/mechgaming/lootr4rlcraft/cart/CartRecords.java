package com.mechgaming.lootr4rlcraft.cart;

import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import com.mechgaming.lootr4rlcraft.Settings;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Random;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraftforge.common.util.FakePlayer;

/** The entity owns its physical buffer. Its UUID identifies private inventories across moves and dimensions. */
public final class CartRecords extends WorldSavedData {
    private static final String NAME = "lootr4rlcraft_carts";
    private NBTTagCompound carts = new NBTTagCompound();
    private final Map<EntityMinecartChest, WeakReference<ChestLootData>> loaded = new WeakHashMap<>();

    public CartRecords(String name) { super(name); }

    public static CartRecords get(WorldServer world) {
        MapStorage storage = world.getMapStorage();
        CartRecords data = (CartRecords) storage.getOrLoadData(CartRecords.class, NAME);
        if (data == null) {
            data = new CartRecords(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    boolean contains(UUID id) { return carts.hasKey(id.toString(), 10); }

    @Nullable
    ChestLootData find(EntityMinecartChest cart) {
        UUID id = cart.getUniqueID();
        if (!contains(id)) return null;
        WeakReference<ChestLootData> reference = loaded.get(cart);
        ChestLootData data = reference == null ? null : reference.get();
        if (data == null) {
            data = ChestLootData.read(carts.getCompoundTag(id.toString()), this::markDirty);
            if (data == null) throw new IllegalStateException("Missing loot data for " + id);
            loaded.put(cart, new WeakReference<>(data));
        }
        return data;
    }

    void enroll(EntityMinecartChest cart) {
        UUID id = cart.getUniqueID();
        if (contains(id)) throw new IllegalArgumentException("This minecart already has a private inventory record.");
        NBTTagCompound entry = new NBTTagCompound();
        ChestLootData loot;
        if (cart.getLootTable() != null) {
            NBTTagCompound source = cart.writeToNBT(new NBTTagCompound());
            entry.setString("Table", cart.getLootTable().toString());
            entry.setLong("Seed", source.getLong("LootTableSeed"));
            loot = ChestLootData.createGenerated(entry, this::markDirty, cart.getSizeInventory());
            // Remove the pending physical roll before hoppers, drag or destruction can access it.
            cart.setLootTable(null, 0L);
        } else {
            NonNullList<ItemStack> template = NonNullList.withSize(cart.getSizeInventory(), ItemStack.EMPTY);
            for (int slot = 0; slot < template.size(); slot++) template.set(slot, cart.getStackInSlot(slot).copy());
            loot = ChestLootData.createFixed(entry, this::markDirty, template);
            cart.clear();
        }
        carts.setTag(id.toString(), entry);
        loaded.put(cart, new WeakReference<>(loot));
        markDirty();
    }

    public IInventory open(EntityMinecartChest cart, EntityPlayerMP player) {
        UUID id = cart.getUniqueID();
        WorldServer world = player.getServerWorld();
        ChestLootData loot = find(cart);
        if (loot == null) throw new IllegalStateException("Missing private minecart record for " + id);
        Predicate<EntityPlayer> usable = viewer -> Settings.privateLootEnabled
                && viewer.world == cart.world && !(viewer instanceof FakePlayer)
                && world.getEntityFromUuid(id) == cart && cart.isUsableByPlayer(viewer);
        TextComponentTranslation title = new TextComponentTranslation("container.lootr4rlcraft.chest");
        if (loot.isFixed()) return loot.open(world, player, title, usable, cart::openInventory, cart::closeInventory);
        NBTTagCompound entry = carts.getCompoundTag(id.toString());
        ResourceLocation table = new ResourceLocation(entry.getString("Table"));
        long seed = entry.getLong("Seed");
        return loot.openGenerated(world, player, title, usable, cart::openInventory, cart::closeInventory, inv -> {
            LootContext context = new LootContext.Builder(world).withLootedEntity(cart)
                    .withLuck(player.getLuck()).withPlayer(player).build();
            world.getLootTableManager().getLootTableFromLocation(table)
                    .fillInventory(inv, seed == 0 ? new Random() : new Random(seed), context);
        });
    }

    @Override public void readFromNBT(NBTTagCompound nbt) {
        if (nbt.getInteger("Version") != 1 || !nbt.hasKey("Carts", 10)) {
            throw new IllegalStateException("Unsupported or damaged private minecart data");
        }
        loaded.clear();
        carts = nbt.getCompoundTag("Carts");
    }

    @Override public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("Version", 1);
        nbt.setTag("Carts", carts.copy());
        return nbt;
    }
}
