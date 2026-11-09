package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.GeoBaker;
import com.akashic.futura.molang.MolangContext;
import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;

/** Builders shared by the animation tests. Molang is stubbed: they never depend on the real interpreter. */
final class AnimTestSupport {

    static final float EPS = 1e-4f;
    static final float DEG = (float) (Math.PI / 180);

    private AnimTestSupport() {}

    /**
     * Stub Molang: numbers are constants, {@code ns.name} (no arguments) asks the context, anything else is 0. Enough
     * to check that the runtime evaluates expressions against the right context without the real interpreter.
     */
    static MolangExpression stubParse(String source) {
        String s = source == null ? "" : source.trim();
        try {
            return MolangExpression.constant(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            // not a number
        }
        int dot = s.indexOf('.');
        if (dot > 0 && s.matches("[a-z_]+\\.[a-z_0-9]+")) {
            String ns = s.substring(0, dot);
            String name = s.substring(dot + 1);
            String namespace = ns.equals("q") ? "query" : ns;
            return new MolangExpression() {

                @Override
                public MolangValue evaluate(MolangContext context) {
                    MolangValue v = context.call(namespace, name, new MolangValue[0]);
                    return v == null ? MolangValue.ZERO : v;
                }
            };
        }
        return MolangExpression.constant(0);
    }

    static final ClipCompiler COMPILER = new ClipCompiler(AnimTestSupport::stubParse);

    /** Clips from the content of an animation file's {@code "animations"} object. */
    static Map<String, AnimationClip> clips(String animationsJson) {
        AnimationFile file = FormatGson.create()
            .fromJson("{\"format_version\":\"1.8.0\",\"animations\":{" + animationsJson + "}}", AnimationFile.class);
        Map<String, AnimationFile> sets = new java.util.LinkedHashMap<>();
        sets.put("main", file);
        return AnimationLibrary.compile(sets, COMPILER);
    }

    static AnimationLibrary library(String animationsJson, AnimationLibrary fallback) {
        return new AnimationLibrary(clips(animationsJson), fallback);
    }

    /** A geometry with the given bones; {@code "Name:Parent"} makes a child. Every bone has one cube. */
    static BakedGeometry geometry(String... bones) {
        StringBuilder sb = new StringBuilder("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"bones\":[");
        for (int i = 0; i < bones.length; i++) {
            String[] parts = bones[i].split(":");
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"")
                .append(parts[0])
                .append('"');
            if (parts.length > 1) {
                sb.append(",\"parent\":\"")
                    .append(parts[1])
                    .append('"');
            }
            sb.append(",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}");
        }
        sb.append("]}]}");
        try {
            return GeoBaker.bake(
                FormatGson.create()
                    .fromJson(sb.toString(), GeoModel.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A context that answers nothing but {@code query.anim_time}. */
    static final class TestContext extends AnimationContext {

        @Override
        public MolangValue call(String namespace, String name, MolangValue[] args) {
            if ("query".equals(namespace) && "anim_time".equals(name)) {
                return MolangValue.of((double) animTime());
            }
            return null;
        }
    }

    /** A processor with hand-made coded controllers. */
    static final class Rig implements AnimationPlayer.BoneIndex {

        final BakedGeometry geometry;
        final AnimationLibrary library;
        final AnimationProcessor processor;
        final TestContext ctx = new TestContext();
        final List<AnimationController> controllers = new ArrayList<>();

        Rig(BakedGeometry geometry, AnimationLibrary library) {
            this.geometry = geometry;
            this.library = library;
            float[] init = new float[geometry.bones.length * 3];
            for (int i = 0; i < geometry.bones.length; i++) {
                System.arraycopy(geometry.bones[i].rotation, 0, init, i * 3, 3);
            }
            this.processor = new AnimationProcessor(init);
        }

        CodedController add(String name, float transitionSeconds, boolean blendRotation,
            CodedController.Predicate predicate) {
            CodedController c = new CodedController(
                name,
                library,
                processor.committed,
                this,
                transitionSeconds,
                blendRotation,
                predicate);
            controllers.add(c);
            return c;
        }

        void tick(float renderTicks) {
            processor.tick(controllers, renderTicks, ctx, true);
        }

        /** Committed value: channel 0 rotation (radians), 1 position (pixels), 2 scale. */
        float value(String bone, int channel, int axis) {
            return processor.committed[geometry.indexOf(bone) * 9 + channel * 3 + axis];
        }

        @Override
        public int count() {
            return geometry.bones.length;
        }

        @Override
        public int indexOf(String bone) {
            return geometry.indexOf(bone);
        }
    }

    /** Plays one named clip forever. */
    static CodedController.Predicate play(String name) {
        return c -> {
            c.setAnimation(name);
            return PlayState.CONTINUE;
        };
    }
}
