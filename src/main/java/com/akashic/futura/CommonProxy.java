package com.akashic.futura;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** Server-side (and shared) setup. */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Futura3D.LOG.info("{} {} loading", Futura3D.NAME, Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {}
}
