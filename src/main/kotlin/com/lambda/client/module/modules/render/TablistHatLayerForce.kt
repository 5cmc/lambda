package com.lambda.client.module.modules.render

import com.lambda.client.module.Category
import com.lambda.client.module.Module

object TablistHatLayerForce : Module(
    name = "TablistHatLayerForce",
    description = "Forces player head layers to render in the tablist",
    category = Category.RENDER,
    showOnArray = false,
    enabledByDefault = true
)