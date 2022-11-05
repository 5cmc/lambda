package com.lambda.client.module

import com.lambda.client.commons.interfaces.DisplayEnum

enum class Category(override val displayName: String) : DisplayEnum {
    CHAT("Chat"),
    CLIENT("Client"),
    COMBAT("Combat"),
    MISC("Misc"),
    MOVEMENT("Movement"),
    PLAYER("Player"),
    PROXY("Proxy"),
    RENDER("Render");

    override fun toString() = displayName
}