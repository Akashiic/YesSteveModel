/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/bone/Vector3v.java and the FloatExpression literal/scaled
 * forms, Apache License 2.0.
 * Changes: evaluates into a caller array; constant programs are folded once at compile time.
 */
package com.akashic.futura.anim;

import com.akashic.futura.molang.MolangContext;
import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/** Three keyframe components, each a constant or a Molang expression, with a unit factor already folded in. */
public final class Vec3Expr {

    /** One component. */
    public static final class Scalar {

        /** null for a constant */
        final MolangExpression expression;
        final float constant;
        final float factor;

        Scalar(MolangExpression expression, float constant, float factor) {
            this.expression = expression;
            this.constant = constant;
            this.factor = factor;
        }

        static Scalar constant(float value) {
            return new Scalar(null, value, 1);
        }

        /** An expression times {@code factor}; folded to a constant if the expression is constant. */
        static Scalar of(MolangExpression expression, float factor) {
            if (expression.isConstant()) {
                return new Scalar(null, (float) expression.evalNumber(NO_CONTEXT) * factor, 1);
            }
            return new Scalar(expression, 0, factor);
        }

        Scalar scaled(float by) {
            return expression == null ? new Scalar(null, constant * by, 1) : new Scalar(expression, 0, factor * by);
        }

        public boolean isConstant() {
            return expression == null;
        }

        /** Upstream evaluates programs as 32-bit floats; anything not finite becomes 0. */
        public float eval(MolangContext context) {
            if (expression == null) {
                return constant;
            }
            return AnimMath.finite((float) expression.evalNumber(context) * factor);
        }
    }

    public final Scalar x;
    public final Scalar y;
    public final Scalar z;

    Vec3Expr(Scalar x, Scalar y, Scalar z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    static Vec3Expr constant(float x, float y, float z) {
        return new Vec3Expr(Scalar.constant(x), Scalar.constant(y), Scalar.constant(z));
    }

    public boolean isConstant() {
        return x.isConstant() && y.isConstant() && z.isConstant();
    }

    public void eval(MolangContext context, float[] out, int o) {
        out[o] = x.eval(context);
        out[o + 1] = y.eval(context);
        out[o + 2] = z.eval(context);
    }

    /** Folds constant programs; never asked for anything but a constant. */
    static final MolangContext NO_CONTEXT = new MolangContext() {

        private final MolangVariables variables = new MolangVariables();

        @Override
        public MolangValue call(String namespace, String name, MolangValue[] args) {
            return null;
        }

        @Override
        public MolangVariables variables() {
            return variables;
        }
    };
}
