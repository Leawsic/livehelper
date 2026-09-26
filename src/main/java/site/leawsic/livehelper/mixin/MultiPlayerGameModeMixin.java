package site.leawsic.livehelper.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import site.leawsic.livehelper.trigger.client.TriggerEventPublisher;

/**
 * 捕获客户端的交互 / 攻击 / 使用物品事件。
 *
 * <p>Fabric API 的 {@code AttackEntityCallback} 等只存在于服务端，客户端侧唯一稳定的入口就是
 * {@link MultiPlayerGameMode}——所有本地交互最终都要经过它。因此把这一个类作为唯一的事件来源，
 * 比分散到多个 mixin 风险更低。
 *
 * <p>全部使用 {@code @Inject} + {@code CallbackInfo}（不使用 {@code @Redirect}），
 * 与本仓库既有 mixin 风格一致，也降低与其他模组冲突的概率。
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void livehelper$onAttack(Player player, Entity target, CallbackInfo ci) {
        TriggerEventPublisher.onAttack(player, target);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void livehelper$onUseItemOn(net.minecraft.client.player.LocalPlayer player,
                                        InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        TriggerEventPublisher.onBlockInteract(player, hand, hit);
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void livehelper$onUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        TriggerEventPublisher.onItemUse(player, hand);
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void livehelper$onInteract(Player player, Entity target, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        TriggerEventPublisher.onEntityInteract(player, target, hand);
    }

    @Inject(method = "interactAt", at = @At("HEAD"))
    private void livehelper$onInteractAt(Player player, Entity target, EntityHitResult hit, InteractionHand hand,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        // interactAt 与 interact 是同一次交互的两个入口，只上报一次。
        if (hit == null || hit.getEntity() == target) {
            TriggerEventPublisher.onEntityInteract(player, target, hand);
        }
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void livehelper$onReleaseUsingItem(Player player, CallbackInfo ci) {
        ItemStack stack = player.getUseItem();
        TriggerEventPublisher.onItemRelease(player, stack);
    }
}
