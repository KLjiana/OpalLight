package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.loading.LoadingModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongPredicate;

/** Run with gradlew lightingRegression; failures use assertions independent of JVM -ea. */
public final class LightingRegressionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // Only registry bootstrap is needed. Empty mod discovery avoids requiring the game launcher.
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        vertexPathsAndBrightness();
        daylightAndMaskBrightness();
        coreAndEdgeFalloff();
        multiSourceColorMixing();
        skyLightChangesRefreshGeometry();
        propagationAndOccludedSampling();
        movingSourcesConverge();
        geometryInvalidatesDeferredPropagation();
        meshColorUpdatesConverge();
        System.out.println("Lighting regression: " + checks + " checks passed");
    }

    private static void vertexPathsAndBrightness() {
        Capture capture = new Capture();
        VertexConsumer wrapped = ColoredLightBufferSource.wrapFixed(capture, 65535L << 32);
        wrapped.addVertex(1, 2, 3).setColor(120, 200, 80, 123).setUv2(32, 160);
        int chained = capture.color, chainedLight = capture.light;
        wrapped.addVertex(1, 2, 3, 0x7B78C850, 0.2F, 0.3F, 99, 160 << 16 | 32, 0, 1, 0);
        check(capture.color == chained, "bulk and chained vertices must receive identical tint and alpha");
        check(capture.color == 0x7B780000, "a saturated source must remove the unrelated white-light color channels");
        check(capture.light == chainedLight, "bulk and chained vertices must receive identical brightness");
        check((capture.light & 65535) == 192 && capture.light >>> 16 == 160,
                "colored light must raise block brightness while preserving sky light");
        check(capture.overlay == 99 && capture.x == 1 && capture.y == 2 && capture.z == 3,
                "bulk attributes must survive wrapping");
        wrapped.setUv2(240, 240);
        check(capture.light == (240 << 16 | 240), "existing brighter lighting must survive");
        long white = 65535L << 32 | 65535L << 16 | 65535L;
        VertexConsumer neutral = ColoredLightBufferSource.wrapFixed(capture, white);
        check(neutral != capture, "neutral colored light must still illuminate entities");
        neutral.addVertex(0, 0, 0).setColor(100, 150, 200, 255).setUv2(0, 0);
        check(capture.color == 0xFF6496C8 && capture.light == 192, "white light raises brightness without changing tint");
        check(ColoredLightBufferSource.wrapFixed(capture, 0) == capture, "unlit vertices should remain unwrapped");
    }

    private static void daylightAndMaskBrightness() throws Exception {
        check(LightBrightness.MASK_INTENSITY == 0.3F, "near-source overlay must be limited to 30% intensity");
        check(LightBrightness.skyVisibility(240, 1, 0) == 0, "full daylight must suppress extra illumination outdoors");
        check(LightBrightness.skyVisibility(0, 1, 0) == 1, "daytime must not suppress lights in caves without sky light");
        check(Math.abs(LightBrightness.skyVisibility(240, 0.24F, 0) - 0.76F) < 0.00001F,
                "nighttime sky light must retain most artificial illumination");
        check(LightBrightness.skyVisibility(240, 0.6F, 0) > LightBrightness.skyVisibility(240, 1, 0),
                "overcast sky must permit more artificial illumination than clear daylight");
        check(LightBrightness.skyVisibility(0, 1, 0.1F) < 1, "dimension ambient light must also reduce additional brightness");
        check(LightBrightness.colorVisibility(240, 1, 0, 1) == 0.25F,
                "daylight must retain a visible tint from saturated colored sources");
        check(LightBrightness.colorVisibility(240, 1, 0, 0) == 0,
                "white sources must not add daytime brightness");
        check(LightBrightness.colorVisibility(0, 1, 0, 1) == 1,
                "cave color visibility must retain the normal brightness cap");
        check(LightBrightness.tintStrength(1, 240, 0, 1, 0, 1) == 1,
                "a sole colored source must fully replace vanilla white light");
        check(Math.abs(LightBrightness.tintStrength(0.2F, 48, 0, 1, 0, 1) - 1) < 0.00001F,
                "a distant sole colored source must retain its hue rather than fading into white light");
        check(Math.abs(LightBrightness.tintStrength(0.2F, 240, 0, 1, 0, 1) - 0.2F) < 0.00001F,
                "unrelated stronger white light must retain its share of illumination");
        float previous = 1;
        for (int sky = 0; sky <= 240; sky++) {
            float visibility = LightBrightness.skyVisibility(sky, 1, 0);
            check(visibility >= 0 && visibility <= previous, "sky suppression must be bounded and monotonic");
            previous = visibility;
        }
        Capture capture = new Capture();
        VertexConsumer day = ColoredLightBufferSource.wrapFixed(capture, 65535L << 32, 1, 0);
        day.addVertex(0, 0, 0).setColor(255, 255, 255, 123).setUv2(32, 240);
        int chainedColor = capture.color, chainedLight = capture.light;
        day.addVertex(0, 0, 0, 0x7BFFFFFF, 0, 0, 0, 240 << 16 | 32, 0, 1, 0);
        check(capture.light == chainedLight && capture.color == chainedColor,
                "bulk and chained vertices must receive the same daylight correction");
        check(capture.light == (240 << 16 | 32), "sunlit entities must not receive extra block brightness");
        day.setUv2(0, 0);
        check(capture.light == 192, "entity illumination must remain effective in daytime caves");
        day.setUv2(240, 0);
        check(capture.light == 240, "existing fullbright vertices must remain unchanged");

        // Exercise actual geometry capture and byte encoding, so UV2 cannot silently read colors/positions.
        Class<?> type = Class.forName("org.mesdag.opallight.light.LightMaskMeshBuilder$GeometryVertexConsumer");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        VertexConsumer geometry = (VertexConsumer) constructor.newInstance();
        for (int i = 0; i < 4; i++) {
            geometry.addVertex(0.5F, 0.5F, 0.5F, 0xFFFFFFFF, 0.2F, 0.3F, 0,
                    (i * 80) << 16 | i * 48, 0, 1, 0);
        }
        Method vertices = type.getDeclaredMethod("vertices");
        vertices.setAccessible(true);
        float[] captured = (float[]) vertices.invoke(geometry);
        LightMeshColorGrid grid = new LightMeshColorGrid(0);
        grid.put(BlockPos.ZERO.asLong(), 65535L << 32);
        Class<?> scratchType = Class.forName("org.mesdag.opallight.light.LightMaskMeshBuilder$EncodingScratch");
        Constructor<?> scratchConstructor = scratchType.getDeclaredConstructor();
        scratchConstructor.setAccessible(true);
        Method colorize = LightMaskMeshBuilder.class.getDeclaredMethod("colorize", float[].class,
                LightMeshColorGrid.class, BlockGetter.class, float[].class, float[].class, scratchType,
                long.class, int.class, int.class, int.class);
        colorize.setAccessible(true);
        byte[] encoded = (byte[]) colorize.invoke(null, captured, grid, new World(pos -> Blocks.AIR.defaultBlockState()),
                new float[3], new float[16], scratchConstructor.newInstance(), 0L, 0, 0, 0);
        var format = LightMaskMeshBuilder.VERTEX_FORMAT;
        check(encoded.length == 4 * format.getVertexSize(), "mask encoding must match its registered vertex format");
        var data = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.nativeOrder());
        int uvOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.UV2);
        int modelOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.UV1);
        int colorOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.COLOR);
        for (int i = 0; i < 4; i++) {
            int offset = i * format.getVertexSize();
            check(data.getShort(offset + uvOffset + 2) == i * 80, "mask vertices must retain per-vertex sky light");
            check(data.getShort(offset + uvOffset) == i * 48, "mask vertices must retain the original block-light coordinate");
            check(data.getShort(offset + modelOffset) == -1 && data.getShort(offset + modelOffset + 2) == -1,
                    "model color and alpha must remain separate from the light hue");
            check(Byte.toUnsignedInt(data.get(offset + colorOffset + 3)) / 255F * LightBrightness.MASK_INTENSITY <= 0.3F,
                    "displayed mask brightness must retain its cap independently of encoded light strength");
            check(Byte.toUnsignedInt(data.get(offset + colorOffset)) == 255,
                    "sky coordinates must not overwrite the red tint channel");
        }
        // White light illuminating a tinted model must not become a falsely saturated light source.
        Method clear = type.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(geometry);
        for (int i = 0; i < 4; i++) {
            geometry.addVertex(0.5F, 0.5F, 0.5F, 0xFF408040, 0.2F, 0.3F, 0, 240 << 16, 0, 1, 0);
        }
        grid.reset(0);
        grid.put(BlockPos.ZERO.asLong(), 65535L << 32 | 65535L << 16 | 65535L);
        byte[] neutral = (byte[]) colorize.invoke(null, (float[]) vertices.invoke(geometry), grid,
                new World(pos -> Blocks.AIR.defaultBlockState()), new float[3], new float[16],
                scratchConstructor.newInstance(), 0L, 0, 0, 0);
        var whiteData = java.nio.ByteBuffer.wrap(neutral).order(java.nio.ByteOrder.nativeOrder());
        check(whiteData.getShort(uvOffset) == 0 && whiteData.getShort(uvOffset + 2) == 240,
                "white light must retain the model's block and sky light coordinates");
        check(Byte.toUnsignedInt(whiteData.get(colorOffset)) == 255
                        && Byte.toUnsignedInt(whiteData.get(colorOffset + 1)) == 255
                        && Byte.toUnsignedInt(whiteData.get(colorOffset + 2)) == 255,
                "white light illuminating vegetation must retain a neutral light hue");
        check((whiteData.getShort(modelOffset) & 65535) == 0x4080
                        && (whiteData.getShort(modelOffset + 2) & 65535) == 0xFF40,
                "vegetation tint must remain intact in the separate model attributes");
    }

    private static void skyLightChangesRefreshGeometry() throws Exception {
        LightMaskMeshCache.invalidate();
        var parts = (LightMaskMeshParts) field(LightMaskMeshCache.class, "parts");
        var geometry = new Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh>();
        var mesh = new LightMaskMeshBuilder.BlockMesh(new float[0], new byte[0]);
        long inside = BlockPos.asLong(8, 8, 8), border = BlockPos.asLong(16, 8, 8), distant = BlockPos.asLong(24, 8, 8);
        geometry.put(inside, mesh);
        geometry.put(border, mesh);
        geometry.put(distant, mesh);
        parts.replace(0, geometry);
        var inFlight = (Long2ObjectOpenHashMap<AtomicBoolean>) field(LightMaskMeshCache.class, "inFlight");
        AtomicBoolean cancelled = new AtomicBoolean();
        inFlight.put(0, cancelled);
        long negativeGroup = SectionPos.asLong(-1, -1, -1);
        var negative = new Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh>();
        long negativeBorder = BlockPos.asLong(-1, -1, -1), negativeDistant = BlockPos.asLong(-2, -2, -2);
        negative.put(negativeBorder, mesh);
        negative.put(negativeDistant, mesh);
        parts.replace(negativeGroup, negative);
        inFlight.put(negativeGroup, new AtomicBoolean());
        LightMaskMeshCache.markSkyLightChanged(SectionPos.of(0, 0, 0));
        check(cancelled.get(), "sky light updates must invalidate meshes with stale cached sky coordinates");
        check(parts.changedBlocks(0).contains(inside) && parts.changedBlocks(0).contains(border),
                "updated sky sections must refresh geometry including the AO border");
        check(!parts.changedBlocks(0).contains(distant), "unaffected cached geometry must remain reusable");
        check(parts.changedBlocks(negativeGroup).contains(negativeBorder)
                        && !parts.changedBlocks(negativeGroup).contains(negativeDistant),
                "sky light geometry invalidation must cross negative group boundaries precisely");
        var dirty = (LongOpenHashSet) field(LightMaskMeshCache.class, "dirtyGroups");
        check(dirty.contains(0) && dirty.contains(negativeGroup), "sky light changes must queue mesh refreshes");
        check(((LongOpenHashSet) field(LightMaskMeshCache.class, "colorDirtyGroups")).isEmpty(),
                "sky light updates must retain color and propagation data");
        LightMaskMeshCache.markSkyLightChanged(SectionPos.of(8, 8, 8));
        check(dirty.size() == 2, "unlit sky sections must not schedule extra meshes");
        LightMaskMeshCache.invalidate();
    }

    private static void coreAndEdgeFalloff() throws Exception {
        check(LightFalloff.coreWhite(0) == 0.2F, "the source center must contain only 20% white");
        check(Math.abs(LightFalloff.coreWhite(1) - 0.2F * 20 / 27) < 0.00001F,
                "white must fade smoothly through the first block");
        check(Math.abs(LightFalloff.coreWhite(2) - 0.2F * 7 / 27) < 0.00001F,
                "white must fade smoothly through the second block");
        check(LightFalloff.coreWhite(3) == 0 && LightFalloff.coreWhite(4) == 0,
                "white must disappear at three blocks without extending the neutral light field");
        check(LightFalloff.edgeOpacity(0) == 0 && LightFalloff.edgeOpacity(0.2F) == 1,
                "edge opacity must run from transparent to full strength across the last three light levels");
        check(Math.abs(LightFalloff.edgeOpacity(0.1F) - 0.5F) < 0.00001F,
                "the middle of the edge gradient must be half transparent");
        float previous = 0;
        for (int i = 0; i <= 100; i++) {
            float strength = i / 500F;
            float opacity = LightFalloff.edgeOpacity(strength);
            check(opacity >= previous && opacity <= 1, "edge opacity must be smooth, monotonic and bounded");
            previous = opacity;
        }
        check(Math.abs(LightBrightness.tintStrength(0.1F, 16, 0, 1, 0, 1) - 0.5F) < 0.00001F,
                "edge tint must retain the half-transparent gradient even for a sole colored source");
        check(LightBrightness.entityLight(0.1F, 0, 1, 0) == 16,
                "entity illumination must follow the same edge envelope");
        Capture capture = new Capture();
        VertexConsumer edge = ColoredLightBufferSource.wrapFixed(capture, LightColorCache.toneMapped(0, 0, 0.1F));
        edge.addVertex(0, 0, 0).setColor(255, 255, 255, 255).setUv2(0, 0);
        int chainedColor = capture.color, chainedLight = capture.light;
        check((capture.color >>> 16 & 255) >= 242 && (capture.color & 255) == 255,
                "entity edge tint must soften without changing the dominant blue channel");
        edge.addVertex(0, 0, 0, 0xFFFFFFFF, 0, 0, 0, 0, 0, 1, 0);
        check(capture.color == chainedColor && capture.light == chainedLight,
                "bulk entity vertices must receive the same edge transparency");

        BlockPos origin = new BlockPos(-1, 0, -1);
        LightSource blue = new LightSource(origin.asLong(), new LightProfile(OpalColor.of(0, 0, 1), null), 15);
        World air = new World(pos -> Blocks.AIR.defaultBlockState());
        var result = LightPropagationSolver.compute(snapshot(List.of(blue), targets()), air, key -> true, false);
        var colors = new Long2LongOpenHashMap();
        for (var update : result.updates()) if (update.colors() != null) colors.putAll(update.colors());
        check(colors.size() == 4089, "the white core must not change the colored light's propagation radius");
        for (var direction : net.minecraft.core.Direction.values()) {
            for (int distance = 0; distance <= 14; distance++) {
                long color = colors.get(origin.relative(direction, distance).asLong());
                float peak = LightColorCache.channel(color, 0);
                float expectedPeak = LightColorCache.channel(LightColorCache.toneMapped(0, 0, (15 - distance) / 15F), 0);
                check(Math.abs(peak - expectedPeak) < 0.00002F,
                        "adding a white core must preserve the peak brightness in every direction");
                float whiteShare = switch (distance) {
                    case 0 -> 0.2F;
                    case 1 -> 0.2F * 20 / 27;
                    case 2 -> 0.2F * 7 / 27;
                    default -> 0;
                };
                check(Math.abs(LightColorCache.channel(color, 32) / peak - whiteShare) < 0.00003F
                                && Math.abs(LightColorCache.channel(color, 16) / peak - whiteShare) < 0.00003F,
                        "blue lanterns must have a small white core that ends at three blocks, including chunk borders");
            }
        }
        // A cell two blocks away is reached by a four-step detour: white must not cut through the wall.
        World detour = new World(pos -> pos.getY() == 0 && pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 2
                && pos.getX() != 1 || pos.getY() == 1 && pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 2
                ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        LightSource detourBlue = new LightSource(BlockPos.ZERO.asLong(), blue.profile(), 15);
        var blocked = LightPropagationSolver.compute(snapshot(List.of(detourBlue), targets()), detour, key -> true, false);
        colors.clear();
        for (var update : blocked.updates()) if (update.colors() != null) colors.putAll(update.colors());
        long behindWall = colors.get(BlockPos.asLong(2, 0, 0));
        check(LightColorCache.channel(behindWall, 0) > 0 && LightColorCache.channel(behindWall, 32) == 0
                        && LightColorCache.channel(behindWall, 16) == 0,
                "the white core must follow the occluded propagation path rather than leak through walls");
    }

    private static void propagationAndOccludedSampling() throws Exception {
        LightColorCache.INSTANCE.clearAll();
        World air = new World(pos -> Blocks.AIR.defaultBlockState());
        var open = LightPropagationSolver.compute(snapshot(List.of(source(BlockPos.ZERO)), targets()), air, key -> true, false);
        int count = 0;
        for (var update : open.updates()) {
            if (update.colors() == null) continue;
            count += update.colors().size();
            for (var entry : update.colors().long2LongEntrySet()) {
                int distance = Math.abs(BlockPos.getX(entry.getLongKey())) + Math.abs(BlockPos.getY(entry.getLongKey()))
                        + Math.abs(BlockPos.getZ(entry.getLongKey()));
                float expected = LightColorCache.channel(LightColorCache.toneMapped((15 - distance) / 15F, 0, 0), 32);
                check(Math.abs(LightColorCache.channel(entry.getLongValue(), 32) - expected) < 0.00002F,
                        "air propagation must retain distance attenuation, including negative coordinates");
            }
        }
        check(count == 4089, "15-level air propagation should reach 4089 voxels");

        World enclosed = new World(pos -> pos.equals(new BlockPos(0, 1, 0)) || pos.equals(new BlockPos(1, 1, 1))
                ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        var isolated = LightPropagationSolver.compute(snapshot(List.of(source(new BlockPos(1, 1, 1))), targets()), enclosed, key -> true, false);
        LightMeshColorGrid grid = new LightMeshColorGrid(0);
        for (var update : isolated.updates()) {
            LightColorCache.INSTANCE.apply(update);
            if (update.colors() != null) update.colors().forEach((pos, color) -> grid.put(pos, color));
        }
        float[] sampled = new float[3];
        grid.sample(enclosed, 1, 1.5, 1, 0, 1, 0, sampled);
        check(sampled[0] == 0, "sealed terrain corner must not borrow diagonal exterior color");
        check(LightColorCache.INSTANCE.sample(enclosed, 0.99, 1.5, 0.99) == 0,
                "entities inside a sealed corner must not borrow exterior color");
        World doorway = new World(pos -> pos.equals(new BlockPos(0, 1, 0)) || pos.equals(new BlockPos(1, 1, 0))
                || pos.equals(new BlockPos(1, 1, 1)) ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        // Reset starts a fresh geometry snapshot: previously blocked cached edges must be discarded.
        grid.reset(0);
        grid.put(BlockPos.asLong(1, 1, 1), 65535L << 32);
        grid.sample(doorway, 1, 1.5, 1, 0, 1, 0, sampled);
        check(sampled[0] == 0.25F, "opening a wall must restore connected diagonal interpolation");
        grid.reset(SectionPos.asLong(-1, 0, -1));
        grid.put(BlockPos.asLong(-15, 1, -15), 65535L << 32);
        World negative = new World(pos -> pos.equals(new BlockPos(-16, 1, -16)) || pos.equals(new BlockPos(-15, 1, -15))
                ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        grid.sample(negative, -15, 1.5, -15, -16, 1, -16, sampled);
        check(sampled[0] == 0, "corner occlusion must work at negative chunk boundaries");

        LightColorSampler uniform = new LightColorSampler((x, y, z) -> 65535L << 32 | 32768L << 16, false);
        uniform.sample(air, -16.1, 15.9, 32.1, -17, 15, 32, sampled);
        check(Math.abs(sampled[0] - 1) < 0.00001F && Math.abs(sampled[1] - 0.5F) < 0.00002F,
                "unobstructed trilinear interpolation must preserve uniform light");
        LightColorSampler slabSampler = new LightColorSampler((x, y, z) -> y == -1 ? 65535L << 32 : 0, false);
        World slab = new World(pos -> pos.equals(BlockPos.ZERO)
                ? Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM) : Blocks.AIR.defaultBlockState());
        slabSampler.sample(slab, 0.5, 0, 0.5, 0, 0, 0, sampled);
        check(sampled[0] == 0, "partial-block closed face must occlude samples even when opacity is below 15");
        slab.states = pos -> pos.equals(BlockPos.ZERO)
                ? Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP) : Blocks.AIR.defaultBlockState();
        slabSampler.sample(slab, 0.5, 0, 0.5, 0, 0, 0, sampled);
        check(sampled[0] == 0.5F, "partial-block open face must keep light interpolation");
        LightColorCache.INSTANCE.clearAll();
    }

    private static void multiSourceColorMixing() throws Exception {
        LightColorCache.INSTANCE.clearAll();
        World air = new World(pos -> Blocks.AIR.defaultBlockState());
        LightSource red = coloredSource(new BlockPos(-4, 0, 0), 1, 0, 0);
        LightSource blue = coloredSource(new BlockPos(4, 0, 0), 0, 0, 1);
        LightSource green = coloredSource(new BlockPos(0, 0, 4), 0, 1, 0);
        var purple = propagatedColors(List.of(red, blue), air);
        assertRgb(purple.get(0), 11 / 15F, 0, 11 / 15F, "equal red and blue sources must produce purple");
        assertRgb(propagatedColors(List.of(red, green), air).get(0), 11 / 15F, 11 / 15F, 0,
                "equal red and green sources must produce yellow");
        assertRgb(propagatedColors(List.of(green, blue), air).get(0), 0, 11 / 15F, 11 / 15F,
                "equal green and blue sources must produce cyan");
        assertRgb(propagatedColors(List.of(red, green, blue), air).get(0), 11 / 15F, 11 / 15F, 11 / 15F,
                "equal red, green and blue sources must produce neutral white");

        LightSource nearRed = coloredSource(new BlockPos(-3, 0, 0), 1, 0, 0);
        LightSource farBlue = coloredSource(new BlockPos(9, 0, 0), 0, 0, 1);
        assertRgb(propagatedColors(List.of(nearRed, farBlue), air).get(0), 0.8F, 0, 0.4F,
                "a source with half the arriving strength must retain a half-strength color coefficient");
        LightSource dimBlue = coloredSource(new BlockPos(4, 0, 0), 0, 0, 0.5F);
        assertRgb(propagatedColors(List.of(red, dimBlue), air).get(0), 11 / 15F, 0, 11 / 30F,
                "source brightness must weight mixed color independently of hue");
        LightSource weakRed = new LightSource(red.pos(), red.profile(), 8);
        assertRgb(propagatedColors(List.of(weakRed, blue), air).get(0),
                0.5F, 0, 11 / 15F,
                "sources with different emission ranges must mix using their arriving attenuation");

        float doubled = 0.9F + 0.1F * ((22 / 15F) - 0.9F) / ((22 / 15F) - 0.8F);
        assertRgb(propagatedColors(List.of(red, red), air).get(0), doubled, 0, 0,
                "same-color sources must become brighter without turning white");
        double mixedGreen = LightColorCache.channel(propagatedColors(List.of(
                coloredSource(BlockPos.ZERO, 1, 0, 0), coloredSource(BlockPos.ZERO, 0, 0, 1)), air).get(0), 16);
        check(mixedGreen > 0 && mixedGreen < 0.34,
                "overlapping small white cores must soften mixed color without overwhelming it");
        var many = new java.util.ArrayList<LightSource>();
        for (int i = 0; i < 64; i++) many.add(red);
        long dense = propagatedColors(many, air).get(0);
        float denseStrength = LightColorCache.channel(dense, 32);
        check(denseStrength > doubled && denseStrength <= 1 && (dense & 0xFFFFFFFFL) == 0,
                "dense same-color lights must preserve hue and approach the brightness cap smoothly");
        check(denseStrength * LightBrightness.MASK_INTENSITY <= 0.3F,
                "many overlapping lights must retain the 30% illumination cap");

        var reversed = propagatedColors(List.of(blue, red), air);
        check(purple.equals(reversed), "source order must not change the mixed field");
        var weighted = propagatedColors(List.of(red, dimBlue, green, farBlue), air);
        var reordered = propagatedColors(List.of(farBlue, green, dimBlue, red), air);
        check(weighted.equals(reordered), "mixed-strength source order must not change packed colors");
        for (int x = -1; x <= 1; x++) {
            long color = purple.get(BlockPos.asLong(x, 0, 0));
            float r = LightColorCache.channel(color, 32), b = LightColorCache.channel(color, 0);
            check(x < 0 ? r > b : x > 0 ? b > r : r == b,
                    "overlap color must transition toward whichever source is nearer");
        }
        World wall = new World(pos -> pos.getX() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        assertRgb(propagatedColors(List.of(red, blue), wall).get(0), 11 / 15F, 0, 0,
                "a source behind a sealed wall must not contribute to mixed light");

        // User's screenshot: a 12 x 13 block rectangle, with weak tails meeting above the ground.
        World ground = new World(pos -> pos.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        var screenshotLights = List.of(coloredSource(new BlockPos(0, 0, 13), 1, 0, 0),
                coloredSource(new BlockPos(12, 0, 13), 0, 0, 1),
                coloredSource(BlockPos.ZERO, 1, 1, 0), coloredSource(new BlockPos(12, 0, 0), 0, 1, 0));
        var screenshotField = propagatedColors(screenshotLights, ground);
        assertRgb(screenshotField.get(BlockPos.asLong(6, 0, 6)), 5 / 15F, 6 / 15F, 2 / 15F,
                "four-light overlap must keep the weak blue tail at its original strength instead of washing the mix toward white");
        assertRgb(screenshotField.get(BlockPos.asLong(6, 0, 7)), 5 / 15F, 4 / 15F, 3 / 15F,
                "mixed tails must transition without gamma-lifting weaker channels");
        assertRgb(screenshotField.get(BlockPos.asLong(12, 0, 6)), 0, 9 / 15F, 8 / 15F,
                "the blue-green overlap must stay cyan without introducing red or white");

        // Preserve all existing single-source appearance, including dim colors and the three-block white core.
        for (OpalColor color : List.of(OpalColor.of(1, 0, 0), OpalColor.of(0, 0, 1), OpalColor.of(1, 1, 1),
                OpalColor.of(0.627451F, 0.12549F, 0.9411765F), OpalColor.of(0.3F, 0.2F, 0.1F))) {
            LightSource single = new LightSource(BlockPos.ZERO.asLong(), new LightProfile(color, null), 15);
            var field = propagatedColors(List.of(single), air);
            float peak = Math.max(color.r(), Math.max(color.g(), color.b()));
            for (int distance = 0; distance < 15; distance++) {
                float white = LightFalloff.coreWhite(distance), factor = (15 - distance) / 15F;
                long expected = LightColorCache.toneMapped((color.r() + (peak - color.r()) * white) * factor,
                        (color.g() + (peak - color.g()) * white) * factor,
                        (color.b() + (peak - color.b()) * white) * factor);
                assertRgb(field.get(BlockPos.asLong(distance, 0, 0)), LightColorCache.channel(expected, 32),
                        LightColorCache.channel(expected, 16), LightColorCache.channel(expected, 0),
                        "single-source colors, brightness and white-core falloff must remain unchanged");
            }
        }
        check(propagatedColors(List.of(coloredSource(BlockPos.ZERO, 0, 0, 0)), air).isEmpty(),
                "a black source must not leave zero-color voxels in the mixed field");

        // Removing a source recomputes the sum, including clearing voxels lit only by that source.
        var initial = LightPropagationSolver.compute(snapshot(List.of(red, blue), targets()), air, key -> true, false);
        for (var update : initial.updates()) LightColorCache.INSTANCE.apply(update);
        var removed = LightPropagationSolver.compute(snapshot(List.of(red), targets()), air, key -> true, false);
        for (var update : removed.updates()) LightColorCache.INSTANCE.apply(update);
        assertRgb(LightColorCache.INSTANCE.sample(air, 0.5, 0.5, 0.5), 11 / 15F, 0, 0,
                "removing the blue source must remove its contribution from overlapping light");
        check(LightColorCache.INSTANCE.sample(air, 18.5, 0.5, 0.5) == 0,
                "removing a source must clear positions outside the remaining source's reach");
        var emptied = LightPropagationSolver.compute(snapshot(List.of(), targets()), air, key -> true, false);
        for (var update : emptied.updates()) LightColorCache.INSTANCE.apply(update);
        check(LightColorCache.INSTANCE.isEmpty(), "removing all sources must clear the mixed field");
    }

    private static LightSource coloredSource(BlockPos pos, float r, float g, float b) {
        return new LightSource(pos.asLong(), new LightProfile(OpalColor.of(r, g, b), null), 15);
    }

    private static Long2LongOpenHashMap propagatedColors(List<LightSource> sources, World world) throws Exception {
        var result = LightPropagationSolver.compute(snapshot(sources, targets()), world, key -> true, false);
        var colors = new Long2LongOpenHashMap();
        for (var update : result.updates()) if (update.colors() != null) colors.putAll(update.colors());
        return colors;
    }

    private static void assertRgb(long color, float r, float g, float b, String message) {
        check(Math.abs(LightColorCache.channel(color, 32) - r) < 0.00003F
                && Math.abs(LightColorCache.channel(color, 16) - g) < 0.00003F
                && Math.abs(LightColorCache.channel(color, 0) - b) < 0.00003F,
                message + ": actual=" + LightColorCache.channel(color, 32) + ","
                        + LightColorCache.channel(color, 16) + "," + LightColorCache.channel(color, 0)
                        + "; expected=" + r + "," + g + "," + b);
    }

    private static void movingSourcesConverge() throws Exception {
        LightPropagator.clearAllSources();
        var moving = new Int2ObjectOpenHashMap<LightSource>();
        moving.put(1, source(new BlockPos(8, 8, 8)));
        LightPropagator.replaceDynamicSources(moving);
        clearPending();
        var future = new CompletableFuture<LightPropagationSolver.Result>();
        Object batch = installBatch(future, true);
        for (int tick = 0; tick < 40; tick++) {
            moving.put(1, source(new BlockPos(8 + tick % 4, tick % 2 == 0 ? 24 : 8, 8)));
            LightPropagator.replaceDynamicSources(moving);
            check(field(LightPropagator.class, "activeBatch") == batch, "continuous movement must not cancel a dynamic computation");
        }
        future.complete(result());
        moving.put(1, source(new BlockPos(12, 8, 8)));
        LightPropagator.replaceDynamicSources(moving);
        check(field(LightPropagator.class, "activeBatch") == batch, "movement must preserve an already completed batch");
        var commit = LightPropagator.flushPending(null, false, OptionalLong::empty);
        check(commit != null && commit.dynamic() && commit.updates().size() == 1,
                "a batch must publish while sources continue moving");
        check(((LongOpenHashSet) field(LightPropagator.class, "pendingChunks")).contains(0),
                "latest movement must remain queued after publishing an older snapshot");
        var ranges = (Long2ObjectOpenHashMap<LongOpenHashSet>) field(LightPropagator.class, "pendingSections");
        check(ranges.get(0).contains(SectionPos.asLong(0, 3, 0)), "followup must retain uncommitted height ranges");
        LightPropagator.clearAllSources();
    }

    private static void geometryInvalidatesDeferredPropagation() throws Exception {
        LightPropagator.clearAllSources();
        var moving = new Int2ObjectOpenHashMap<LightSource>();
        moving.put(1, source(new BlockPos(8, 8, 8)));
        LightPropagator.replaceDynamicSources(moving);
        clearPending();
        var future = new CompletableFuture<LightPropagationSolver.Result>();
        Object batch = installBatch(future, true);
        moving.put(1, source(new BlockPos(9, 8, 8)));
        LightPropagator.replaceDynamicSources(moving);
        LightPropagator.scheduleAround(new BlockPos(8, 8, 8));
        check(field(LightPropagator.class, "activeBatch") == null,
                "geometry changes must invalidate a batch even when movement already queued its chunk");
        Method accessor = batch.getClass().getDeclaredMethod("snapshot");
        accessor.setAccessible(true);
        boolean cancelled = false;
        try { ((LightPropagationSnapshot) accessor.invoke(batch)).checkCancelled(); }
        catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
        check(cancelled, "invalid geometry snapshot must be cancelled");
        LightPropagator.clearAllSources();
        installBatch(CompletableFuture.completedFuture(result()), false);
        moving.put(1, source(new BlockPos(10, 8, 8)));
        LightPropagator.replaceDynamicSources(moving);
        check(LightPropagator.flushPending(null, false, OptionalLong::empty) != null,
                "completed static batches must also publish before movement followups");
        LightPropagator.clearAllSources();
    }

    private static void meshColorUpdatesConverge() throws Exception {
        LightMaskMeshCache.invalidate();
        var inFlight = (Long2ObjectOpenHashMap<AtomicBoolean>) field(LightMaskMeshCache.class, "inFlight");
        var versions = (Long2LongOpenHashMap) field(LightMaskMeshCache.class, "groupVersions");
        AtomicBoolean cancelled = new AtomicBoolean();
        inFlight.put(0, cancelled);
        versions.put(0, 7);
        for (int tick = 0; tick < 40; tick++) {
            LightMaskMeshCache.beginDirtyBatch();
            LightMaskMeshCache.markColorChanged(8, 8, 8, 8, 8, 8, true);
            LightMaskMeshCache.endDirtyBatch();
        }
        check(!cancelled.get() && versions.get(0) == 7, "continuous color changes must allow a mesh computation to finish");
        Class<?> type = Class.forName("org.mesdag.opallight.light.LightMaskMeshCache$MeshResult");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        long epoch = ((AtomicLong) field(LightMaskMeshCache.class, "epoch")).get();
        var completed = (ConcurrentLinkedQueue<Object>) field(LightMaskMeshCache.class, "completed");
        completed.add(constructor.newInstance(0L, epoch, 7L, null, null));
        Method process = LightMaskMeshCache.class.getDeclaredMethod("processAsync", ClientLevel.class, Frustum.class,
                boolean.class, LongPredicate.class);
        process.setAccessible(true);
        process.invoke(null, null, null, false, (LongPredicate) key -> false);
        check(versions.get(0) == 8 && ((LongOpenHashSet) field(LightMaskMeshCache.class, "dirtyGroups")).contains(0),
                "publishing a mesh must leave one rebuild with the latest colors queued");
        cancelled = new AtomicBoolean();
        inFlight.put(0, cancelled);
        LightMaskMeshCache.invalidateChangedGeometry(BlockPos.asLong(8, 8, 8));
        check(cancelled.get(), "geometry changes must still cancel unsafe mesh computations");
        LightMaskMeshCache.invalidate();
    }

    private static LightSource source(BlockPos pos) {
        return new LightSource(pos.asLong(), new LightProfile(OpalColor.of(1, 0, 0), null), 15);
    }

    private static LongOpenHashSet targets() {
        LongOpenHashSet targets = new LongOpenHashSet();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) targets.add(ChunkPos.asLong(x, z));
        return targets;
    }

    private static LightPropagationSnapshot snapshot(List<LightSource> sources, LongOpenHashSet targets) throws Exception {
        Constructor<?> constructor = LightPropagationSnapshot.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var restricted = new Long2ObjectOpenHashMap<LongOpenHashSet>();
        if (targets.size() == 1) restricted.put(0, new LongOpenHashSet(new long[]{SectionPos.asLong(0, 3, 0)}));
        return (LightPropagationSnapshot) constructor.newInstance(new Long2ObjectOpenHashMap<>(), targets, restricted,
                sources, -64, 384, 0L, false);
    }

    private static Object installBatch(CompletableFuture<LightPropagationSolver.Result> future, boolean dynamic) throws Exception {
        Class<?> type = Class.forName("org.mesdag.opallight.light.LightPropagator$ActiveBatch");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Long2LongOpenHashMap versions = new Long2LongOpenHashMap();
        versions.put(0, 1);
        ((Long2LongOpenHashMap) field(LightPropagator.class, "chunkVersions")).put(0, 1);
        Object batch = constructor.newInstance(null, versions, snapshot(List.of(), new LongOpenHashSet(new long[]{0})), future, dynamic);
        Field active = LightPropagator.class.getDeclaredField("activeBatch");
        active.setAccessible(true);
        active.set(null, batch);
        return batch;
    }

    private static LightPropagationSolver.Result result() {
        Long2LongOpenHashMap colors = new Long2LongOpenHashMap();
        colors.put(BlockPos.asLong(8, 8, 8), 65535L << 32);
        return new LightPropagationSolver.Result(List.of(new LightColorCache.SectionUpdate(0, colors, 8, 8, 8, 8, 8, 8)), false);
    }

    private static void clearPending() throws Exception {
        ((LongOpenHashSet) field(LightPropagator.class, "pendingChunks")).clear();
        ((LongOpenHashSet) field(LightPropagator.class, "dynamicPendingChunks")).clear();
        ((Long2ObjectOpenHashMap<?>) field(LightPropagator.class, "pendingSections")).clear();
    }

    private static Object field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class World implements BlockGetter {
        private Function<BlockPos, BlockState> states;
        private World(Function<BlockPos, BlockState> states) { this.states = states; }
        @Override public BlockState getBlockState(BlockPos pos) { return states.apply(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static final class Capture implements VertexConsumer {
        private int color, light, overlay;
        private float x, y, z;
        @Override public VertexConsumer addVertex(float x, float y, float z) { this.x = x; this.y = y; this.z = z; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { color = a << 24 | r << 16 | g << 8 | b; return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { overlay = u | v << 16; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { light = u | v << 16; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                                        float nx, float ny, float nz) {
            this.x = x; this.y = y; this.z = z; this.color = color; this.overlay = overlay; this.light = light;
        }
    }
}
