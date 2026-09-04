# HitFix

Per-frame packet processing for Minecraft 1.7.10. A Forge mod that gives 1.7.10 the
same inbound packet timing 1.8.9 has: hits, knockback, teleports and pearls are
applied on the next rendered frame instead of waiting for the next 50 ms game tick.

## The problem

Every client receives packets on a Netty thread. Game state can only be touched from
the main thread, so each version has to hand packets over. 1.7.10 and 1.8.9 do that
differently, and the difference is a whole game tick of latency.

### 1.7.10: queue, then drain once per tick

Inbound packets go into a queue. Only keep-alives and a few others marked
`hasPriority()` are handled immediately.

```java
// net.minecraft.network.NetworkManager  (MCP 1.7.10)
protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
    if (this.channel.isOpen()) {
        if (packet.hasPriority()) {
            packet.processPacket(this.netHandler);   // keep-alive etc, immediate
        } else {
            this.receivedPacketsQueue.add(packet);    // everything else waits here
        }
    }
}

public void processReceivedPackets() {
    this.flushOutboundQueue();
    if (this.netHandler != null) {
        for (int i = 1000; !this.receivedPacketsQueue.isEmpty() && i >= 0; --i) {
            Packet packet = (Packet) this.receivedPacketsQueue.poll();
            packet.processPacket(this.netHandler);   // entity moves, velocity, teleports applied here
        }
        this.netHandler.onNetworkTick();
    }
    this.channel.flush();
}
```

The only caller of `processReceivedPackets()` while in a world is the world tick:

```java
// net.minecraft.client.multiplayer.WorldClient  (MCP 1.7.10)
public void tick() {
    super.tick();
    ...
    this.theProfiler.endStartSection("connection");
    this.sendQueue.processReceivedPackets();      // NetHandlerPlayClient -> NetworkManager
    ...
}
```

and the world tick runs 20 times per second from `Minecraft.runTick()`, at the very
end of the tick:

```java
// net.minecraft.client.Minecraft  (MCP 1.7.10)
public void runTick() {
    ...
    this.mcProfiler.endStartSection("pick");
    this.entityRenderer.getMouseOver(1.0F);
    ...
    if (!this.isGamePaused) {
        this.theWorld.updateEntities();
    }
    ...
    if (!this.isGamePaused) {
        this.theWorld.tick();                         // <- packets drained HERE, every 50 ms
    }
    ...
}
```

So a packet that arrives 1 ms after a tick sits in the queue for 49 ms. On average a
packet waits 25 ms, and every packet from the last 50 ms is applied in one burst.

### 1.8.9: process immediately, hop to the main thread, drain every frame

1.8.9 no longer queues in `NetworkManager`. The packet is processed right away on the
Netty thread:

```java
// net.minecraft.network.NetworkManager  (MCP 1.8.9)
protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
    if (this.channel.isOpen()) {
        try {
            packet.processPacket(this.packetListener);
        } catch (ThreadQuickExitException e) {
            // handler re-scheduled itself onto the main thread, nothing to do
        }
    }
}
```

Every handler in `NetHandlerPlayClient` starts with a thread check. If it is not on the
main thread it schedules itself there and bails out:

```java
// net.minecraft.network.PacketThreadUtil  (MCP 1.8.9)
public static <T extends INetHandler> void checkThreadAndEnqueue(final Packet<T> packet,
        final T handler, IThreadListener scheduler) throws ThreadQuickExitException {
    if (!scheduler.isCallingFromMinecraftThread()) {
        scheduler.addScheduledTask(new Runnable() {
            public void run() {
                packet.processPacket(handler);
            }
        });
        throw ThreadQuickExitException.INSTANCE;
    }
}

// net.minecraft.client.network.NetHandlerPlayClient  (MCP 1.8.9)
public void handleEntityVelocity(S12PacketEntityVelocity packetIn) {
    PacketThreadUtil.checkThreadAndEnqueue(packetIn, this, this.gameController);
    Entity entity = this.clientWorldController.getEntityByID(packetIn.getEntityID());
    if (entity != null) {
        entity.setVelocity(...);
    }
}
```

And the scheduled tasks are drained at the top of the game loop, which runs once per
rendered frame, before ticks and before rendering:

```java
// net.minecraft.client.Minecraft  (MCP 1.8.9)
private void runGameLoop() throws IOException {
    ...
    synchronized (this.scheduledTasks) {
        while (!this.scheduledTasks.isEmpty()) {
            Util.runTask((FutureTask) this.scheduledTasks.poll(), logger);   // <- every FRAME
        }
    }
    ...
    for (int j = 0; j < this.timer.elapsedTicks; ++j) {
        this.runTick();
    }
    ...
    this.entityRenderer.updateCameraAndRender(this.timer.renderPartialTicks, i);
}
```

A packet arriving between frames waits for the next frame, not the next tick. At 144
FPS that is about 3.5 ms on average instead of 25 ms.

## What HitFix does

1.7.10 already has the drain method. It is just never called more than once per tick.
HitFix calls it once per frame from Forge's render tick event, which fires on the main
thread at the start of the render pass, the same place 1.8.9 drains its task queue.

```java
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
```

No packets are modified, delayed or generated. No mixins, no reflection, no
bytecode patches. The vanilla per-tick drain still runs and finds an empty queue.

### Why it is smoother

On vanilla 1.7.10 everything that arrived in the last 50 ms is applied at once at the
tick boundary: entity position targets, velocity, teleports, block changes. Between
boundaries nothing new happens. That gives the characteristic 1.7 feel where an
opponent's knockback or a pearl "pops" a moment after it should.

With HitFix each packet is applied on the frame it arrives. Entity interpolation
targets update as soon as the server sends them, so other players track their real
position more closely. Knockback you receive is applied on the same frame, so your own
movement reacts without a hitch. Teleports and pearls land where the server put you
instead of one tick later.

The tick rate is unchanged. Physics, block updates and your own outbound packets still
run at 20 Hz. HitFix only changes when inbound packets are consumed.
