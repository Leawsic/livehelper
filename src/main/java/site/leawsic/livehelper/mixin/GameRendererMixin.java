package site.leawsic.livehelper.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
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

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private float zoom;
    @Shadow private float zoomX;
    @Shadow private float zoomY;
    @Shadow public native float getDepthFar();

    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(float tickDelta, long startNano, boolean tick, CallbackInfo ci) {
        if (ActiveRenderContext.isActive()) {
            minecraft.options.hideGui = true;
        }
    }

    /** Override viewport to stream dimensions right after render() sets it to window size */
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

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void beforeRenderItemInHand(PoseStack poseStack, Camera camera, float tickDelta, CallbackInfo ci) {
        if (ActiveRenderContext.isActive()) {
            ci.cancel();
        }
    }

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
