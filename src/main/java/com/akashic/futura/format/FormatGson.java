package com.akashic.futura.format;

import com.akashic.futura.format.pojo.animation.Animation;
import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.animation.BoneAnimation;
import com.akashic.futura.format.pojo.animation.keyframe.BoneKeyFrameList;
import com.akashic.futura.format.pojo.animation.value.UnionValue;
import com.akashic.futura.format.pojo.animation.value.Vector3v;
import com.akashic.futura.format.pojo.controller.AnimationEntry;
import com.akashic.futura.format.pojo.controller.BlendTransition;
import com.akashic.futura.format.pojo.controller.Transition;
import com.akashic.futura.format.pojo.manifest.models.ModelFilesWarper;
import com.akashic.futura.format.pojo.manifest.models.PBRTextureSet;
import com.akashic.futura.format.pojo.model.CubeUv;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The Gson setup for the model format. Upstream binds its adapters with {@code @JsonAdapter}, which Gson 2.2.4 (the
 * version Minecraft 1.7.10 ships) does not have; they are registered here instead.
 */
public final class FormatGson {

    private FormatGson() {}

    /** A Gson for manifests ({@code ysm.json}), geometry, animations and controllers. */
    public static Gson create() {
        return create(false);
    }

    /**
     * @param mergeInstKeyframeLines join each {@code timeline} array into one program
     *                               ({@code properties.merge_multiline_expr})
     */
    public static Gson create(boolean mergeInstKeyframeLines) {
        return new GsonBuilder()
            .registerTypeAdapter(AnimationFile.class, new AnimationFile.Adapter(mergeInstKeyframeLines))
            .registerTypeAdapter(Animation.class, new Animation.Adapter())
            .registerTypeAdapter(BoneAnimation.class, new BoneAnimation.Adapter())
            .registerTypeAdapter(BoneKeyFrameList.class, new BoneKeyFrameList.Adapter())
            .registerTypeAdapter(UnionValue.class, new UnionValue.Adapter())
            .registerTypeAdapter(Vector3v.class, new Vector3v.Adapter())
            .registerTypeAdapter(AnimationEntry.class, new AnimationEntry.Adapter())
            .registerTypeAdapter(BlendTransition.class, new BlendTransition.Adapter())
            .registerTypeAdapter(Transition.class, new Transition.Adapter())
            .registerTypeAdapter(PBRTextureSet.class, new PBRTextureSet.Adapter())
            .registerTypeAdapter(CubeUv.class, new CubeUv.Adapter())
            .registerTypeAdapterFactory(new ModelFilesWarper.AdapterFactory())
            .create();
    }
}
