package com.lambda.client.module.modules.player

import com.lambda.client.module.Category
import com.lambda.client.module.Module

object Reach : Module(
    name = "Reach",
    description = "Extends your reach",
    category = Category.PLAYER
) {
    val dist by setting("Distance", 4.0, 4.0..10.0, 0.1)

    /**
     * @see com.lambda.mixin.player.MixinPlayerControllerMP.onGetBlockReachDistance
     */
}