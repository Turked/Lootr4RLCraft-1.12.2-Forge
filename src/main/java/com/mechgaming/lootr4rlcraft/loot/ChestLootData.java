package com.mechgaming.lootr4rlcraft.loot;

import com.mechgaming.lootr4rlcraft.container.GuardedChestContainer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraftforge.common.util.Constants.NBT;

/** A loaded container's saved loot and open inventories. Access from the server thread. */
public final class ChestLootData {
    public static final String KEY = "lootr4rlcraft";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_SLOTS = 256;

    private final UUID id;
    private final boolean fixed;
    private final boolean generated;
    @Nullable
    private final ResourceLocation lootTable;
    private final long seed;
    private final int size;
    private final NBTTagCompound players;
    private final NBTTagCompound template;
    private final Runnable markDirty;
    private final Map<UUID, PersonalInventory> openInventories = new HashMap<>();

    private ChestLootData(NBTTagCompound tag, Runnable markDirty) {
        id = tag.getUniqueId("Id");
        fixed = "fixed".equals(tag.getString("Mode"));
        generated = "generated".equals(tag.getString("Mode"));
        lootTable = fixed || generated ? null : new ResourceLocation(tag.getString("LootTable"));
        seed = tag.getLong("LootTableSeed");
        size = tag.getInteger("Size");
        players = tag.getCompoundTag("Players");
        template = tag.getCompoundTag("Template");
        this.markDirty = Objects.requireNonNull(markDirty);
    }

    /**
     * Read from a live, persisted NBT owner, such as a tile entity's custom data.
     * Keep one data instance per loaded container while there are open views.
     *
     * @throws IllegalStateException if an existing record has an unsupported schema
     */
    @Nullable
    public static ChestLootData read(NBTTagCompound owner, Runnable markDirty) {
        if (!owner.hasKey(KEY)) {
            return null;
        }
        NBTTagCompound tag = owner.getCompoundTag(KEY);
        boolean fixed = "fixed".equals(tag.getString("Mode"));
        boolean generated = "generated".equals(tag.getString("Mode"));
        boolean knownMode = fixed || generated || tag.getString("Mode").isEmpty() || "table".equals(tag.getString("Mode"));
        if (!owner.hasKey(KEY, NBT.TAG_COMPOUND)
                || tag.getInteger("Version") != SCHEMA_VERSION
                || !tag.hasUniqueId("Id")
                || !knownMode
                || (fixed ? !tag.getCompoundTag("Template").hasKey("Items", NBT.TAG_LIST)
                        : !generated && tag.getString("LootTable").isEmpty())
                || !tag.hasKey("LootTableSeed", NBT.TAG_LONG)
                || !validSize(tag.getInteger("Size"))
                || !tag.hasKey("Players", NBT.TAG_COMPOUND)) {
            throw new IllegalStateException("Invalid or unsupported Lootr4RLCraft container data");
        }
        return new ChestLootData(tag, markDirty);
    }

