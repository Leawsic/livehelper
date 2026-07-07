package site.leawsic.livehelper.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("cullingFrustum")
    Frustum livehelper$getCullingFrustum();

    @Invoker("setupRender")
    void livehelper$setupRender(Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator);
}
