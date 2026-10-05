package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.lwjgl.opengl.GL32.*;

/** Optional GPU regression: gradlew lightingShaderRegression; uses a hidden OpenGL window. */
public final class LightMaskShaderRegression {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static int program, checks;
    private record Sample(int color, float strength, int blockUv, int skyUv, float skyBrightness, float ambient, int modelColor) {
        Sample(int color, float strength, int blockUv, int skyUv, float skyBrightness, float ambient) {
            this(color, strength, blockUv, skyUv, skyBrightness, ambient, 0xFFFFFFFF);
        }
    }

    public static void main(String[] args) throws Exception {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "OpalLight shader regression", 0, 0);
        if (window == 0) throw new AssertionError("Hidden OpenGL context creation failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            String fog;
            try (ZipFile resources = new ZipFile(args[1])) {
                fog = new String(resources.getInputStream(resources.getEntry("assets/minecraft/shaders/include/fog.glsl")).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).replace("#version 150", "");
            }
            Path shaders = Path.of(args[0], "src/main/resources/assets/opallight/shaders/core");
            String light = Files.readString(shaders.resolve("../include/light.glsl"));
            int vertex = compile(GL_VERTEX_SHADER, Files.readString(shaders.resolve("light_mask.vsh")), fog, light);
            int fragment = compile(GL_FRAGMENT_SHADER, Files.readString(shaders.resolve("light_mask.fsh")), fog, light);
            program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            var format = LightMaskMeshBuilder.VERTEX_FORMAT;
            var attributes = format.getElementAttributeNames();
            for (int i = 0; i < attributes.size(); i++) glBindAttribLocation(program, i, attributes.get(i));
            glLinkProgram(program);
            check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE, glGetProgramInfoLog(program));
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            glUseProgram(program);
            glUniformMatrix4fv(uniform("ModelViewMat"), false, IDENTITY);
            glUniformMatrix4fv(uniform("ProjMat"), false, IDENTITY);
            glUniform1i(uniform("Sampler0"), 0);
            glUniform3f(uniform("GroupOffset"), 0, 0, 0);
            glUniform1f(uniform("FogStart"), 100);
            glUniform1f(uniform("FogEnd"), 200);
            glUniform4f(uniform("FogColor"), 0, 0, 0, 0);
            glUniform1i(uniform("FogShape"), 0);
            glUniform1f(uniform("TransitionWeight"), 1);
            glUniform4f(uniform("LightSettings"), LightBrightness.MASK_INTENSITY, LightBrightness.TINT_INTENSITY,
                    LightBrightness.MIN_DAY_TINT_VISIBILITY, LightFalloff.EDGE_FADE_STRENGTH);

