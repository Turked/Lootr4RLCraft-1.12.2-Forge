package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;
import org.apache.logging.log4j.LogManager;

/** Exercises Luckified's actual transformed loot and RLArtifacts code in the optional environment. */
final class LuckifiedProbe {
    private static final ResourceLocation TABLE = new ResourceLocation("lootrserverprobe", "luck_gear");

    private static BlockPos position(WorldServer world) {
        BlockPos spawn = world.getSpawnPoint();
        return new BlockPos(((spawn.getX() >> 4) << 4) + 4, 246, ((spawn.getZ() >> 4) << 4) + 4);
    }

    static void prime(WorldServer world, EntityPlayerMP actor) {
        BlockPos pos = position(world);
        int x = pos.getX() >> 4;
        int z = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunkFromChunkCoords(x + dx, z + dz);
            }
        }
        Chunk chunk = world.getChunkFromChunkCoords(x, z);
        MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(chunk, actor));
        LogManager.getLogger("lootr-server-probe").info("LUCK PRIMED watch {}. Run lootrqa luck after Locks' delayed pass.", chunk.getPos());
    }

    static void run(WorldServer world, EntityPlayerMP[] actors) throws Exception {
        BlockPos pos = position(world);
        require(LockAccess.worldgenReady(world.getChunkFromBlockCoords(pos)), "Run lootrqa luck-prime and wait 40 server ticks");
        for (int i = 0; i < 4 && !world.isAirBlock(pos); i++) {
            pos = pos.up(2);
        }
        BlockPos other = pos.east(4);
        require(world.isAirBlock(pos) && world.isAirBlock(other), "Fixture area is occupied");
        List<Runnable> restore = new ArrayList<>();
        try {
            Object vanilla = Class.forName("luckified.ModConfig").getField("vanilla").get(null);
            Object artifacts = Class.forName("luckified.ModConfig").getField("rlArtifacts").get(null);
            Object mimicSettings = Class.forName("artifacts.common.ModConfig").getField("general").get(null);
            set(vanilla, "changeItemColors", true, restore);
            set(vanilla, "maxEnchantabilityLootBaseChance", 1.0F, restore);
            set(vanilla, "luckEnchantabilityMaxLoot", 2.0F, restore);
            set(artifacts, "luckMimicChance", 0.5F, restore);
            set(mimicSettings, "unlootedChestMimicRatio", Double.MIN_VALUE, restore);
            actors[0].getEntityAttribute(SharedMonsterAttributes.LUCK).setBaseValue(0);
            actors[1].getEntityAttribute(SharedMonsterAttributes.LUCK).setBaseValue(4);
            place(world, pos);
            for (int i = 0; i < 2; i++) {
                EntityPlayerMP actor = actors[i];
                TileEntityChest nativeControl = new TileEntityChest();
                nativeControl.setWorld(world);
                nativeControl.setPos(pos);
                nativeControl.setLootTable(TABLE, 42L);
                nativeControl.fillWithLoot(actor);
                ItemStack nativeGear = sword(nativeControl);
                activate(world, actor, pos);
                require(actor.openContainer instanceof ContainerChest, "A private menu was not opened for luck " + actor.getLuck());
                IInventory menu = ((ContainerChest) actor.openContainer).getLowerChestInventory();
                require("lootr".equals(menu.getDisplayName().getUnformattedText()), "Original shared menu bypassed private opening");
                ItemStack managed = sword(menu);
                String expected = (i == 0 ? TextFormatting.YELLOW : TextFormatting.GOLD).toString();
                require(managed.getDisplayName().startsWith(expected) && managed.getDisplayName().equals(nativeGear.getDisplayName())
                        && managed.getEnchantmentTagList().equals(nativeGear.getEnchantmentTagList()), "Private gear diverged from native Luckified control");
                actor.closeScreen();
            }
            place(world, other);
            int count = mimics(world, other);
            activate(world, actors[1], other);
            require(world.isAirBlock(other) && mimics(world, other) == count + 1 && actors[1].openContainer == actors[1].inventoryContainer,
                    "Luckified's high-luck Mimic bonus failed on a fresh managed chest");
            LogManager.getLogger("lootr-server-probe").info("LUCK PASS two identical-seed native vs managed rolls: luck 0 yellow, luck 4 gold, enchantment NBT equal; high-luck Mimic only on fresh chest");
        } finally {
            for (Runnable reset : restore) reset.run();
            for (EntityPlayerMP actor : actors) if (actor.openContainer != actor.inventoryContainer) actor.closeScreen();
        }
    }

    private static void place(WorldServer world, BlockPos pos) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        ((TileEntityChest) world.getTileEntity(pos)).setLootTable(TABLE, 42L);
        ContainerLoot.convert(world, pos, false);
    }

    private static void activate(WorldServer world, EntityPlayerMP actor, BlockPos pos) {
        actor.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.0);
        actor.interactionManager.processRightClickBlock(actor, world, ItemStack.EMPTY, EnumHand.MAIN_HAND, pos,
                EnumFacing.SOUTH, 0.5F, 0.5F, 0.5F);
    }

    private static int mimics(WorldServer world, BlockPos pos) {
        return world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos).grow(2), e ->
                e != null && e.getClass().getName().equals("artifacts.common.entity.EntityMimic")).size();
    }

    private static ItemStack sword(IInventory inventory) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                require(result.isEmpty() && stack.getItem() == Items.DIAMOND_SWORD && stack.isItemEnchanted(),
                        "Unexpected contents from guaranteed enchanted gear table");
                result = stack;
            }
        }
        require(!result.isEmpty(), "Guaranteed gear table returned no sword");
        return result;
    }

    private static void set(Object target, String name, Object value, List<Runnable> restore) throws Exception {
        Field field = target.getClass().getField(name);
        Object previous = field.get(target);
        restore.add(() -> {
            try {
                field.set(target, previous);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        });
        field.set(target, value);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
