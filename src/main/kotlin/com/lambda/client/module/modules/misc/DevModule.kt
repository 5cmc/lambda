package com.lambda.client.module.modules.misc

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.threads.safeListener
import com.sun.xml.internal.bind.v2.schemagen.xmlschema.AttributeType
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.passive.AbstractHorse
import net.minecraft.entity.passive.EntityAnimal
import net.minecraftforge.fml.common.gameevent.TickEvent

object DevModule: Module(

    name = "HorseyJump",
    category = Category.MISC,
    description = "Makes horse jump"
) {

    private val horseJumpPower by setting("Horse jump strength", 9.0f, 1.0f..20.0f, 0.5f)


    init {
        safeListener<TickEvent.ClientTickEvent>(priority = 9998) {
            if (it.phase != TickEvent.Phase.END) return@safeListener
            player.ridingEntity?.let { entity ->

            if(entity is AbstractHorse){

            }

            if(!isEnabled) return@safeListener
            if(player.isRidingHorse){
                if(entity is AbstractHorse && mc.gameSettings.keyBindJump.isKeyDown){
                    entity.setJumpPower(90)

                    }
                }
            }
        }
    }
}