            int texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    ByteBuffer.allocateDirect(4).putInt(-1).flip());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            int target = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 16, 16, 0, GL_RGBA, GL_FLOAT, (FloatBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, glGenFramebuffers());
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "incomplete framebuffer");
            glBindTexture(GL_TEXTURE_2D, texture);
            glViewport(0, 0, 16, 16);
            glBindVertexArray(glGenVertexArrays());
            glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
            for (int i = 0; i < attributes.size(); i++) {
                VertexFormatElement element = format.getElements().get(i);
                if (element == VertexFormatElement.UV2 || element == VertexFormatElement.UV1) {
                    glVertexAttribIPointer(i, 2, GL_SHORT, format.getVertexSize(), format.getOffset(element));
                } else {
                    glVertexAttribPointer(i, element.count(), element == VertexFormatElement.COLOR ? GL_UNSIGNED_BYTE : GL_FLOAT,
                            element == VertexFormatElement.COLOR, format.getVertexSize(), format.getOffset(element));
                }
                glEnableVertexAttribArray(i);
            }
            glEnable(GL_BLEND);
            // Blue light must replace vanilla neutral/warm illumination, not merely tint it weakly.
            float[] brightRed = render(new Sample(0xFF0000, 1, 240, 0, 1, 0), 1);
            near(brightRed[0], 1, "fully lit surfaces must not become brighter");
            near(brightRed[1], 0.03575F, "a tone-mapped red source must remove most of the green component of vanilla white light");
            near(brightRed[2], 0.03575F, "a tone-mapped red source must remove most of the blue component of vanilla white light");
            Sample blue = new Sample(0x0000FF, 1, 240, 0, 1, 0);
            float[] warmBlue = render(blue, new float[]{1, 0.7F, 0.3F});
            near(warmBlue[0], 0.03575F, "blue lanterns must remove most of the red component of vanilla warm lighting");
            near(warmBlue[1], 0.025025F, "blue lanterns must remove most of the green component of vanilla warm lighting");
            near(warmBlue[2], 0.4995F, "blue-channel brightness must retain its original cap");
            float[] nearBlue = render(new Sample(0x0000FF, 1, 240, 0, 1, 0), 1);
            check(nearBlue[0] < 0.04F && nearBlue[1] < 0.04F && nearBlue[2] > 0.99F,
                    "a tone-mapped blue source must remain blue rather than pale vanilla white");
            float[] distantBlue = render(new Sample(0x0000FF, 0.2F, 48, 0, 1, 0), 0.1F);
            near(distantBlue[0], 0, "a distant sole blue source must not return to neutral white");
            near(distantBlue[1], 0, "a distant sole blue source must not return to neutral white");
            check(distantBlue[2] < 0.16F, "distant blue illumination must retain attenuation");
            Sample blueCore = new Sample(0x3333FF, 0.95F, 240, 0, 1, 0);
            float[] core = render(blueCore, 1);
            float[] expectedCore = expected(blueCore, new float[]{1, 1, 1});
            for (int i = 0; i < 3; i++) near(core[i], expectedCore[i], "a 20% white core must retain the blue hue and brightness cap");
            check(core[0] > 0.1F && core[0] < 0.3F && core[1] < 0.3F && core[2] > 0.99F,
                    "the small white core must soften blue without making the lantern neutral white");
            for (int step = 0; step <= 20; step++) {
                for (int sky : new int[]{0, 240}) {
                    Sample sample = new Sample(0x0000FF, step / 100F, 16, sky, 1, 0);
                    float[] actual = render(sample, 0.1F);
                    float[] expected = expected(sample, new float[]{0.1F, 0.1F, 0.1F});
                    for (int i = 0; i < 3; i++) near(actual[i], expected[i],
                            "edge tint and brightness must fade together in caves and daylight");
                }
            }
            // One face spans all three edge levels. Check each pixel against fragment-level falloff.
            Sample edgeLeft = new Sample(0x0000FF, 1 / 15F, 16, 0, 1, 0);
            Sample edgeRight = new Sample(0x0000FF, 3 / 15F, 16, 0, 1, 0);
            render(edgeLeft, edgeRight, new float[]{0.1F, 0.1F, 0.1F});
            float previousRed = 1;
            for (int x = 0; x < 16; x++) {
                float fraction = (x + 0.5F) / 16;
                float strength = edgeLeft.strength() + (edgeRight.strength() - edgeLeft.strength()) * fraction;
                float[] actual = read(x, 8);
                float[] expected = expected(edgeLeft, new float[]{0.1F, 0.1F, 0.1F}, strength);
                for (int i = 0; i < 3; i++) near(actual[i], expected[i],
                        "large faces must use a smooth per-fragment edge gradient");
                check(actual[0] <= previousRed, "edge tint must change continuously across a face");
                previousRed = actual[0];
            }
            float[] dayRed = render(new Sample(0xFF0000, 1, 240, 240, 1, 0), 0.8F);
            check(dayRed[0] - dayRed[1] > 0.05F, "daylight must retain a subtle visible hue");
            near(dayRed[0], 0.8F, "daylight tint must not add brightness");
            float[] whiteDay = render(new Sample(0xFFFFFF, 1, 240, 240, 1, 0), 0.8F);
            for (float channel : whiteDay) near(channel, 0.8F, "white sources must not add daylight brightness");
            float[] vegetation = render(new Sample(0xFFFFFF, 1, 240, 240, 1, 0, 0xFF408040), 0.8F);
            for (float channel : vegetation) near(channel, 0.8F, "model vegetation tint must not count as light saturation");

            for (int color : new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x8040FF}) {
                for (int sky : new int[]{0, 160, 240}) {
                    for (float brightness : new float[]{0.24F, 1}) {
                        for (float base : new float[]{0.1F, 0.9F, 1}) {
                            Sample sample = new Sample(color, 1, 240, sky, brightness, 0);
                            float[] actual = render(sample, base);
                            float[] expected = expected(sample, new float[]{base, base, base});
                            for (int channel = 0; channel < 3; channel++) {
                                near(actual[channel], expected[channel],
                                        "GPU tint and brightness must agree with the Java visibility policy");
                                check(actual[channel] >= 0 && actual[channel] <= 1.00001F, "colored light must remain bounded");
                            }
                        }
                    }
                }
            }
            for (float ambient : new float[]{0.1F, 0.8F}) {
                Sample sample = new Sample(0xFF0000, 1, 240, 160, 1, ambient);
                float[] actual = render(sample, 0.1F);
                float[] expected = expected(sample, new float[]{0.1F, 0.1F, 0.1F});
                for (int i = 0; i < 3; i++) near(actual[i], expected[i], "dimension ambient light must adjust tint visibility");
            }
            for (Sample sample : new Sample[]{
                    new Sample(0x0000FF, 0.2F, 240, 0, 1, 0),
                    new Sample(0x0000FF, 1, 240, 0, 1, 0, 0xFF408040),
                    new Sample(0x0000FF, 0.5F, 160, 80, 0.24F, 0, 0x80408040)}) {
                float[] actual = render(sample, 0.4F);
                float[] expected = expected(sample, new float[]{0.4F, 0.4F, 0.4F});
                for (int i = 0; i < 3; i++) near(actual[i], expected[i], "color shares and model AO/alpha must remain separate");
            }
            long[] mixedColors = {
                    LightColorData.pack(0.6, 0, 0.6),
                    LightColorData.pack(0.6, 0.6, 0),
                    LightColorData.pack(0, 0.6, 0.6),
                    LightColorData.pack(0.6, 0.6, 0.6),
                    LightColorData.pack(0.8, 0, 0.4),
                    LightColorData.pack(48, 0, 0)
            };
            for (long mixed : mixedColors) {
                float strength = Math.max(LightColorData.channel(mixed, 32),
                        Math.max(LightColorData.channel(mixed, 16), LightColorData.channel(mixed, 0)));
                int hue = 0;
                for (int i = 0; i < 3; i++) hue |= Math.round(LightColorData.channel(mixed, 32 - i * 16) / strength * 255) << (16 - i * 8);
                for (int sky : new int[]{0, 240}) {
                    Sample sample = new Sample(hue, strength, Math.min(240, Math.round(strength * 240)), sky, 1, 0);
                    float[] actual = render(sample, 0.1F);
                    float[] expected = expected(sample, new float[]{0.1F, 0.1F, 0.1F});
                    for (int i = 0; i < 3; i++) {
                        near(actual[i], expected[i], "mixed colors must retain their hue through mask vertex encoding and both shader passes");
                        check(actual[i] <= 0.37F, "dense mixed light must retain the final illumination cap");
                    }
                    if (sky == 0 && hue == 0xFF00FF) {
                        check(actual[0] > 0.2F && actual[2] > 0.2F && actual[1] < 0.001F,
                                "equal red and blue contributions must display purple rather than white");
                    }
                    if (sky == 240 && hue == 0xFFFFFF) {
                        for (float channel : actual) near(channel, 0.1F, "three primary colors mixing to white must not brighten full daylight");
                    }
                }
            }
            // Reproduce the screenshot's four weak light tails on biome-tinted grass under moonlight.
            long groundMix = LightColorData.pack(5 / 15.0, 6 / 15.0, 2 / 15.0);
            float groundStrength = LightColorData.channel(groundMix, 16);
            int groundHue = Math.round(LightColorData.channel(groundMix, 32) / groundStrength * 255) << 16
                    | 255 << 8 | Math.round(LightColorData.channel(groundMix, 0) / groundStrength * 255);
            Sample groundOverlap = new Sample(groundHue, groundStrength, 48, 240, 0.24F, 0, 0xFF91BD59);
            float[] groundBase = {0.12F, 0.16F, 0.07F};
            float[] groundActual = render(groundOverlap, groundBase);
            float[] groundExpected = expected(groundOverlap, groundBase);
            for (int i = 0; i < 3; i++) near(groundActual[i], groundExpected[i],
                    "mixed weak tails must preserve their ratios through grass tint and moonlight suppression");
            check(groundActual[2] < 0.05F && groundActual[1] > 0.18F,
                    "the weaker blue tail must not wash the grass overlap into a gray-white strip");
            // HDR contributions, not separately normalized hues, must interpolate across the face.
            Sample strongRed = new Sample(0xFF0000, 4, 240, 0, 1, 0);
            Sample weakBlue = new Sample(0x0000FF, 1, 240, 0, 1, 0);
            float[] gradientBase = {0.1F, 0.1F, 0.1F};
            render(strongRed, weakBlue, gradientBase);
            for (int x = 0; x < 16; x++) {
                float fraction = (x + 0.5F) / 16;
                float[] raw = {4 * (1 - fraction), 0, fraction};
                float peak = Math.max(raw[0], raw[2]);
                float[] expected = expected(strongRed, gradientBase, peak, new float[]{raw[0] / peak, 0, raw[2] / peak});
                float[] actual = read(x, 8);
                for (int i = 0; i < 3; i++) near(actual[i], expected[i],
                        "per-fragment normalization must preserve unequal HDR contributions across a face");
            }
            mixingSeams();
            for (float strength : new float[]{0.899F, 0.9F, 0.901F, 1, 2, 48, 4096}) {
                Sample sample = new Sample(0xFF8040, strength, 240, 0, 1, 0);
                float[] actual = render(sample, 0.1F);
                float[] expected = expected(sample, gradientBase);
                for (int i = 0; i < 3; i++) near(actual[i], expected[i],
                        "GLSL tone mapping must remain continuous and bounded across HDR values");
            }
            float[] transparent = render(new Sample(0x0000FF, 48, 240, 0, 1, 0, 0x00408040), 0.1F);
            for (float channel : transparent) near(channel, 0.1F, "transparent model vertices must not filter or illuminate surfaces");
            float[] none = render(new Sample(0xFF0000, 0, 240, 0, 1, 0), 0.4F);
            for (float channel : none) near(channel, 0.4F, "zero-strength light must leave surfaces unchanged");
            float[] stable = render(blue, 0.1F);
            clear(0.1F);
            glUniform1f(uniform("TransitionWeight"), 0.5F);
            drawPass(true, 2);
            drawPass(false, 2);
            float[] transition = read();
            for (int i = 0; i < 3; i++) near(transition[i], stable[i], "unchanged color must not flicker during mesh crossfade");
            glUniform1f(uniform("TransitionWeight"), 1);
            glUniform1f(uniform("FogStart"), 0);
            glUniform1f(uniform("FogEnd"), 0.1F);
            glUniform4f(uniform("FogColor"), 0, 0, 0, 1);
            float[] fogged = render(blue, 0.1F);
            for (float channel : fogged) near(channel, 0.1F, "fully fogged light must remain invisible");
            check(glGetError() == GL_NO_ERROR, "OpenGL error during shader regression");
            System.out.println("Lighting shader regression: " + checks + " checks passed on " + glGetString(GL_RENDERER));
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            errors.free();
        }
    }

    private static int compile(int type, String source, String fog, String light) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source.replace("#moj_import <fog.glsl>", fog).replace("#moj_import <opallight:light.glsl>", light));
        glCompileShader(shader);
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE, glGetShaderInfoLog(shader));
        return shader;
    }

    private static int uniform(String name) {
        int location = glGetUniformLocation(program, name);
        check(location >= 0, "missing uniform " + name);
        return location;
    }

    private static float[] render(Sample sample, float base) {
        return render(sample, new float[]{base, base, base});
    }

    private static float[] render(Sample sample, float[] base) {
        return render(sample, sample, base);
    }

    private static float[] render(Sample left, Sample right, float[] base) {
        return render(left, right, base, contributions(left), contributions(right));
    }

    private static float[] contributions(Sample sample) {
        return new float[]{(sample.color() >>> 16 & 255) / 255F * sample.strength(),
                (sample.color() >>> 8 & 255) / 255F * sample.strength(),
                (sample.color() & 255) / 255F * sample.strength()};
    }

    private static float[] render(Sample left, Sample right, float[] base, float[] leftRaw, float[] rightRaw) {
        clear(base);
        var format = LightMaskMeshBuilder.VERTEX_FORMAT;
        ByteBuffer vertices = MemoryUtil.memAlloc(4 * format.getVertexSize());
        try {
            for (int i = 0; i < 4; i++) {
                Sample sample = (i & 1) == 0 ? left : right;
                float[] raw = (i & 1) == 0 ? leftRaw : rightRaw;
                LightMaskVertex.write(vertices, (i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, 0,
                        0.5F, 0.5F, sample.modelColor(), (sample.modelColor() >>> 24) / 255F, sample.blockUv(), sample.skyUv(),
                        raw[0], raw[1], raw[2]);
            }
            glBufferData(GL_ARRAY_BUFFER, vertices.flip(), GL_DYNAMIC_DRAW);
        } finally { MemoryUtil.memFree(vertices); }
        glUniform1f(uniform("SkyBrightness"), left.skyBrightness());
        glUniform1f(uniform("AmbientLight"), left.ambient());
        drawPass(true, 1);
        drawPass(false, 1);
        return read();
    }

    private static void drawPass(boolean tint, int count) {
        glUniform1i(uniform("TintPass"), tint ? 1 : 0);
        glBlendFuncSeparate(tint ? GL_DST_COLOR : GL_SRC_ALPHA, tint ? GL_ZERO : GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        for (int i = 0; i < count; i++) glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void mixingSeams() {
        Sample surface = new Sample(0, 0, 16, 0, 1, 0);
        float[] base = {0.12F, 0.12F, 0.12F};
        // Inspect the actual two-pass output on both sides of each dominant-channel switch.
        // A hard max has a cusp here even though the incoming RGB field is linear.
        for (int first = 0; first < 3; first++) {
            int second = (first + 1) % 3;
            int third = (first + 2) % 3;
            for (float level : new float[]{0.4F, 2, 32}) {
                for (float neutralShare : new float[]{0, 0.2F}) {
                    float delta = level * 0.001F;
                    float[][] pixels = new float[3][];
                    for (int step = -1; step <= 1; step++) {
                        float[] raw = new float[3];
                        raw[first] = level + step * delta;
                        raw[second] = level - step * delta;
                        raw[third] = level * neutralShare;
                        pixels[step + 1] = render(surface, surface, base, raw, raw);
                        for (float channel : pixels[step + 1]) check(channel >= 0 && channel <= 1,
                                "rounding a mixing seam must retain the brightness cap");
                        if (neutralShare == 0) near(pixels[step + 1][third], 0,
                                "smoothing red/green/blue overlaps must not add a white channel");
                        check(Math.abs(pixels[step + 1][first] * raw[second]
                                        - pixels[step + 1][second] * raw[first]) < level * 0.0001F,
                                "seam smoothing must preserve the arriving RGB ratio");
                    }
                    for (int channel = 0; channel < 3; channel++) {
                        float leftSlope = (pixels[1][channel] - pixels[0][channel]) / 0.001F;
                        float rightSlope = (pixels[2][channel] - pixels[1][channel]) / 0.001F;
                        check(Math.abs(leftSlope - rightSlope) < 0.03F,
                                "mixed color must have a continuous slope when dominant channels swap: "
                                        + leftSlope + " != " + rightSlope);
                    }
                    near(pixels[0][first], pixels[2][second], "mixing seams must be symmetric between colors");
                    near(pixels[1][first], pixels[1][second], "equal contributions must remain equal at the seam");
                }
            }
        }
        // The width scales with chromatic energy. Its outer joins must also be smooth.
        for (float join : new float[]{0.375F, 0.625F}) {
            float[][] pixels = new float[3][];
            for (int step = -1; step <= 1; step++) {
                float fraction = join + step * 0.001F;
                float[] raw = {fraction, 0, 1 - fraction};
                pixels[step + 1] = render(surface, surface, base, raw, raw);
            }
            for (int channel = 0; channel < 3; channel++) {
                float leftSlope = (pixels[1][channel] - pixels[0][channel]) / 0.001F;
                float rightSlope = (pixels[2][channel] - pixels[1][channel]) / 0.001F;
                check(Math.abs(leftSlope - rightSlope) < 0.03F,
                        "the seam smoothing region must join the original colors without a new edge");
            }
        }
    }

    private static float[] expected(Sample sample, float[] base) {
        return expected(sample, base, sample.strength());
    }

    private static float[] expected(Sample sample, float[] base, float rawStrength) {
        float[] hue = new float[3];
        for (int i = 0; i < 3; i++) hue[i] = (sample.color() >>> (16 - i * 8) & 255) / 255F;
        return expected(sample, base, rawStrength, hue);
    }

    private static float[] expected(Sample sample, float[] base, float rawStrength, float[] hue) {
        float[] raw = {hue[0] * rawStrength, hue[1] * rawStrength, hue[2] * rawStrength};
        float neutral = Math.min(raw[0], Math.min(raw[1], raw[2]));
        float peak = Math.max(raw[0], Math.max(raw[1], raw[2]));
        float chromaSum = raw[0] + raw[1] + raw[2] - 3 * neutral;
        float width = 0.25F * chromaSum;
        float gap = 2 * (peak - neutral) - chromaSum;
        float overlap = width > 0 ? Math.max(0, 1 - gap / width) : 0;
        float colorStrength = peak + 0.25F * width * overlap * overlap;
        hue = new float[3];
        for (int i = 0; i < 3; i++) hue[i] = colorStrength > 0 ? raw[i] / colorStrength : 0;
        float strength = LightBrightness.mappedStrength(colorStrength);
        float[] result = new float[3];
        float saturation = 1 - Math.min(hue[0], Math.min(hue[1], hue[2]));
        float alpha = (sample.modelColor() >>> 24) / 255F;
        float tint = LightingRegressionTest.DisplayReference.tintStrength(strength, sample.blockUv(), sample.skyUv(),
                sample.skyBrightness(), sample.ambient(), saturation) * alpha;
        float opacity = strength * alpha * LightBrightness.MASK_INTENSITY
                * LightFalloff.edgeOpacity(strength) * LightBrightness.skyVisibility(sample.skyUv(), sample.skyBrightness(), sample.ambient());
        for (int i = 0; i < 3; i++) {
            float model = (sample.modelColor() >>> (16 - i * 8) & 255) / 255F;
            result[i] = base[i] * (1 - tint * (1 - hue[i])) * (1 - opacity) + hue[i] * model * opacity;
        }
        return result;
    }

    private static void clear(float base) {
        clear(new float[]{base, base, base});
    }

    private static void clear(float[] base) {
        glClearColor(base[0], base[1], base[2], 0.7F);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static float[] read() {
        return read(8, 8);
    }

    private static float[] read(int x, int y) {
        FloatBuffer pixels = MemoryUtil.memAllocFloat(4);
        try {
            glReadPixels(x, y, 1, 1, GL_RGBA, GL_FLOAT, pixels);
            near(pixels.get(3), 0.7F, "lighting must preserve framebuffer alpha");
            return new float[]{pixels.get(0), pixels.get(1), pixels.get(2)};
        } finally { MemoryUtil.memFree(pixels); }
    }

    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.0001F, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
