package com.lambda.client.event.events

import com.lambda.client.event.Cancellable
import com.lambda.client.event.Event
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

// this is basically a copy of Forge's PlayerInteractEvent.RightClickBlock but actually cancellable without sending a packet
class RightClickBlockEvent(val player: EntityPlayer, val hand: EnumHand, val pos: BlockPos, val face: EnumFacing, val hitVec: Vec3d) : Event, Cancellable()