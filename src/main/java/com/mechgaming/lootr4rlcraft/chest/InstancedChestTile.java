package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.container.GuardedChestContainer;
import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.ILockableContainer;
import net.minecraft.world.LockCode;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Keeps the original chest block, renderer, and lock attachment with private server-side slots. */
public final class InstancedChestTile extends TileEntityChest {
    private static final String DATA = "Lootr4RLCraftData";
    private NBTTagCompound saved = new NBTTagCompound();
    private ChestLootData loot;
    private boolean mimicDropsReleased;

    static InstancedChestTile prepare(WorldServer world, TileEntityChest original) {
        NBTTagCompound originalNbt = original.writeToNBT(new NBTTagCompound());
        NonNullList<ItemStack> fixed = NonNullList.withSize(original.getSizeInventory(), ItemStack.EMPTY);
        if (original.getLootTable() == null) {
            ItemStackHelper.loadAllItems(originalNbt, fixed);
        }
        NBTTagCompound emptyPhysical = originalNbt.copy();
        emptyPhysical.removeTag("Items");
        InstancedChestTile tile = new InstancedChestTile();
        tile.readFromNBT(emptyPhysical);
        tile.saved.setLong("Position", original.getPos().toLong());
        tile.saved.setInteger("Dimension", world.provider.getDimension());
        tile.loot = original.getLootTable() == null
                ? ChestLootData.createFixed(tile.saved, tile::markDirty, fixed)
                : ChestLootData.create(tile.saved, tile::markDirty, original.getLootTable(),
                        originalNbt.getLong("LootTableSeed"), original.getSizeInventory());
        return tile;
    }

    static void link(InstancedChestTile first, InstancedChestTile second) {
        UUID pair = UUID.randomUUID();
        first.saved.setLong("Partner", second.getPos().toLong());
        second.saved.setLong("Partner", first.getPos().toLong());
        first.saved.setUniqueId("PairId", pair);
        second.saved.setUniqueId("PairId", pair);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        saved = tag.getCompoundTag(DATA);
        try {
            loot = ChestLootData.read(saved, this::markDirty);
            if (loot != null && loot.getSize() != getSizeInventory()) {
                throw new IllegalStateException("Wrong inventory size for a chest half");
            }
        } catch (IllegalStateException error) {
            loot = null;
            Lootr4RLCraft.LOGGER.error("Preserving unreadable chest data at {}", getPos(), error);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setTag(DATA, saved.copy());
        return tag;
    }

    // The base update tag contains only base fields. Private DATA is written exclusively for disk saves.
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState previous, IBlockState next) {
        return previous.getBlock() != next.getBlock();
    }

    @Override
    public ITextComponent getDisplayName() {
        return new TextComponentString("lootr");
    }

    @Override
    public void fillWithLoot(@Nullable EntityPlayer player) {
        // RLArtifacts calls this before its first-open/break Mimic roll, including obstructed breaks.
        // Consume the physical pending flag for the whole pair without granting inventory access.
        if (player != null && authorized(player)) {
            for (InstancedChestTile tile : group()) {
                if (tile.getLootTable() != null) {
                    tile.setLootTable(null, 0L);
                    tile.markDirty();
                }
            }
        }
    }

