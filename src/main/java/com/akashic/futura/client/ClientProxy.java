package com.akashic.futura.client;

import java.io.File;

import net.minecraft.client.Minecraft;

import com.akashic.futura.CommonProxy;
import com.akashic.futura.Futura3D;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** Client-side setup: loads the models and, in development, the render harness. */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        PlayerModels.INSTANCE.load();
        if (new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness").isFile()) {
            try {
                // Development only: the dev package is not in release jars.
                Class.forName("com.akashic.futura.dev.RenderHarness")
                    .getMethod("install")
                    .invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                Futura3D.LOG.error("Render harness unavailable", e);
            }
        }
    }
}
