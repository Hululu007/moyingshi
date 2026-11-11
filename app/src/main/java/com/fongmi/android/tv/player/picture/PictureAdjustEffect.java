package com.fongmi.android.tv.player.picture;

import android.content.Context;
import android.opengl.GLES20;

import androidx.media3.common.Effect;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import com.github.catvod.crawler.SpiderDebug;

import java.util.Collections;
import java.util.List;

/**
 * 画面调节五项的 Exo 效果器（B6）。套路照抄 DynamicLutEffect：GlEffect +
 * BaseGlShaderProgram + 内嵌 GLSL，set() 只改 volatile 状态即可实时生效，不用重建
 * effect、不用重调 setVideoEffects。
 *
 * 滑杆值 s ∈ [-100, 100]（默认 0）到 shader 参数的映射见 docs/b6-picture-adjust-anime4k.md §2.4；
 * 与 mpv 原生属性的映射不完全一致是已知差异，默认 0 时两者均为原样。
 * HDR 不支持（与 DynamicLutEffect 同款：useHdr 时抛错，由 PlayerManager 门禁挡住）。
 */
public class PictureAdjustEffect implements GlEffect {

    private final List<Effect> effects = Collections.singletonList(this);
    private volatile State state = new State(PictureAdjust.NEUTRAL, 0);
    private int serial;

    public List<Effect> effects() {
        return effects;
    }

    public synchronized void set(PictureAdjust adjust) {
        if (adjust == null) adjust = PictureAdjust.NEUTRAL;
        state = new State(adjust, ++serial);
    }

    public synchronized void clear() {
        state = new State(PictureAdjust.NEUTRAL, ++serial);
    }

    public PictureAdjust getAdjust() {
        return state.adjust;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new PictureAdjustShaderProgram(context, this, useHdr);
    }

    @Override
    public boolean isNoOp(int inputWidth, int inputHeight) {
        // 与 DynamicLutEffect 同款：恒 false 保管线温热；中性时 shader 内短路原样输出。
        return false;
    }

    private State getState() {
        return state;
    }

    private static class State {
        private final PictureAdjust adjust;
        private final int serial;

        private State(PictureAdjust adjust, int serial) {
            this.adjust = adjust;
            this.serial = serial;
        }
    }

    private static class PictureAdjustShaderProgram extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
                "#version 100\n" +
                "attribute vec4 aFramePosition;\n" +
                "uniform mat4 uTransformationMatrix;\n" +
                "uniform mat4 uTexTransformationMatrix;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uTransformationMatrix * aFramePosition;\n" +
                "  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n" +
                "  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#version 100\n" +
                "precision highp float;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform float uEnabled;\n" +
                "uniform float uBrightness;\n" +
                "uniform float uContrast;\n" +
                "uniform float uSaturation;\n" +
                "uniform float uHue;\n" +
                "uniform float uGamma;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "const vec3 kRGBToYPrime = vec3(0.299, 0.587, 0.114);\n" +
                "const vec3 kRGBToI = vec3(0.596, -0.274, -0.322);\n" +
                "const vec3 kRGBToQ = vec3(0.211, -0.523, 0.312);\n" +
                "const vec3 kYIQToR = vec3(1.0, 0.956, 0.619);\n" +
                "const vec3 kYIQToG = vec3(1.0, -0.272, -0.647);\n" +
                "const vec3 kYIQToB = vec3(1.0, -1.107, 1.704);\n" +
                "void main() {\n" +
                "  vec4 inputColor = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "  if (uEnabled < 0.5) {\n" +
                "    gl_FragColor = inputColor;\n" +
                "    return;\n" +
                "  }\n" +
                "  vec3 color = inputColor.rgb;\n" +
                "  color += uBrightness;\n" +
                "  color = (color - 0.5) * uContrast + 0.5;\n" +
                "  float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));\n" +
                "  color = mix(vec3(luma), color, uSaturation);\n" +
                "  vec3 yiq = vec3(dot(color, kRGBToYPrime), dot(color, kRGBToI), dot(color, kRGBToQ));\n" +
                "  float hue = atan(yiq.z, yiq.y) + uHue;\n" +
                "  float chroma = length(yiq.yz);\n" +
                "  yiq.y = chroma * cos(hue);\n" +
                "  yiq.z = chroma * sin(hue);\n" +
                "  color = vec3(dot(yiq, kYIQToR), dot(yiq, kYIQToG), dot(yiq, kYIQToB));\n" +
                "  color = pow(max(color, vec3(0.0)), vec3(1.0 / uGamma));\n" +
                "  gl_FragColor.rgb = clamp(color, 0.0, 1.0);\n" +
                "  gl_FragColor.a = inputColor.a;\n" +
                "}\n";

        private final PictureAdjustEffect effect;
        private final GlProgram glProgram;
        private int loggedSerial = Integer.MIN_VALUE;

        private PictureAdjustShaderProgram(Context context, PictureAdjustEffect effect, boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 1);
            if (useHdr) throw new VideoFrameProcessingException("PictureAdjustEffect does not support HDR colors.");
            this.effect = effect;
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
                float[] identity = GlUtil.create4x4IdentityMatrix();
                glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
                glProgram.setFloatsUniform("uTransformationMatrix", identity);
                glProgram.setFloatsUniform("uTexTransformationMatrix", identity);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            return new Size(inputWidth, inputHeight);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                State state = effect.getState();
                logState(state);
                PictureAdjust adjust = state.adjust;
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                glProgram.setFloatUniform("uEnabled", adjust.isNeutral() ? 0f : 1f);
                // 亮度：s/100 → ±1.0 偏移；对比度：1+s/100 → 0..2；饱和度：1+s/100 → 0..2。
                glProgram.setFloatUniform("uBrightness", adjust.getBrightness() / 100f);
                glProgram.setFloatUniform("uContrast", 1f + adjust.getContrast() / 100f);
                glProgram.setFloatUniform("uSaturation", 1f + adjust.getSaturation() / 100f);
                // 色相：s/100*π（±100 转 180°）；伽马：10^(s/100) → 0.1..10。
                glProgram.setFloatUniform("uHue", (float) (adjust.getHue() / 100.0 * Math.PI));
                glProgram.setFloatUniform("uGamma", (float) Math.pow(10.0, adjust.getGamma() / 100.0));
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } catch (RuntimeException | GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                super.release();
                glProgram.delete();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        private void logState(State state) {
            if (!SpiderDebug.isEnabled() || loggedSerial == state.serial) return;
            loggedSerial = state.serial;
            SpiderDebug.log("pic-exo", "draw serial=%d adjust=%s", state.serial, state.adjust);
        }
    }
}
