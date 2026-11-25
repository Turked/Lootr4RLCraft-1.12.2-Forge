package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.adapter.ContainerAdapters;
import com.mechgaming.lootr4rlcraft.adapter.InventoryAdapter;
import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.lang.reflect.Field;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class CompatibilityProbe {
    private static final Logger LOG = LogManager.getLogger("lootr-server-probe");

    static void run(WorldServer world, EntityPlayerMP[] actors, BlockPos base) throws Exception {
        Class<?> generalType = Class.forName("artifacts.common.ModConfig");
        Object settings = generalType.getField("general").get(null);
        Field chance = settings.getClass().getField("unlootedChestMimicRatio");
        double previous = chance.getDouble(settings);
        try {
            chance.setDouble(settings, 1.0);
            BlockPos winning = free(world, base);
            placeTable(world, winning);
            int originalCount = mimics(world, winning);
            click(world, actors[0], winning, ItemStack.EMPTY, EnumFacing.SOUTH);
            require(world.isAirBlock(winning) && mimics(world, winning) == originalCount + 1
                    && actors[0].openContainer == actors[0].inventoryContainer, "Actual RLArtifacts winning roll did not replace the chest exactly once");
            click(world, actors[1], winning, ItemStack.EMPTY, EnumFacing.SOUTH);
            require(mimics(world, winning) == originalCount + 1, "Second interaction repeated a winning Mimic");
            BlockPos broken = free(world, base.south(10));
            placeTable(world, broken);
            actors[2].setPosition(broken.getX() + 0.5, broken.getY(), broken.getZ() + 1.0);
            int beforeBreak = mimics(world, broken);
            actors[2].interactionManager.tryHarvestBlock(broken);
            require(world.isAirBlock(broken) && mimics(world, broken) == beforeBreak + 1,
                    "Actual break handler did not replace the unopened table chest exactly once");
            chance.setDouble(settings, Double.MIN_VALUE);
            BlockPos surviving = free(world, base.east(3));
            placeTable(world, surviving);
            click(world, actors[0], surviving, ItemStack.EMPTY, EnumFacing.SOUTH);
            requirePrivate(actors[0]);
            actors[0].closeScreen();
            chance.setDouble(settings, 1.0);
            click(world, actors[1], surviving, ItemStack.EMPTY, EnumFacing.SOUTH);
            requirePrivate(actors[1]);
            actors[1].closeScreen();
            require(ContainerLoot.isManaged(world.getTileEntity(surviving)), "Native Mimic first-open repeated for the next player");
            LOG.info("COMPAT PASS actual RLArtifacts forced winning click and break, no inventory on replacement, native losing first click and second player under forced winning odds");
        } finally {
            chance.setDouble(settings, previous);
        }

        String[] types = {"minecraft:chest", "minecraft:chest", "minecraft:trapped_chest", "quark:custom_chest",
                "rustic:cabinet", "rustic:barrel", "charm:barrel", "variedcommodities:barrel"};
        int failures = 0;
        for (int i = 0; i < types.length; i++) {
            try {
                lockCase(world, actors, free(world, base.add(2 + (i % 4) * 3, 0, 3 + (i / 4) * 4)), types[i], i == 1);
            } catch (Exception failure) {
                failures++;
                LOG.error("COMPAT FAIL {} double={}", types[i], i == 1, failure);
            } finally {
                for (EntityPlayerMP actor : actors) {
                    actor.setSneaking(false);
                    actor.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                    if (actor.openContainer != actor.inventoryContainer) actor.closeScreen();
                }
            }
        }
        require(failures == 0, failures + " Locks compatibility fixture(s) failed; inspect individual outcomes");
    }

    private static void placeTable(WorldServer world, BlockPos pos) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(pos);
        chest.setLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"), 84811L);
        ContainerLoot.convert(world, pos, false);
    }

    private static BlockPos free(WorldServer world, BlockPos pos) {
        // Allow a repeat while leaving previous fixtures intact.
        for (int y = pos.getY(); y < 255; y += 2) {
            BlockPos candidate = new BlockPos(pos.getX(), y, pos.getZ());
            if (world.isAirBlock(candidate) && world.isAirBlock(candidate.east())) return candidate;
        }
        throw new IllegalStateException("No clear fixture volume above " + pos);
    }

    private static int mimics(WorldServer world, BlockPos pos) {
        return world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos).grow(3),
                e -> e != null && "artifacts.common.entity.EntityMimic".equals(e.getClass().getName())).size();
    }

    private static void lockCase(WorldServer world, EntityPlayerMP[] actors, BlockPos pos, String type, boolean pair) throws Exception {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(type));
        require(block != null && block != Blocks.AIR, "Missing block " + type);
        world.setBlockState(pos, block.getDefaultState(), 3);
        if (pair) world.setBlockState(pos.east(), block.getDefaultState(), 3);
        TileEntity tile = world.getTileEntity(pos);
        InventoryAdapter adapter = ContainerAdapters.forEnrollment(tile);
        require(adapter != null, "Missing adapter for " + type);
        IInventory physical = adapter.physical(tile);
        physical.setInventorySlotContents(0, new ItemStack(Items.BOOK, 2));
        BlockPos lockPos = pair ? pos.east() : pos;
        ItemStack key = item("KEY");
        Object lock = addLock(world, lockPos, key);
        require(!LockAccess.canOpen(world, lockPos), "Locks did not register the fixture attachment");
        ContainerLoot.convert(world, pos, true);
        require(world.getTileEntity(pos) == tile && !LockAccess.canOpen(world, lockPos), "Enrollment altered the lock or tile");
        EnumFacing face = EnumFacing.SOUTH;
        if ("rustic:cabinet".equals(type)) {
            IBlockState state = world.getBlockState(pos);
            for (IProperty<?> property : state.getPropertyKeys())
                if ("facing".equals(property.getName())) face = (EnumFacing) state.getValue(property);
        }
        click(world, actors[0], pos, ItemStack.EMPTY, face);
        require(actors[0].openContainer == actors[0].inventoryContainer, "Locked single or connected half exposed loot");
        click(world, actors[0], lockPos, key, face);
        require(LockAccess.canOpen(world, lockPos), "Native matching key did not unlock");
        click(world, actors[0], pos, ItemStack.EMPTY, face);
        IInventory menu = requirePrivate(actors[0]);
        require(menu.getStackInSlot(0).getCount() == 2 && (!pair || menu.getSizeInventory() == 54), "Private source or double size changed");
        if (block == Blocks.TRAPPED_CHEST) {
            IBlockState state = world.getBlockState(pos);
            require(state.getWeakPower(world, pos, EnumFacing.NORTH) == 1, "Trapped chest did not power adjacent blocks on open");
        }
        actors[0].closeScreen();
        if (block == Blocks.TRAPPED_CHEST) require(world.getBlockState(pos).getWeakPower(world, pos, EnumFacing.NORTH) == 0,
                "Trapped chest remained powered after close");
        actors[0].setSneaking(true);
        click(world, actors[0], lockPos, key, face);
        actors[0].setSneaking(false);
        require(!LockAccess.canOpen(world, lockPos), "Native matching key did not relock");
        click(world, actors[1], lockPos, item("DIAMOND_LOCK_PICK"), face);
        Object pickMenu = actors[1].openContainer;
        require("melonslise.locks.common.container.LockPickingContainer".equals(pickMenu.getClass().getName()),
                "Native pick did not open the Locks server menu");
        int length = (Integer) lock.getClass().getMethod("getLength").invoke(lock);
        for (int i = 0; i < length; i++) {
            byte pin = (Byte) lock.getClass().getMethod("getPin", int.class).invoke(lock, i);
            pickMenu.getClass().getMethod("checkPin", int.class).invoke(pickMenu, (int) pin);
        }
        actors[1].closeScreen();
        require(LockAccess.canOpen(world, lockPos), "Native successful lockpicking did not unlock");
        click(world, actors[1], pos, ItemStack.EMPTY, face);
        requirePrivate(actors[1]);
        LOG.info("COMPAT PASS {} pair={} locked denial, key, relock, native pick-menu pin sequence and private reopen", type, pair);
    }

    private static Object addLock(WorldServer world, BlockPos pos, ItemStack key) throws Exception {
        Class<?> cuboid = Class.forName("melonslise.locks.common.util.Cuboid6i");
        Class<?> lockType = Class.forName("melonslise.locks.common.util.Lock");
        Class<?> lockableType = Class.forName("melonslise.locks.common.util.Lockable");
        Class<?> orient = Class.forName("melonslise.locks.common.util.Orientation");
        Class<?> itemType = Class.forName("melonslise.locks.common.item.LockingItem");
        int id = (Integer) itemType.getMethod("getOrSetId", ItemStack.class).invoke(null, key);
        Object lock = lockType.getConstructor(int.class, int.class, boolean.class).newInstance(id, 2, true);
        Object lockable = lockableType.getConstructor(cuboid, lockType, orient, ItemStack.class, World.class)
                .newInstance(cuboid.getConstructor(BlockPos.class).newInstance(pos), lock, orient.getField("SOUTH_MID").get(null), item("WOOD_LOCK"), world);
        Capability<?> cap = (Capability<?>) Class.forName("melonslise.locks.common.init.LocksCapabilities").getField("LOCKABLE_HANDLER").get(null);
        Object manager = world.getCapability(cap, null);
        require(Boolean.TRUE.equals(Class.forName("melonslise.locks.common.capability.ILockableHandler")
                .getMethod("add", lockableType).invoke(manager, lockable)), "Lock fixture rejected by Locks");
        return lock;
    }

    private static ItemStack item(String field) throws Exception {
        return new ItemStack((Item) Class.forName("melonslise.locks.common.init.LocksItems").getField(field).get(null));
    }

    private static void click(WorldServer world, EntityPlayerMP actor, BlockPos pos, ItemStack hand, EnumFacing face) {
        actor.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.0);
        actor.setHeldItem(EnumHand.MAIN_HAND, hand);
        actor.interactionManager.processRightClickBlock(actor, world, hand, EnumHand.MAIN_HAND, pos, face, 0.5F, 0.5F, 0.5F);
    }

    private static IInventory requirePrivate(EntityPlayerMP actor) {
        require(actor.openContainer instanceof ContainerChest, "No private chest menu");
        IInventory contents = ((ContainerChest) actor.openContainer).getLowerChestInventory();
        require("lootr".equals(contents.getDisplayName().getUnformattedText()), "Opened the native instead of private menu");
        return contents;
    }

    private static void require(boolean success, String reason) {
        if (!success) throw new IllegalStateException(reason);
    }
}