    public static ChestLootData create(NBTTagCompound owner, Runnable markDirty,
            ResourceLocation table, long seed, int size) {
        if (owner.hasKey(KEY)) {
            throw new IllegalStateException("Lootr4RLCraft container data already exists");
        }
        if (!validSize(size) || table.getResourcePath().isEmpty()) {
            throw new IllegalArgumentException("Invalid loot table or container size");
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", SCHEMA_VERSION);
        tag.setUniqueId("Id", UUID.randomUUID());
        tag.setString("Mode", "table");
        tag.setString("LootTable", table.toString());
        tag.setLong("LootTableSeed", seed);
        tag.setInteger("Size", size);
        tag.setTag("Players", new NBTTagCompound());
        ChestLootData data = new ChestLootData(tag, markDirty);
        owner.setTag(KEY, tag);
        markDirty.run();
        return data;
    }

    /** Explicit administrator conversion of a fixed inventory into a per-player template. */
    public static ChestLootData createFixed(NBTTagCompound owner, Runnable markDirty,
            NonNullList<ItemStack> originalContents) {
        if (owner.hasKey(KEY)) {
            throw new IllegalStateException("Lootr4RLCraft container data already exists");
        }
        if (!validSize(originalContents.size())) {
            throw new IllegalArgumentException("Invalid fixed container size");
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", SCHEMA_VERSION);
        tag.setUniqueId("Id", UUID.randomUUID());
        tag.setString("Mode", "fixed");
        tag.setLong("LootTableSeed", 0L);
        tag.setInteger("Size", originalContents.size());
        tag.setTag("Players", new NBTTagCompound());
        tag.setTag("Template", ItemStackHelper.saveAllItems(new NBTTagCompound(), originalContents).copy());
        ChestLootData data = new ChestLootData(tag, markDirty);
        owner.setTag(KEY, tag);
        markDirty.run();
        return data;
    }

    /** A container whose first allocation is filled by its installed implementation's adapter. */
    public static ChestLootData createGenerated(NBTTagCompound owner, Runnable markDirty, int size) {
        if (owner.hasKey(KEY)) {
            throw new IllegalStateException("Lootr4RLCraft container data already exists");
        }
        if (!validSize(size)) {
            throw new IllegalArgumentException("Invalid generated container size");
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", SCHEMA_VERSION);
        tag.setUniqueId("Id", UUID.randomUUID());
        tag.setString("Mode", "generated");
        tag.setLong("LootTableSeed", 0L);
        tag.setInteger("Size", size);
        tag.setTag("Players", new NBTTagCompound());
        ChestLootData data = new ChestLootData(tag, markDirty);
        owner.setTag(KEY, tag);
        markDirty.run();
        return data;
    }

    private static boolean validSize(int size) {
        return size > 0 && size <= MAX_SLOTS;
    }

    public UUID getId() {
        return id;
    }

    public boolean isFixed() {
        return fixed;
    }

    public boolean isGenerated() {
        return generated;
    }

    @Nullable
    public ResourceLocation getLootTable() {
        return lootTable;
    }

    public long getSeed() {
        return seed;
    }

    public int getSize() {
        return size;
    }

    public boolean hasOpened(UUID playerId) {
        return players.hasKey(playerId.toString());
    }

    public boolean hasOpened(EntityPlayerMP player) {
        if (PlayerCheckpoints.records(player, false).hasKey(id.toString(), NBT.TAG_COMPOUND)) return true;
        NBTTagCompound legacy = players.getCompoundTag(player.getUniqueID().toString());
        return legacy.hasKey("Items", NBT.TAG_LIST) && !legacy.hasKey("CheckpointBase")
                && !legacy.hasKey("BaselineIsCurrent");
    }

    public IInventory open(WorldServer world, EntityPlayerMP player, ITextComponent title,
            Predicate<EntityPlayer> usable, Consumer<EntityPlayer> onOpen, Consumer<EntityPlayer> onClose) {
        if (generated) {
            throw new IllegalStateException("This container requires its native generation adapter");
        }
        requireAccess(world, player, usable);
        return open(player, player.getUniqueID(), title, usable, onOpen, onClose, inventory -> {
            LootTable table = world.getLootTableManager().getLootTableFromLocation(lootTable);
            Random random = seed == 0L ? new Random() : new Random(seed);
            LootContext context = new LootContext.Builder(world)
                    .withLuck(player.getLuck())
                    .withPlayer(player)
                    .build();
            table.fillInventory(inventory, random, context);
        });
    }

    public IInventory openGenerated(WorldServer world, EntityPlayerMP player, ITextComponent title,
            Predicate<EntityPlayer> usable, Consumer<EntityPlayer> onOpen, Consumer<EntityPlayer> onClose,
            Consumer<IInventory> initialFill) {
        if (!generated) {
            throw new IllegalStateException("This container does not use a native generation adapter");
        }
        requireAccess(world, player, usable);
        return open(player, player.getUniqueID(), title, usable, onOpen, onClose, Objects.requireNonNull(initialFill));
    }

    private void requireAccess(WorldServer world, EntityPlayerMP player, Predicate<EntityPlayer> usable) {
        if (world != player.getServerWorld() || player.isSpectator()
                || !world.getMinecraftServer().isCallingFromMinecraftThread() || !usable.test(player)) {
            throw new IllegalStateException("Unavailable Lootr4RLCraft inventory");
        }
    }

    IInventory open(UUID playerId, ITextComponent title, Predicate<EntityPlayer> usable,
            Consumer<EntityPlayer> onOpen, Consumer<EntityPlayer> onClose, Consumer<IInventory> initialFill) {
        return open(null, playerId, title, usable, onOpen, onClose, initialFill);
    }

    private IInventory open(@Nullable EntityPlayerMP session, UUID playerId, ITextComponent title, Predicate<EntityPlayer> usable,
            Consumer<EntityPlayer> onOpen, Consumer<EntityPlayer> onClose, Consumer<IInventory> initialFill) {
        PersonalInventory current = openInventories.get(playerId);
        if (current != null && current.session == session) {
            return current;
        }

        String key = playerId.toString();
        PersonalInventory inventory = new PersonalInventory(session, playerId, title, usable, onOpen, onClose);
        if (players.hasKey(key) && !players.hasKey(key, NBT.TAG_COMPOUND)) {
            throw new IllegalStateException("Invalid Lootr4RLCraft player inventory for " + playerId);
        }
        NBTTagCompound mirror = players.getCompoundTag(key);
        NBTTagCompound baseline = mirror.hasKey("CheckpointBase") ? mirror.getCompoundTag("CheckpointBase") : mirror;
        NBTTagCompound checkpoints = session == null ? null : PlayerCheckpoints.records(session, true);
        String containerKey = id.toString();
        NBTTagCompound saved = mirror;
        if (checkpoints != null) {
            saved = checkpoints.hasKey(containerKey) ? checkpoints.getCompoundTag(containerKey) : baseline;
        }
        if (players.hasKey(key) || checkpoints != null && checkpoints.hasKey(containerKey)) {
            if (!saved.hasKey("Items", NBT.TAG_LIST)) {
                throw new IllegalStateException("Missing Lootr4RLCraft item list for " + playerId);
            }
            NonNullList<ItemStack> contents = NonNullList.withSize(size, ItemStack.EMPTY);
            ItemStackHelper.loadAllItems(saved.copy(), contents);
            for (int slot = 0; slot < size; slot++) {
                inventory.setInventorySlotContents(slot, contents.get(slot));
            }
        } else {
            if (fixed) {
                NonNullList<ItemStack> contents = NonNullList.withSize(size, ItemStack.EMPTY);
                ItemStackHelper.loadAllItems(template.copy(), contents);
                for (int slot = 0; slot < size; slot++) {
                    inventory.setInventorySlotContents(slot, contents.get(slot));
                }
            } else {
                initialFill.accept(inventory);
            }
        }
        // If the chunk reached disk ahead of a first-time player's file, the immutable baseline
        // is the version paired with that earlier player inventory. Legacy records start at their existing contents.
        NBTTagCompound initial = baseline.hasKey("Items", NBT.TAG_LIST)
                ? itemsOnly(baseline) : snapshot(inventory);
        Consumer<IInventory> save = changed -> {
            NBTTagCompound latest = snapshot(changed);
            if (checkpoints != null) {
                checkpoints.setTag(containerKey, latest.copy());
                if (com.mechgaming.lootr4rlcraft.Settings.compactCheckpoints && initial.equals(latest)) latest.setBoolean("BaselineIsCurrent", true);
                else latest.setTag("CheckpointBase", initial);
            }
            players.setTag(key, latest);
            markDirty.run();
        };
        if (checkpoints != null || !players.hasKey(key)) save.accept(inventory);
        openInventories.put(playerId, inventory);
        inventory.addInventoryChangeListener(changed -> {
            if (openInventories.get(playerId) == inventory) save.accept(changed);
        });
        return inventory;
    }

    private static NBTTagCompound itemsOnly(NBTTagCompound saved) {
        NBTTagCompound contents = new NBTTagCompound();
        contents.setTag("Items", saved.getTag("Items").copy());
        return contents;
    }

    private NBTTagCompound snapshot(IInventory inventory) {
        NonNullList<ItemStack> contents = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int slot = 0; slot < size; slot++) {
            contents.set(slot, inventory.getStackInSlot(slot));
        }
        return ItemStackHelper.saveAllItems(new NBTTagCompound(), contents).copy();
    }

    private final class PersonalInventory extends InventoryBasic implements IInteractionObject {
        private final EntityPlayerMP session;
        private final UUID playerId;
        private final ITextComponent title;
        private final Predicate<EntityPlayer> usable;
        private final Consumer<EntityPlayer> onOpen;
        private final Consumer<EntityPlayer> onClose;
        private int viewers;

        private PersonalInventory(@Nullable EntityPlayerMP session, UUID playerId, ITextComponent title, Predicate<EntityPlayer> usable,
                Consumer<EntityPlayer> onOpen, Consumer<EntityPlayer> onClose) {
            super(title instanceof TextComponentTranslation
                    ? ((TextComponentTranslation) title).getKey() : title.getUnformattedText(),
                    !(title instanceof TextComponentTranslation), size);
            this.playerId = playerId;
            this.session = session;
            this.title = title;
            this.usable = usable;
            this.onOpen = onOpen;
            this.onClose = onClose;
        }

        @Override
        public ITextComponent getDisplayName() {
            return title;
        }

        @Override
        public String getGuiID() {
            return "minecraft:chest";
        }

        @Override
        public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
            return new GuardedChestContainer(inventory, this, player);
        }

        @Override
        public boolean isUsableByPlayer(EntityPlayer player) {
            return (session == null || player == session) && playerId.equals(player.getUniqueID()) && !player.isSpectator() && usable.test(player);
        }

        @Override
        public void openInventory(EntityPlayer player) {
            if (isUsableByPlayer(player)) {
                onOpen.accept(player);
                viewers++;
            }
        }

        @Override
        public void closeInventory(EntityPlayer player) {
            if (!playerId.equals(player.getUniqueID()) || viewers == 0) {
                return;
            }
            viewers--;
            if (viewers == 0) {
                openInventories.remove(playerId, this);
            }
            onClose.accept(player);
        }

        @Override
        public ItemStack removeStackFromSlot(int index) {
            ItemStack stack = super.removeStackFromSlot(index);
            if (!stack.isEmpty()) {
                markDirty();
            }
            return stack;
        }

        @Override
        public void clear() {
            super.clear();
            markDirty();
        }
    }
}
