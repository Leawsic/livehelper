package site.leawsic.livehelper.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import site.leawsic.livehelper.render.CameraSetup;
import site.leawsic.livehelper.util.ActiveRenderContext;

@Mixin(Camera.class)
public class CameraMixin {
    @Inject(method = "setup", at = @At("RETURN"))
    private void afterSetup(BlockGetter level, Entity entity, boolean detached, boolean inverseView, float tickDelta, CallbackInfo ci) {
        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        if (ctx != null) {
            CameraSetup.applyAfterSetup((Camera)(Object) this, ctx.command());
        }
    }
}
