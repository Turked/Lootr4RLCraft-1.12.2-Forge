package com.mechgaming.lootr4rlcraft.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.client.resources.data.PackMetadataSection;
import net.minecraft.client.resources.data.PackMetadataSectionSerializer;
import net.minecraft.util.ResourceLocation;

/** Exercises packaged metadata and locale paths without modifying the game's translation map. */
public final class ResourceLanguageTest {
    private ResourceLanguageTest() {}

    public static void verify() throws IOException {
        URL language = ResourceLanguageTest.class.getResource("/assets/lootr4rlcraft/lang/en_us.lang");
        if (language == null) {
            throw new AssertionError("The mod's English language resource is missing");
        }
        IResourcePack pack;
        try {
            if ("jar".equals(language.getProtocol())) {
                URL archive = ((JarURLConnection) language.openConnection()).getJarFileURL();
                pack = new FileResourcePack(new File(archive.toURI()));
            } else {
                File root = new File(language.toURI()).getParentFile().getParentFile().getParentFile().getParentFile();
                pack = new FolderResourcePack(root);
            }
        } catch (URISyntaxException error) {
            throw new IOException("Invalid test resource path", error);
        }
        MetadataSerializer serializer = new MetadataSerializer();
        serializer.registerMetadataSectionType(new PackMetadataSectionSerializer(), PackMetadataSection.class);
        PackMetadataSection metadata = pack.getPackMetadata(serializer, "pack");
        if (metadata == null || metadata.getPackFormat() != 3) {
            throw new AssertionError("Minecraft 1.12 requires pack_format 3 for lowercase locale paths");
        }
        Properties translations = new Properties();
        try (InputStream stream = pack.getInputStream(new ResourceLocation("lootr4rlcraft", "lang/en_us.lang"));
                InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            translations.load(reader);
        }
        if (!"lootr".equals(translations.getProperty("container.lootr4rlcraft.chest"))
                || !"lootr".equals(translations.getProperty("container.lootr4rlcraft.double_chest"))) {
            throw new AssertionError("Both chest names must translate to the requested literal title");
        }
    }
}
