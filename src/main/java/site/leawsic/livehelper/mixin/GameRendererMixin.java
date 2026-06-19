package site.leawsic.livehelper.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import site.leawsic.livehelper.render.CameraSetup;
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

    /**
     * Before renderLevel: disable hand rendering to prevent the hand clear
     * that would wipe the world from our offscreen target.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void beforeRenderLevel(float tickDelta, long startNano, PoseStack poseStack, CallbackInfo ci) {
        if (ActiveRenderContext.isActive()) {
            this.renderHand = false;
        }
    }

    /**
     * After camera.setup() inside renderLevel, re-apply virtual camera position/rotation.
     */
    @Inject(method = "renderLevel", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        shift = At.Shift.AFTER
    ))
    private void afterCameraSetup(float tickDelta, long startNano, PoseStack poseStack, CallbackInfo ci) {
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        if (ctx != null) {
            CameraSetup.applyAfterSetup(((GameRenderer)(Object) this).getMainCamera(), ctx.command());
        }
    }

    /**
     * Right after renderLevel returns and before the GUI clear: swap mainRenderTarget
     * back to the original window target so the GUI clear/draw hits the window,
     * preserving our offscreen target with the world content.
     */
    @Inject(method = "render", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V",
        shift = At.Shift.AFTER
    ))
    private void afterRenderLevelSwapBack(CallbackInfo ci) {
        RenderTarget orig = OffscreenTargetTracker.getOriginalTarget();
        if (orig != null) {
            OffscreenTargetTracker.clear();
            minecraft.getMainRenderTarget(); // ensure resolved
            try {
                java.lang.reflect.Field targetField = Minecraft.class.getDeclaredField("mainRenderTarget");
                targetField.setAccessible(true);
                targetField.set(minecraft, orig);
            } catch (Exception e) {
                // fallback, shouldn't happen
            }
            orig.bindWrite(true);
        }
    }

    /**
     * Override projection matrix to use stream FOV/dimensions during offscreen rendering.
     */
    @Overwrite
    public Matrix4f getProjectionMatrix(double fov) {
        PoseStack stack = new PoseStack();
        stack.last().pose().identity();
        if (this.zoom != 1.0F) {
            stack.translate(this.zoomX, -this.zoomY, 0.0F);
            stack.scale(this.zoom, this.zoom, 1.0F);
        }
        float fovRad;
        float aspect;
        float depthFar;
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        if (ctx != null) {
            fovRad = (float) Math.toRadians(ctx.command().fov());
            aspect = (float) ctx.width() / (float) ctx.height();
            depthFar = ctx.renderDistance() * 16.0f;
        } else {
            fovRad = (float) (fov * 0.01745329238474369);
            aspect = (float) minecraft.getWindow().getWidth() / (float) minecraft.getWindow().getHeight();
            depthFar = this.getDepthFar();
        }
        stack.last().pose().mul(new Matrix4f().setPerspective(fovRad, aspect, 0.05F, depthFar));
        return stack.last().pose();
    }
}
