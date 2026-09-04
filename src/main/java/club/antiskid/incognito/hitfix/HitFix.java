/*
 * HitFix - per-frame packet flush for Minecraft 1.7.10 (Forge)
 *
 * Vanilla 1.7.10 NetworkManager.channelRead0 puts every inbound packet that is
 * not hasPriority() into receivedPacketsQueue. That queue is only drained by
 * NetworkManager.processReceivedPackets(), which WorldClient.tick() calls once
 * per game tick, every 50 ms. A hit, knockback, teleport or pearl therefore
 * waits up to a full tick, 25 ms on average, before the client applies it.
 *
 * 1.8 drains its equivalent (Minecraft.scheduledTasks) at the top of
 * runGameLoop(), once per rendered frame. HitFix does the same on 1.7.10: it
 * calls processReceivedPackets() from Forge's RenderTickEvent (START), which
 * fires once per frame on the client thread, the only thread allowed to touch
 * game state.
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
        if (event.phase != TickEvent.Phase.END) return;

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

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !enabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (mc.isSingleplayer() && mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) return;

        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null) return;

        NetworkManager net = handler.getNetworkManager();
        if (net == null || !net.isChannelOpen()) return;

        net.processReceivedPackets();
    }
}
