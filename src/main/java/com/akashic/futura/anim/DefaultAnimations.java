package com.akashic.futura.anim;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ModelSource;
import com.akashic.futura.model.ReplacementTarget;

/**
 * The built-in default model's animations, loaded once: the fallback for every other model (upstream "default
 * animations"). Loading fails soft: without them, missing animations simply do not play.
 */
public final class DefaultAnimations {

    public static final String BASE = "/assets/futura3d/builtin/default/";

    private static AnimationLibrary library;
    private static AnimationLibrary firstPerson;
    /** The default model's projectile and vehicle animations by target domain ({@link ReplacementTarget#domain}). */
    private static Map<String, AnimationLibrary> replacements = Collections.emptyMap();
    private static boolean attempted;
    private static IOException failure;

    private DefaultAnimations() {}

    /** @return the default library, or null if the default model could not be read */
    public static synchronized AnimationLibrary library() {
        if (!attempted) {
            attempted = true;
            try {
                LoadedModel model = ModelLoader.load(new ClasspathModelSource(BASE));
                ClipCompiler compiler = new ClipCompiler();
                library = AnimationLibrary.of(model, null, compiler);
                firstPerson = AnimationLibrary.ofFirstPerson(model, null, compiler);
                Map<String, AnimationLibrary> byDomain = new HashMap<>();
                for (ReplacementTarget target : model.replacements()) {
                    // as upstream's default sources: the first target of a domain
                    if (!byDomain.containsKey(target.domain())) {
                        byDomain.put(target.domain(), EntityAnimations.library(target, null, compiler));
                    }
                }
                replacements = byDomain;
            } catch (IOException e) {
                failure = e;
            }
        }
        return library;
    }

    /** @return the default model's first-person ({@code fp_arm}) library, or null if it could not be read */
    public static synchronized AnimationLibrary firstPersonLibrary() {
        library();
        return firstPerson;
    }

    /**
     * The default model's animations for a projectile or vehicle target domain ({@link ReplacementTarget#domain}), or
     * null when the default model has no target with that match list (upstream's default animation domains).
     */
    public static synchronized AnimationLibrary replacementLibrary(String domain) {
        library();
        return replacements.get(domain);
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
