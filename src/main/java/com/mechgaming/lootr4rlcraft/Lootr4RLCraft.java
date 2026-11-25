package com.mechgaming.lootr4rlcraft;

import com.mechgaming.lootr4rlcraft.chest.ChestEvents;
import com.mechgaming.lootr4rlcraft.cart.CartEvents;
import com.mechgaming.lootr4rlcraft.cart.CartViews;
import com.mechgaming.lootr4rlcraft.chest.InstancedChestTile;
import com.mechgaming.lootr4rlcraft.command.LootrCommand;
import com.mechgaming.lootr4rlcraft.container.ContainerChunkData;
import com.mechgaming.lootr4rlcraft.container.ContainerEvents;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Lootr4RLCraft.ID, name = "Lootr4RLCraft", version = "0.2.0-dev", useMetadata = true,
        acceptedMinecraftVersions = "[1.12.2]", dependencies = "required-after:forge@[14.23.5.2860,)")
public final class Lootr4RLCraft {
    public static final String ID = "lootr4rlcraft";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    @SidedProxy(clientSide = "com.mechgaming.lootr4rlcraft.client.ClientProxy",
            serverSide = "com.mechgaming.lootr4rlcraft.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Settings.load(event.getSuggestedConfigurationFile());
        ContainerChunkData.register();
        GameRegistry.registerTileEntity(InstancedChestTile.class, new ResourceLocation(ID, "structure_chest"));
        ChestViews.register();
        CartViews.register();
        ChestEvents chests = new ChestEvents();
        MinecraftForge.EVENT_BUS.register(chests);
        MinecraftForge.EVENT_BUS.register(new CartEvents(chests::generatedEntity));
        MinecraftForge.EVENT_BUS.register(new ContainerEvents());
        MinecraftForge.EVENT_BUS.register(new com.mechgaming.lootr4rlcraft.loot.PlayerCheckpoints());
        GameRegistry.registerWorldGenerator((random, x, z, world, generator, provider) -> {
            if (world instanceof net.minecraft.world.WorldServer) {
                chests.beginAdditionalGenerators((net.minecraft.world.WorldServer) world, x, z, generator);
            }
        }, Integer.MIN_VALUE);
        GameRegistry.registerWorldGenerator(chests, Integer.MAX_VALUE);
        chests.registerDiscovery();
        proxy.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new LootrCommand());
    }
}
