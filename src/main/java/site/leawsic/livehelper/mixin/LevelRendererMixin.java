package site.leawsic.livehelper.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import site.leawsic.livehelper.util.ActiveRenderContext;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private double useVirtualCameraX(LocalPlayer player, Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator) {
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        return ctx != null ? ctx.command().x() : player.getX();
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    private double useVirtualCameraY(LocalPlayer player, Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator) {
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        return ctx != null ? ctx.command().y() : player.getY();
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private double useVirtualCameraZ(LocalPlayer player, Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator) {
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        return ctx != null ? ctx.command().z() : player.getZ();
    }
}