    @Override
    public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
        if (player instanceof EntityPlayerMP && !saved.hasKey("Partner", NBT.TAG_LONG)) {
            try {
                ChestMenu menu = prepareMenu((EntityPlayerMP) player);
                if (menu != null) {
                    return menu.createContainer(inventory, player);
                }
            } catch (RuntimeException error) {
                Lootr4RLCraft.LOGGER.error("Could not open private loot at {}", pos, error);
            }
        }
        return denied(inventory, player);
    }

    boolean openDouble(EntityPlayerMP player) {
        if (!saved.hasKey("Partner", NBT.TAG_LONG)) {
            return false;
        }
        try {
            ChestMenu menu = prepareMenu(player);
            if (menu != null) {
                player.displayGUIChest(menu);
                return true;
            }
        } catch (RuntimeException error) {
            Lootr4RLCraft.LOGGER.error("Could not open private double loot at {}", pos, error);
        }
        player.sendStatusMessage(new TextComponentTranslation("message.lootr4rlcraft.unavailable"), true);
        return false;
    }

    @Nullable
    private ChestMenu prepareMenu(EntityPlayerMP player) {
        if (!canOpen(player)) {
            return null;
        }
        List<InstancedChestTile> tiles = group();
        ITextComponent menuTitle = getDisplayName();
        List<IInventory> inventories = new ArrayList<>(tiles.size());
        for (InstancedChestTile tile : tiles) {
            inventories.add(tile.loot.open((WorldServer) world, player, menuTitle, this::canOpen,
                    tile::openInventory, tile::closeInventory));
        }
        fillWithLoot(player);
        for (InstancedChestTile tile : tiles) {
            ChestViews.send(player, tile.getPos(), true);
        }
        BlockChest block = (BlockChest) world.getBlockState(pos).getBlock();
        return new ChestMenu(inventories, block.getLockableContainer(world, pos), menuTitle, this::canOpen);
    }

    private Container denied(InventoryPlayer inventory, EntityPlayer player) {
        player.sendStatusMessage(new TextComponentTranslation("message.lootr4rlcraft.unavailable"), true);
        Container denied = new ContainerChest(inventory, new InventoryBasic("container.chest", false, 27) {
            @Override
            public boolean isUsableByPlayer(EntityPlayer viewer) {
                return false;
            }
        }, player);
        denied.setCanCraft(player, false);
        return denied;
    }

    boolean canDisplay() {
        return !group().isEmpty();
    }

    boolean hasOpened(EntityPlayerMP player) {
        List<InstancedChestTile> tiles = group();
        return !tiles.isEmpty() && tiles.stream().allMatch(tile -> tile.loot.hasOpened(player));
    }

    boolean isFixed() {
        return loot != null && loot.isFixed();
    }

    boolean isPaired() {
        return saved.hasKey("Partner", NBT.TAG_LONG);
    }

    @Nullable
    BlockPos partnerPosition() {
        return isPaired() ? BlockPos.fromLong(saved.getLong("Partner")) : null;
    }

    /** Finishes a captured native Mimic replacement once, preserving its original physical chest drops. */
    void finishMimicReplacement(EntityPlayerMP player) {
        if (world == null || !world.isBlockLoaded(pos) || !world.isAirBlock(pos)
                || world.getTileEntity(pos) == this || getLootTable() != null || mimicDropsReleased) {
            return;
        }
        mimicDropsReleased = true;
        try {
            if (loot != null && loot.getLootTable() != null && world instanceof WorldServer) {
                InventoryBasic drops = new InventoryBasic("container.chest", false, getSizeInventory());
                LootContext context = new LootContext.Builder((WorldServer) world)
                        .withPlayer(player).withLuck(player.getLuck()).build();
                world.getLootTableManager().getLootTableFromLocation(loot.getLootTable()).fillInventory(drops,
                        loot.getSeed() == 0L ? new Random() : new Random(loot.getSeed()), context);
                InventoryHelper.dropInventoryItems(world, pos, drops);
            }
        } catch (RuntimeException failure) {
            Lootr4RLCraft.LOGGER.error("Could not release original chest loot after native Mimic replacement at {}", pos, failure);
        } finally {
            unlinkMimicPartner();
        }
    }

    private void unlinkMimicPartner() {
        BlockPos partnerPos = partnerPosition();
        if (partnerPos == null || world == null || !world.isBlockLoaded(pos) || !world.isBlockLoaded(partnerPos)
                || !world.isAirBlock(pos)) {
            return;
        }
        TileEntity other = world.getTileEntity(partnerPos);
        if (!(other instanceof InstancedChestTile)) {
            return;
        }
        InstancedChestTile survivor = (InstancedChestTile) other;
        if (survivor.saved.hasKey("Partner", NBT.TAG_LONG)
                && survivor.saved.getLong("Partner") == pos.toLong()
                && saved.hasUniqueId("PairId") && saved.getUniqueId("PairId").equals(survivor.saved.getUniqueId("PairId"))) {
            survivor.saved.removeTag("Partner");
            survivor.saved.removeTag("PairIdMost");
            survivor.saved.removeTag("PairIdLeast");
            survivor.markDirty();
        }
    }

    private boolean validSelf() {
        return loot != null && world instanceof WorldServer && !isInvalid()
                && saved.hasKey("Position", NBT.TAG_LONG) && saved.getLong("Position") == pos.toLong()
                && saved.hasKey("Dimension", NBT.TAG_INT)
                && saved.getInteger("Dimension") == world.provider.getDimension()
                && world.isBlockLoaded(pos) && world.getTileEntity(pos) == this
                && ChestRegistration.isVanillaChest(world.getBlockState(pos).getBlock());
    }

    private List<InstancedChestTile> group() {
        List<InstancedChestTile> tiles = new ArrayList<>(2);
        if (!validSelf()) {
            return tiles;
        }
        Block block = world.getBlockState(pos).getBlock();
        BlockPos partnerPos = partnerPosition();
        InstancedChestTile partner = null;
        if (partnerPos != null) {
            if (!saved.hasUniqueId("PairId") || pos.distanceSq(partnerPos) != 1.0D
                    || pos.getY() != partnerPos.getY() || !world.isBlockLoaded(partnerPos)
                    || world.getBlockState(partnerPos).getBlock() != block) {
                return tiles;
            }
            TileEntity candidate = world.getTileEntity(partnerPos);
            if (!(candidate instanceof InstancedChestTile)) {
                return tiles;
            }
            partner = (InstancedChestTile) candidate;
            if (!partner.validSelf() || !partner.saved.hasKey("Partner", NBT.TAG_LONG)
                    || partner.saved.getLong("Partner") != pos.toLong()
                    || !saved.getUniqueId("PairId").equals(partner.saved.getUniqueId("PairId"))) {
                return tiles;
            }
        }
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.offset(side);
            if (!world.isBlockLoaded(neighbor)
                    || (world.getBlockState(neighbor).getBlock() == block && !neighbor.equals(partnerPos))) {
                return tiles;
            }
            if (partner != null) {
                BlockPos otherNeighbor = partnerPos.offset(side);
                if (!world.isBlockLoaded(otherNeighbor)
                        || (world.getBlockState(otherNeighbor).getBlock() == block && !otherNeighbor.equals(pos))) {
                    return tiles;
                }
            }
        }
        tiles.add(this);
        if (partner != null) {
            tiles.add(partner);
            tiles.sort(Comparator.comparingInt((InstancedChestTile tile) -> tile.pos.getX())
                    .thenComparingInt(tile -> tile.pos.getZ()));
        }
        return tiles;
    }

    boolean authorized(EntityPlayer player) {
        if (!Settings.privateLootEnabled || !isRealViewer(player)) {
            return false;
        }
        List<InstancedChestTile> tiles = group();
        if (tiles.isEmpty()) {
            return false;
        }
        for (InstancedChestTile tile : tiles) {
            if (!tile.isUsableByPlayer(player) || (tile.isLocked() && !player.canOpen(tile.getLockCode()))
                    || !LockAccess.canOpen(world, tile.pos)) {
                return false;
            }
        }
        return true;
    }

    boolean canOpen(EntityPlayer player) {
        return authorized(player)
                && ((BlockChest) world.getBlockState(pos).getBlock()).getLockableContainer(world, pos) != null;
    }

    private boolean isRealViewer(@Nullable EntityPlayer player) {
        return player instanceof EntityPlayerMP && !(player instanceof FakePlayer)
                && !player.isSpectator() && player.world == world;
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return false;
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        // The physical shared inventory accepts no items.
    }

    @Override
    protected IItemHandler createUnSidedHandler() {
        return new ItemStackHandler(0);
    }

    private static final class ChestMenu extends InventoryLargeChest {
        private final List<IInventory> inventories;
        private final ILockableContainer physical;
        private final ITextComponent title;
        private final Predicate<EntityPlayer> usable;

        private ChestMenu(List<IInventory> inventories, ILockableContainer physical, ITextComponent title,
                Predicate<EntityPlayer> usable) {
            super("container.chest", physical, physical);
            this.inventories = inventories;
            this.physical = physical;
            this.title = title;
            this.usable = usable;
        }

        private IInventory half(int slot) {
            return inventories.get(slot / 27);
        }

        @Override
        public int getSizeInventory() {
            return inventories.size() * 27;
        }

        @Override
        public boolean isPartOfLargeChest(IInventory inventory) {
            return physical == inventory || physical instanceof InventoryLargeChest
                    && ((InventoryLargeChest) physical).isPartOfLargeChest(inventory);
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return half(slot).getStackInSlot(slot % 27);
        }

        @Override
        public ItemStack decrStackSize(int slot, int count) {
            return half(slot).decrStackSize(slot % 27, count);
        }

        @Override
        public ItemStack removeStackFromSlot(int slot) {
            return half(slot).removeStackFromSlot(slot % 27);
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            half(slot).setInventorySlotContents(slot % 27, stack);
        }

        @Override
        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            return half(slot).isItemValidForSlot(slot % 27, stack);
        }

        @Override
        public boolean isEmpty() {
            return inventories.stream().allMatch(IInventory::isEmpty);
        }

        @Override
        public void markDirty() {
            if (inventories != null) {
                inventories.forEach(IInventory::markDirty);
            }
        }

        @Override
        public void clear() {
            inventories.forEach(IInventory::clear);
        }

        @Override
        public ITextComponent getDisplayName() {
            return title;
        }

        @Override
        public boolean isUsableByPlayer(EntityPlayer player) {
            return usable.test(player);
        }

        @Override
        public void openInventory(EntityPlayer player) {
            inventories.forEach(inventory -> inventory.openInventory(player));
        }

        @Override
        public void closeInventory(EntityPlayer player) {
            inventories.forEach(inventory -> inventory.closeInventory(player));
        }

        @Override
        public boolean isLocked() {
            return physical.isLocked();
        }

        @Override
        public LockCode getLockCode() {
            return physical.getLockCode();
        }

        @Override
        public void setLockCode(LockCode code) {
            physical.setLockCode(code);
        }

        @Override
        public String getGuiID() {
            return "minecraft:chest";
        }

        @Override
        public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
            return new GuardedChestContainer(inventory, this, player);
        }
    }
}
