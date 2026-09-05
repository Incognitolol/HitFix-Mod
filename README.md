
Shoutout @dewgs https://github.com/dewgs

# HitFix

A Forge mod that gives Minecraft 1.7.10 the inbound packet timing of 1.8.9.

1.7.10 only processes packets from the server once per game tick. 1.8.9 processes them
on every rendered frame. HitFix makes 1.7.10 do what 1.8.9 does, using the method the
1.7.10 client already has, and nothing else.

## What you notice

Everything the server tells you shows up on screen sooner. You see your hit land on
someone up to 50 ms earlier than on vanilla 1.7.10. A pearl moves your camera up to
50 ms earlier. Hit flashes, particles, health, other players' movement: all of it
reaches your screen on the frame it arrived instead of on the next tick.

The game does not run faster. Ticks still happen every 50 ms. HitFix uses the time
between ticks to process packets that would otherwise sit in a queue, so the frames
you render in that gap use current information instead of information from the last
tick.

## The timeline

```
1.7.10
  Tick A  (0 ms)   packets processed
                   ...... 50 ms gap, queue fills, nothing applied ......
  Tick B  (50 ms)  packets processed

1.8.9
  Tick A  (0 ms)   packets processed
  frame            packets processed
  frame            packets processed
  frame            packets processed
  Tick B  (50 ms)  packets processed
```

That 50 ms gap is the whole difference. It is also why a 1.7.10 client answers
transaction packets in 50 ms steps, while a 1.8.9 client answers them on the frame
they arrive, close to keep-alive speed.

## 1.7.10: queue, drain once per tick

Inbound packets go into a queue. Only the few marked `hasPriority()`, such as
keep-alives, are handled immediately.

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
            packet.processPacket(this.netHandler);   // applied here
        }
        this.netHandler.onNetworkTick();
    }
    this.channel.flush();
}
```

The only caller while you are in a world is the world tick:

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

and the world tick runs at the end of `runTick`, after entities have already been
updated for that tick:

```java
// net.minecraft.client.Minecraft  (MCP 1.7.10)
public void runTick() {
    ...
    if (!this.isGamePaused) {
        this.theWorld.updateEntities();           // physics for this tick
    }
    ...
    if (!this.isGamePaused) {
        this.theWorld.tick();                     // packets drained here, every 50 ms
    }
    ...
}
```

A packet that arrives just after a tick sits in the queue for almost 50 ms. On average
it waits 25 ms, and everything from the last 50 ms is applied in one burst.

## 1.8.9: process immediately, hop to the main thread, drain every frame

1.8.9 has no queue in `NetworkManager`. The packet is handled as soon as it is read:

```java
// net.minecraft.network.NetworkManager  (MCP 1.8.9)
protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
    if (this.channel.isOpen()) {
        try {
            packet.processPacket(this.packetListener);
        } catch (ThreadQuickExitException e) {
            // handler re-scheduled itself onto the main thread
        }
    }
}
```

Every handler starts with a thread check. Off the main thread it schedules itself and
bails out:

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

The scheduled tasks are drained at the top of the game loop, once per rendered frame,
before any tick runs on that frame:

```java
// net.minecraft.client.Minecraft  (MCP 1.8.9)
private void runGameLoop() throws IOException {
    ...
    synchronized (this.scheduledTasks) {
        while (!this.scheduledTasks.isEmpty()) {
            Util.runTask((FutureTask) this.scheduledTasks.poll(), logger);   // every frame
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

A packet waits for the next frame, not the next tick. At 144 FPS that is about 3.5 ms
on average instead of 25 ms.

## What HitFix does

It calls the 1.7.10 drain method where 1.8.9 drains: at the start of every game loop
pass before the tick, and on every frame in between.

```java
@SubscribeEvent
public void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase == TickEvent.Phase.START) drain();   // before this tick's physics
}

@SubscribeEvent
public void onRenderTick(TickEvent.RenderTickEvent event) {
    if (event.phase == TickEvent.Phase.START) drain();   // every frame between ticks
}

private static void drain() {
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

That is the whole mod. No packet is modified, delayed, dropped or added. No mixins,
no reflection, no bytecode patches. The vanilla end-of-tick drain still runs and finds
an empty queue.

### Not a cheat

HitFix sends nothing a 1.8.9 client does not send and applies nothing a 1.8.9 client
does not apply. Tick rate, physics, reach and outbound packets are unchanged. The
server sees a 1.7.10 client that responds the way every 1.8.9 client already does.

The one effect beyond rendering: because packets are applied before the next tick's
physics instead of after, an incoming velocity or teleport for your own player is
integrated on the next tick rather than the one after. That is exactly 1.8.9's
behaviour, and it is the reason the drain also runs at tick start and not only per
frame.

