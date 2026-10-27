package dev.rtpgoto;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only mod. The server decides where /rtp sends you, so this mod cannot choose the
 * destination. Instead it runs /rtp again and again until you land within a radius of the
 * target coordinates you set with /rtpgoto.
 */
public class RtpGotoClient implements ClientModInitializer {

	private enum State { IDLE, SEND, WAIT_TELEPORT, COOLDOWN }

	private static State state = State.IDLE;
	private static int targetX, targetZ;
	private static int radius = 500;
	private static int cooldownSeconds = 8;
	private static int timeoutSeconds = 20;
	private static int attempts = 0;
	private static int timer = 0;
	private static Vec3 posAtSend = Vec3.ZERO;
	private static ResourceKey<Level> dimAtSend = null;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("rtpgoto")
				.then(ClientCommandManager.literal("stop").executes(ctx -> {
					stop();
					msg(ctx.getSource(), "RTP Goto gestoppt.");
					return 1;
				}))
				.then(ClientCommandManager.literal("status").executes(ctx -> {
					msg(ctx.getSource(), "Status: " + state + ", Ziel " + targetX + " " + targetZ
						+ ", Radius " + radius + ", Versuche " + attempts
						+ ", Pause " + cooldownSeconds + "s");
					return 1;
				}))
				.then(ClientCommandManager.literal("delay")
					.then(ClientCommandManager.argument("sekunden", IntegerArgumentType.integer(1, 600))
						.executes(ctx -> {
							cooldownSeconds = IntegerArgumentType.getInteger(ctx, "sekunden");
							msg(ctx.getSource(), "Pause zwischen /rtp: " + cooldownSeconds + "s");
							return 1;
						})))
				.then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
					.then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
						.executes(ctx -> start(ctx.getSource(),
							IntegerArgumentType.getInteger(ctx, "x"),
							IntegerArgumentType.getInteger(ctx, "z"),
							radius))
						.then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(10, 1000000))
							.executes(ctx -> start(ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "x"),
								IntegerArgumentType.getInteger(ctx, "z"),
								IntegerArgumentType.getInteger(ctx, "radius"))))))
			);
		});

		ClientTickEvents.END_CLIENT_TICK.register(RtpGotoClient::tick);
	}

	private static int start(FabricClientCommandSource source, int x, int z, int r) {
		targetX = x;
		targetZ = z;
		radius = r;
		attempts = 0;
		timer = 0;
		state = State.SEND;
		msg(source, "Ziel " + x + " " + z + " (Radius " + r + "). Starte /rtp-Schleife. Stoppen: /rtpgoto stop");
		return 1;
	}

	private static void stop() {
		state = State.IDLE;
		timer = 0;
	}

	private static void msg(FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal("[RTP Goto] " + text));
	}

	private static void say(LocalPlayer player, String text) {
		player.displayClientMessage(Component.literal("[RTP Goto] " + text), false);
	}

	private static void tick(Minecraft client) {
		if (state == State.IDLE) return;
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			stop();
			return;
		}

		switch (state) {
			case SEND -> {
				posAtSend = player.position();
				dimAtSend = player.level().dimension();
				attempts++;
				player.connection.sendCommand("rtp");
				timer = 0;
				state = State.WAIT_TELEPORT;
			}
			case WAIT_TELEPORT -> {
				timer++;
				Vec3 now = player.position();
				double dx = now.x - posAtSend.x;
				double dz = now.z - posAtSend.z;
				boolean teleported = (dx * dx + dz * dz) > 32 * 32
					|| !player.level().dimension().equals(dimAtSend);
				if (teleported) {
					double tdx = now.x - targetX;
					double tdz = now.z - targetZ;
					double dist = Math.sqrt(tdx * tdx + tdz * tdz);
					if (dist <= radius) {
						say(player, "Angekommen nach " + attempts + " Versuchen: "
							+ (int) now.x + " " + (int) now.z + " (" + (int) dist + " Bloecke vom Ziel).");
						stop();
					} else {
						say(player, "Versuch " + attempts + ": " + (int) now.x + " " + (int) now.z
							+ " - " + (int) dist + " Bloecke entfernt, naechster Versuch gleich.");
						timer = 0;
						state = State.COOLDOWN;
					}
				} else if (timer > timeoutSeconds * 20) {
					// Kein Teleport erkannt (Cooldown, Menue oder Fehler) -> nach Pause erneut senden
					timer = 0;
					state = State.COOLDOWN;
				}
			}
			case COOLDOWN -> {
				timer++;
				if (timer >= cooldownSeconds * 20) {
					state = State.SEND;
				}
			}
			default -> {
			}
		}
	}
}
