package net.ramixin.visibletraders.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.ramixin.visibletraders.ducks.VillagerDuck;
import net.ramixin.visibletraders.ducks.ZombieVillagerDuck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Zombie.class)
public class ZombieMixin {

    @Inject(method = "killedEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;getVillagerXp()I"))
    private void transferTradesToZombieVillager(ServerLevel serverLevel, LivingEntity livingEntity, CallbackInfoReturnable<Boolean> cir, @Local ZombieVillager zombieVillager) {
        if (livingEntity instanceof Villager villager && zombieVillager != null) {
            VillagerDuck.of(villager).visibleTraders$getLockedTradeData().ifPresent(data -> ZombieVillagerDuck.of(zombieVillager).visibleTraders$setLockedTradeData(data));
        }
    }

}
