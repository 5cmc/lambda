package com.lambda.mixin.player;

import com.lambda.client.event.LambdaEventBus;
import com.lambda.client.event.events.OnUpdateWalkingPlayerEvent;
import com.lambda.client.event.events.PlayerBlockPushEvent;
import com.lambda.client.event.events.PlayerMoveEvent;
import com.lambda.client.gui.mc.LambdaGuiBeacon;
import com.lambda.client.manager.managers.MessageManager;
import com.lambda.client.manager.managers.PlayerPacketManager;
import com.lambda.client.module.modules.chat.PortalChat;
import com.lambda.client.module.modules.misc.BeaconSelector;
import com.lambda.client.module.modules.movement.Sprint;
import com.lambda.client.module.modules.player.Freecam;
import com.lambda.client.util.Wrapper;
import com.lambda.client.util.math.Vec2f;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityPlayerSP.class, priority = Integer.MAX_VALUE)
public abstract class MixinEntityPlayerSP extends EntityPlayer {
    @Shadow @Final public NetHandlerPlayClient connection;
    @Shadow protected Minecraft mc;
    @Shadow private double lastReportedPosX;
    @Shadow private double lastReportedPosY;
    @Shadow private double lastReportedPosZ;
    @Shadow private float lastReportedYaw;
    @Shadow private int positionUpdateTicks;
    @Shadow private float lastReportedPitch;
    @Shadow private boolean serverSprintState;
    @Shadow private boolean serverSneakState;
    @Shadow private boolean prevOnGround;
    @Shadow private boolean autoJumpEnabled;
    private OnUpdateWalkingPlayerEvent updateWalkingPlayerEvent = new OnUpdateWalkingPlayerEvent();

    public MixinEntityPlayerSP(World worldIn, GameProfile gameProfileIn) {
        super(worldIn, gameProfileIn);
    }

    @Shadow
    protected abstract boolean isCurrentViewEntity();

    @Shadow
    protected abstract void updateAutoJump(float p_189810_1_, float p_189810_2_);

