package com.akashic.futura.anim;

import java.io.IOException;

import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ModelSource;

/**
 * The built-in default model's animations, loaded once: the fallback for every other model (upstream "default
 * animations"). Loading fails soft: without them, missing animations simply do not play.
 */
public final class DefaultAnimations {

    public static final String BASE = "/assets/futura3d/builtin/default/";

    private static AnimationLibrary library;
    private static boolean attempted;
    private static IOException failure;

    private DefaultAnimations() {}

    /** @return the default library, or null if the default model could not be read */
    public static synchronized AnimationLibrary library() {
        if (!attempted) {
            attempted = true;
            try {
                LoadedModel model = ModelLoader.load(new ClasspathModelSource(BASE));
                library = AnimationLibrary.of(model, null, new ClipCompiler());
            } catch (IOException e) {
                failure = e;
            }
        }
        return library;
    }

    /** Why {@link #library()} returned null, if it did. */
    public static synchronized IOException failure() {
        return failure;
    }

    /** @return true if the model is the built-in default itself (it has no fallback) */
    public static boolean isDefault(ModelSource source) {
        return source instanceof ClasspathModelSource && BASE.equals(((ClasspathModelSource) source).base());
    }
}
