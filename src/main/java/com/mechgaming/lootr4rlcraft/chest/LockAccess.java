package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import java.lang.reflect.Method;
import net.minecraft.block.BlockChest;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fml.common.Loader;

public final class LockAccess {
    private static boolean checked;
    private static boolean present;
    private static Method locked;
    private static boolean generationChecked;
    private static Capability<?> worldgen;
    private static int generationDelay = 20;
    private static boolean generationFailureLogged;

    private LockAccess() {}

    public static boolean available() {
        if (!checked) {
            checked = true;
            present = Loader.isModLoaded("locks");
            if (present) {
                try {
                    Class<?> api = Class.forName("melonslise.locks.common.util.LocksUtil");
                    locked = api.getMethod("locked", World.class, BlockPos.class);
                } catch (ReflectiveOperationException | LinkageError error) {
                    Lootr4RLCraft.LOGGER.warn("Locks authorization is unavailable. Chest registration is disabled.", error);
                }
            }
        }
        return !present || locked != null;
    }

    public static boolean canOpen(World world, BlockPos pos) {
        if (!available()) {
            return false;
        }
        if (!present) {
            return true;
        }
        try {
            return Boolean.FALSE.equals(locked.invoke(null, world, pos));
        } catch (ReflectiveOperationException | LinkageError error) {
            locked = null;
            Lootr4RLCraft.LOGGER.warn("Locks authorization failed. Chest registration and access are disabled.", error);
            return false;
        }
    }

    public static boolean worldgenReady(Chunk chunk) {
        if (!available()) {
            return false;
        }
        if (!present) {
            return true;
        }
        initializeGenerationApi();
        if (worldgen == null) {
            return false;
        }
        try {
            Object handler = chunk.getCapability(worldgen, null);
            if (handler instanceof INBTSerializable<?>) {
                NBTBase state = ((INBTSerializable<?>) handler).serializeNBT();
                if (state instanceof NBTTagInt) {
                    return ((NBTTagInt) state).getInt() == 2;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            generationFailure("Locks chunk generation state could not be read", failure);
            return false;
        }
        generationFailure("Locks chunk generation state is unavailable", null);
        return false;
    }

    public static boolean worldgenReady(Chunk chunk, TileEntity tile) {
        // Locks 3.0.0's generation handler visits BlockChest + TileEntityChest pairs only.
        // Its region-based authorization still applies to all adapters on every access.
        return available() && (!(tile instanceof TileEntityChest && tile.getBlockType() instanceof BlockChest)
                || worldgenReady(chunk));
    }

    static int generationRetryDelay() {
        available();
        if (!present) {
            return 0;
        }
        initializeGenerationApi();
        return Math.max(1, generationDelay + 1);
    }

    private static void initializeGenerationApi() {
        if (generationChecked) {
            return;
        }
        generationChecked = true;
        try {
            Class<?> capabilities = Class.forName("melonslise.locks.common.init.LocksCapabilities");
            worldgen = (Capability<?>) capabilities.getField("LOCKABLE_WORLDGEN_HANDLER").get(null);
            Class<?> delays = Class.forName("melonslise.locks.common.event.LocksDelayEvents");
            generationDelay = delays.getField("delay").getInt(null);
            if (worldgen == null) {
                generationFailure("Locks chunk generation capability is unavailable", null);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            worldgen = null;
            generationFailure("Locks chunk generation API is unavailable", error);
        }
    }

    private static void generationFailure(String message, Throwable failure) {
        if (!generationFailureLogged) {
            generationFailureLogged = true;
            Lootr4RLCraft.LOGGER.warn(message + ". Automatic conversion waits for verified lock generation.", failure);
        }
    }
}