    @Redirect(method = "onLivingUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;closeScreen()V"))
    public void closeScreen(EntityPlayerSP player) {
        if (PortalChat.INSTANCE.isDisabled()) player.closeScreen();
    }

    @Redirect(method = "onLivingUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;displayGuiScreen(Lnet/minecraft/client/gui/GuiScreen;)V"))
    public void closeScreen(Minecraft minecraft, GuiScreen screen) {
        if (PortalChat.INSTANCE.isDisabled()) Wrapper.getMinecraft().displayGuiScreen(screen);
    }

    /**
     * @author TBM
     * Used with full permission from TBM - l1ving
     */
    @Inject(method = "displayGUIChest", at = @At("HEAD"), cancellable = true)
    public void onDisplayGUIChest(IInventory chestInventory, CallbackInfo ci) {
        if (BeaconSelector.INSTANCE.isEnabled()) {
            if (chestInventory instanceof IInteractionObject && "minecraft:beacon".equals(((IInteractionObject) chestInventory).getGuiID())) {
                Minecraft.getMinecraft().displayGuiScreen(new LambdaGuiBeacon(this.inventory, chestInventory));
                ci.cancel();
            }
        }
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    public void moveHead(MoverType type, double x, double y, double z, CallbackInfo ci) {
        EntityPlayerSP player = Wrapper.getPlayer();
        if (player == null) return;

        PlayerMoveEvent event = new PlayerMoveEvent(player);
        LambdaEventBus.INSTANCE.post(event);

        if (event.isModified()) {
            double prevX = this.posX;
            double prevZ = this.posZ;

            super.move(type, event.getX(), event.getY(), event.getZ());
            this.updateAutoJump((float) (this.posX - prevX), (float) (this.posZ - prevZ));

            ci.cancel();
        }
    }

    @ModifyArg(method = "setSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;setSprinting(Z)V"), index = 0)
    public boolean modifySprinting(boolean sprinting) {
        if (Sprint.INSTANCE.isEnabled() && Sprint.shouldSprint()) {
            return true;
        } else {
            return sprinting;
        }
    }

    // We have to return true here so it would still update movement inputs from Baritone and send packets
    @Inject(method = "isCurrentViewEntity", at = @At("RETURN"), cancellable = true)
    protected void mixinIsCurrentViewEntity(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.INSTANCE.isEnabled() && Freecam.INSTANCE.getCameraGuy() != null) {
            cir.setReturnValue(mc.getRenderViewEntity() == Freecam.INSTANCE.getCameraGuy());
        }
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"))
    public void sendChatMessage(String message, CallbackInfo ci) {
        MessageManager.INSTANCE.setLastPlayerMessage(message);
    }

    @Inject(method = "onUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;onUpdateWalkingPlayer()V", shift = At.Shift.AFTER))
    private void onUpdateInvokeOnUpdateWalkingPlayer(CallbackInfo ci) {
        Vec3d serverSidePos = PlayerPacketManager.INSTANCE.getServerSidePosition();
        Vec2f serverSideRotation = PlayerPacketManager.INSTANCE.getPrevServerSideRotation();

        this.lastReportedPosX = serverSidePos.x;
        this.lastReportedPosY = serverSidePos.y;
        this.lastReportedPosZ = serverSidePos.z;

        this.lastReportedYaw = serverSideRotation.getX();
        this.lastReportedPitch = serverSideRotation.getY();
    }

    @Inject(
        method = "onUpdate",
        at = @At(
            // need to do this weird injection for future compatibility
            // what's likely going on is future does a similar thing, setting the player rotation values based on the event
            // but those values are being taken from the updateWalkingPlayer method args rather than the field values
            // idk no way to be sure without looking at future src
            // 3arthh4ck does a similar mixin like this for compatibility
            value = "INVOKE",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;onUpdateWalkingPlayer()V",
            shift = At.Shift.BEFORE),
        cancellable = true)
    public void onUpdateWalkingPlayerInvoke(CallbackInfo ci)
    {
        // Setup flags
        Vec3d position = new Vec3d(this.posX, this.getEntityBoundingBox().minY, this.posZ);
        Vec2f rotation = new Vec2f(this.rotationYaw, this.rotationPitch);
        boolean moving = isMoving(position);
        boolean rotating = isRotating(rotation);

        updateWalkingPlayerEvent = new OnUpdateWalkingPlayerEvent(moving, rotating, position, rotation);
        LambdaEventBus.INSTANCE.post(updateWalkingPlayerEvent);

        updateWalkingPlayerEvent = updateWalkingPlayerEvent.nextPhase();
        LambdaEventBus.INSTANCE.post(updateWalkingPlayerEvent);

        if (updateWalkingPlayerEvent.getCancelled()) {
            ci.cancel();

            if (!updateWalkingPlayerEvent.getCancelAll()) {
                // Copy flags from event
                moving = updateWalkingPlayerEvent.isMoving();
                rotating = updateWalkingPlayerEvent.isRotating();
                position = updateWalkingPlayerEvent.getPosition();
                rotation = updateWalkingPlayerEvent.getRotation();

                sendSprintPacket();
                sendSneakPacket();
                sendPlayerPacket(moving, rotating, position, rotation);

                this.prevOnGround = onGround;
            }

            ++this.positionUpdateTicks;
            this.autoJumpEnabled = this.mc.gameSettings.autoJump;
        } else {
            this.posX = updateWalkingPlayerEvent.getPosition().x;
            // posY doesn't seem to affect this, need to update bounding box?
            this.posZ = updateWalkingPlayerEvent.getPosition().z;
            this.rotationYaw = updateWalkingPlayerEvent.getRotation().getX();
            this.rotationPitch = updateWalkingPlayerEvent.getRotation().getY();
        }
    }

    @Inject(method = "onUpdateWalkingPlayer", at = @At("TAIL"))
    private void onUpdateWalkingPlayerTail(CallbackInfo ci) {
        final OnUpdateWalkingPlayerEvent event = updateWalkingPlayerEvent.nextPhase();
        LambdaEventBus.INSTANCE.post(event);
        this.posX = event.getPosInitial().x;
        this.posZ = event.getPosInitial().z;
        this.rotationYaw = event.getRotInitial().getX();
        this.rotationPitch = event.getRotInitial().getY();
    }

    @Inject(method = "pushOutOfBlocks", at = @At("HEAD"), cancellable = true)
    public void pushOutOfBlocks(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        final PlayerBlockPushEvent playerBlockPushEvent = new PlayerBlockPushEvent();
        LambdaEventBus.INSTANCE.post(playerBlockPushEvent);
        if (playerBlockPushEvent.getCancelled()) {
            cir.cancel();
        }
    }

    private void sendSprintPacket() {
        boolean sprinting = this.isSprinting();

        if (sprinting != this.serverSprintState) {
            if (sprinting) {
                this.connection.sendPacket(new CPacketEntityAction(this, CPacketEntityAction.Action.START_SPRINTING));
            } else {
                this.connection.sendPacket(new CPacketEntityAction(this, CPacketEntityAction.Action.STOP_SPRINTING));
            }
            this.serverSprintState = sprinting;
        }
    }

    private void sendSneakPacket() {
        boolean sneaking = this.isSneaking();

        if (sneaking != this.serverSneakState) {
            if (sneaking) {
                this.connection.sendPacket(new CPacketEntityAction(this, CPacketEntityAction.Action.START_SNEAKING));
            } else {
                this.connection.sendPacket(new CPacketEntityAction(this, CPacketEntityAction.Action.STOP_SNEAKING));
            }
            this.serverSneakState = sneaking;
        }
    }

    private void sendPlayerPacket(boolean moving, boolean rotating, Vec3d position, Vec2f rotation) {
        if (!this.isCurrentViewEntity()) return;

        if (this.isRiding()) {
            this.connection.sendPacket(new CPacketPlayer.PositionRotation(this.motionX, -999.0D, this.motionZ, rotation.getX(), rotation.getY(), onGround));
            moving = false;
        } else if (moving && rotating) {
            this.connection.sendPacket(new CPacketPlayer.PositionRotation(position.x, position.y, position.z, rotation.getX(), rotation.getY(), onGround));
        } else if (moving) {
            this.connection.sendPacket(new CPacketPlayer.Position(position.x, position.y, position.z, onGround));
        } else if (rotating) {
            this.connection.sendPacket(new CPacketPlayer.Rotation(rotation.getX(), rotation.getY(), onGround));
        } else if (this.prevOnGround != onGround) {
            this.connection.sendPacket(new CPacketPlayer(onGround));
        }

        if (moving) {
            this.positionUpdateTicks = 0;
        }
    }

    private boolean isMoving(Vec3d position) {
        double xDiff = position.x - this.lastReportedPosX;
        double yDiff = position.y - this.lastReportedPosY;
        double zDiff = position.z - this.lastReportedPosZ;

        return this.positionUpdateTicks >= 20 || xDiff * xDiff + yDiff * yDiff + zDiff * zDiff > 9.0E-4D;
    }

    private boolean isRotating(Vec2f rotation) {
        double yawDiff = rotation.getX() - this.lastReportedYaw;
        double pitchDiff = rotation.getY() - this.lastReportedPitch;

        return yawDiff != 0.0D || pitchDiff != 0.0D;
    }
}
