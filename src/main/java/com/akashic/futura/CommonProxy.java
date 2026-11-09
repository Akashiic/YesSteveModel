package com.akashic.futura;

import com.akashic.futura.server.ForgeModelServer;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** Server-side (and shared) setup. */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Futura3D.LOG.info("{} {} loading", Futura3D.NAME, Tags.VERSION);
        ForgeModelServer.setConfigDir(event.getModConfigurationDirectory());
    }

    /** The model channel and the server's handlers (the integrated server of singleplayer needs them too). */
    public void init(FMLInitializationEvent event) {
        ModNetwork.init();
        ForgeModelServer.registerEvents();
    }
}
