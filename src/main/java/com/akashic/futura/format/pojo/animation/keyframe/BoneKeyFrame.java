/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/animation/keyframe/BoneKeyFrame.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.animation.keyframe;

import com.akashic.futura.format.pojo.animation.value.Vector3v;

public class BoneKeyFrame {

    public float startTick = 0;
    public EasingType easingType = EasingType.LINEAR;
    public Vector3v preValue;
    public Vector3v postValue;
}
