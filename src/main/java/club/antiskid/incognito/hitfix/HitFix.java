/*
 * HitFix - 1.8.9 inbound packet timing for Minecraft 1.7.10 (Forge)
 *
 * 1.7.10: NetworkManager.channelRead0 puts every inbound packet that is not
 * hasPriority() into receivedPacketsQueue. The queue is only drained by
 * NetworkManager.processReceivedPackets(), called from WorldClient.tick() at
 * the END of Minecraft.runTick(), after updateEntities(). So a packet waits for
 * the next 50 ms tick, and even then it lands after that tick's physics, so it
 * takes effect one tick later still.
 *
 * 1.8.9: packet handlers hop to the main thread through Minecraft.scheduledTasks,
 * which runGameLoop() drains at the TOP of every rendered frame, before runTick().
 * Every packet is applied on the frame it arrives and before the next tick's
 * physics.
 *
 * HitFix reproduces the 1.8.9 order on 1.7.10 with the drain method vanilla
 * already has:
 *   ClientTickEvent START  -> processReceivedPackets() before this tick's physics
 *   RenderTickEvent START  -> processReceivedPackets() on every frame between ticks
 * The vanilla end-of-tick drain still runs and finds an empty queue.

 */
package club.antiskid.incognito.hitfix;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import java.nio.charset.StandardCharsets;

@Mod(modid = HitFix.MODID, name = "HitFix", version = HitFix.VERSION, acceptableRemoteVersions = "*")
public class HitFix {

    public static final String MODID = "hitfix";
    public static final String VERSION = "1.0";
    public static final String CHANNEL = "1337";

    public static volatile boolean enabled = true;

    private NetHandlerPlayClient announced;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            drain();
            return;
        }
        announce();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) drain();
    }

    private static void drain() {
        if (!enabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (mc.isSingleplayer() && mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) return;

        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null) return;

        NetworkManager net = handler.getNetworkManager();
        if (net == null || !net.isChannelOpen()) return;

        net.processReceivedPackets();
    }

    private void announce() {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null || mc.thePlayer == null) {
            announced = null;
            return;
        }
        if (handler == announced) return;
        announced = handler;

        handler.addToSendQueue(new C17PacketCustomPayload("REGISTER", CHANNEL.getBytes(StandardCharsets.UTF_8)));
        handler.addToSendQueue(new C17PacketCustomPayload(CHANNEL, ("HitFix/" + VERSION).getBytes(StandardCharsets.UTF_8)));
    }
}
