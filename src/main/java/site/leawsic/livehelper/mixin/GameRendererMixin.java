package site.leawsic.livehelper.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.render.CameraSetup;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.util.ActiveRenderContext;
import site.leawsic.livehelper.util.OffscreenTargetTracker;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private boolean renderHand;
    @Shadow private float zoom;
    @Shadow private float zoomX;
    @Shadow private float zoomY;
    @Shadow public native float getDepthFar();

    @Unique
    private static long livehelper$lastRedirectLogNs = 0L;

    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(float tickDelta, long startNano, boolean tick, CallbackInfo ci) {
        if (ActiveRenderContext.isOffscreenActive()) {
            minecraft.options.hideGui = true;
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void skipAutoPauseDuringStream(Minecraft minecraft, boolean pauseOnly) {
        if (!StreamManager.INSTANCE.hasActive()) {
            minecraft.pauseGame(pauseOnly);
        }
    }

    @Inject(method = "render", at = @At(
        value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;viewport(IIII)V",
        ordinal = 0,
        shift = At.Shift.AFTER
    ))
    private void afterViewportSet(CallbackInfo ci) {
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        if (ctx != null) {
            RenderSystem.viewport(0, 0, ctx.width(), ctx.height());
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void beforeRenderLevel(float tickDelta, long startNano, PoseStack poseStack, CallbackInfo ci) {
        if (ActiveRenderContext.isOffscreenActive()) {
            this.renderHand = false;
        }
    }

    @Redirect(method = "renderLevel", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"
    ))
    private void setupVirtualCamera(Camera camera, net.minecraft.world.level.BlockGetter level,
                                    net.minecraft.world.entity.Entity entity, boolean detached,
                                    boolean inverseView, float tickDelta) {
        camera.setup(level, entity, detached, inverseView, tickDelta);
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        if (ctx != null) {
            CameraSetup.applyAfterSetup(camera, ctx.command());
            long now = System.nanoTime();
            if (now - livehelper$lastRedirectLogNs > 2_000_000_000L) {
                livehelper$lastRedirectLogNs = now;
                LiveHelper.LOGGER.info(
                    "GameRenderer camera redirected: cmd=({}, {}, {}) camera=({}, {}, {}) rot=({}, {})",
                    ctx.command().x(), ctx.command().y(), ctx.command().z(),
                    camera.getPosition().x, camera.getPosition().y, camera.getPosition().z,
                    camera.getYRot(), camera.getXRot()
                );
            }
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void beforeRenderItemInHand(PoseStack poseStack, Camera camera, float tickDelta, CallbackInfo ci) {
        if (ActiveRenderContext.isOffscreenActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V",
        shift = At.Shift.AFTER
    ))
    private void afterRenderLevelSwapBack(CallbackInfo ci) {
        RenderTarget original = OffscreenTargetTracker.getOriginalTarget();
        if (original != null) {
            OffscreenTargetTracker.clear();
            ((MinecraftAccessor) minecraft).livehelper$setMainRenderTarget(original);
            original.bindWrite(true);
        }
    }

    /**
     * @author Leawsic
     * @reason Offscreen streams need their own aspect ratio, FOV, and far plane.
     */
    @Overwrite
    public Matrix4f getProjectionMatrix(double fov) {
        PoseStack stack = new PoseStack();
        stack.last().pose().identity();
        if (this.zoom != 1.0F) {
            stack.translate(this.zoomX, -this.zoomY, 0.0F);
            stack.scale(this.zoom, this.zoom, 1.0F);
        }

        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        if (ctx != null) {
            stack.last().pose().mul(new Matrix4f().setPerspective(
                (float) Math.toRadians(ctx.command().fov()),
                (float) ctx.width() / (float) ctx.height(),
                0.05F,
                ctx.renderDistance() * 16.0F
            ));
        } else {
            stack.last().pose().mul(new Matrix4f().setPerspective(
                (float) (fov * 0.01745329238474369),
                (float) minecraft.getWindow().getWidth() / (float) minecraft.getWindow().getHeight(),
                0.05F,
                this.getDepthFar()
            ));
        }

        return stack.last().pose();
    }
}
