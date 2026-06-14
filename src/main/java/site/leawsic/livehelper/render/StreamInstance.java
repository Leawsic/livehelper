package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.mixin.GameRendererAccessor;
import site.leawsic.livehelper.mixin.LightTextureAccessor;
import site.leawsic.livehelper.mixin.MinecraftAccessor;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.scheduler.MainScheduler;
import site.leawsic.livehelper.spout.SpoutSender;
import site.leawsic.livehelper.util.ActiveRenderContext;

import java.util.concurrent.TimeUnit;

public class StreamInstance implements AutoCloseable {
    private final int managerId;
    private final Manager config;
    private final PlaybackEngine engine;
    private final MainTarget renderTarget;
    private final SpoutSender spoutSender;
    private final Camera dummyCamera;
    private final long frameIntervalNs;

    private boolean stopped;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.dummyCamera = new Camera();
        this.frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / Math.max(1, manager.fps());
        this.renderTarget = new MainTarget(manager.width(), manager.height());
        this.renderTarget.setClearColor(0f, 0f, 0f, 1f);
        this.spoutSender = new SpoutSender("LiveHelper-" + manager.name());

        scheduleNext(System.nanoTime());
    }

    private void scheduleNext(long currentNs) {
        if (stopped) return;
        long nextFrameNs = Math.max(currentNs + frameIntervalNs, System.nanoTime());
        MainScheduler.submitTask(nextFrameNs, (isOutOfMemoryRecovery, taskNs) -> {
            if (stopped) return;
            renderFrame();
            scheduleNext(taskNs);
        });
    }

    private void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        FrameCommand cmd = engine.computeFrame();
        if (cmd == null) return;

        MinecraftAccessor minecraftAccessor = (MinecraftAccessor) mc;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) mc.gameRenderer;
        RenderTarget prevTarget = minecraftAccessor.livehelper$getMainRenderTarget();
        Camera prevCamera = gameRendererAccessor.livehelper$getMainCamera();
        boolean prevHideGui = mc.options.hideGui;

        try {
            minecraftAccessor.livehelper$setMainRenderTarget(renderTarget);
            gameRendererAccessor.livehelper$setMainCamera(dummyCamera);
            mc.options.hideGui = true;

            renderTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, renderTarget.width, renderTarget.height);
            renderTarget.clear(Minecraft.ON_OSX);

            CameraSetup.apply(dummyCamera, cmd, config.width(), config.height(), config.renderDistance());

            // ── Render the full game frame into our offscreen target ──
            // GameRenderer.render() will:
            //   1. Set viewport to window size (we set it back in finally)
            //   2. Call renderLevel() internally with proper GL state
            //   3. Draw hand (suppressed by mixin)
            //   4. Draw GUI (suppressed by hideGui mixin)
            //
            // CRITICAL: renderLevel() calls lightTexture.updateLightTexture() which reads
            // the mainRenderTarget's color texture to set up the light map.
            // Since we swapped mainRenderTarget to our NEW black-cleared offscreen target,
            // the light map will be corrupted (black). We MUST reset the update flag
            // afterwards so the next game frame re-reads from the correct (real) target.
            ActiveRenderContext.runWithContext(cmd, config.width(), config.height(), config.renderDistance(),
                () -> mc.gameRenderer.render(1.0F, System.nanoTime(), true)
            );

            // Reset the light texture update flag — our render consumed it with a black target,
            // so the game needs to re-update on its next frame using the real main target.
            ((LightTextureAccessor) mc.gameRenderer.lightTexture())
                .livehelper$setUpdateLightTexture(true);

            spoutSender.send(renderTarget.frameBufferId, renderTarget.width, renderTarget.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        } finally {
            minecraftAccessor.livehelper$setMainRenderTarget(prevTarget);
            gameRendererAccessor.livehelper$setMainCamera(prevCamera);
            mc.options.hideGui = prevHideGui;
            prevTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    public void tick() {
        dummyCamera.tick();
    }

    @Override
    public void close() {
        stopped = true;
        renderTarget.destroyBuffers();
        spoutSender.close();
    }
}
