package site.leawsic.livehelper.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import site.leawsic.livehelper.render.CameraSetup;
import site.leawsic.livehelper.util.ActiveRenderContext;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(float tickDelta, long startNano, boolean tick, CallbackInfo ci) {
        if (ActiveRenderContext.isActive()) {
            minecraft.options.hideGui = true;
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
}
