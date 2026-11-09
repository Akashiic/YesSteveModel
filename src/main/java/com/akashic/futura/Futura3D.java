package com.akashic.futura;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 3D Futura Engine: custom Bedrock player models for Minecraft 1.7.10, a fork of Yes Steve Model (Apache-2.0).
 * <p>
 * Reads the open authoring format (a folder or zip with {@code ysm.json}, Bedrock geometry and animations, Molang,
 * PNG textures). Encrypted {@code .ysm} exports are not supported.
 */
@Mod(modid = Futura3D.MODID, name = Futura3D.NAME, version = Tags.VERSION, acceptableRemoteVersions = "*")
public final class Futura3D {

    public static final String MODID = "futura3d";
    public static final String NAME = "3D Futura Engine";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @SidedProxy(clientSide = "com.akashic.futura.client.ClientProxy", serverSide = "com.akashic.futura.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
