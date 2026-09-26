package site.leawsic.livehelper.mixin;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 暴露客户端进度完成状态。
 *
 * <p>1.20.1 的 {@code ClientAdvancements} 把已完成进度存在私有 map 里，
 * {@code AdvancementList} 也不提供进度查询，因此要实现 advancement 触发器只能读这个字段。
 * 只读，不改写。
 */
@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {

    @Accessor("progress")
    Map<Advancement, AdvancementProgress> livehelper$getProgress();
}
