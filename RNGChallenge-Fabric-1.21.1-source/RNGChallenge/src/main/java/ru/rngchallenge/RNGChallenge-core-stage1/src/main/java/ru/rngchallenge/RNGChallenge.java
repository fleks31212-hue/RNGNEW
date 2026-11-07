package ru.rngchallenge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * RNG Challenge: first-stage server session core.
 * Task generation, rewards, punishments, HUD and sound assets are later stages.
 */
public final class RNGChallenge implements ModInitializer {
    private static final SessionManager SESSIONS = new SessionManager();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(SESSIONS::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SESSIONS.clear());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("rngstart")
                .executes(ctx -> startOne(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                .then(literal("all")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> startMany(ctx.getSource(),
                        ctx.getSource().getServer().getPlayerManager().getPlayerList())))
                .then(literal("team")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("targets", EntityArgumentType.players())
                        .executes(ctx -> startMany(ctx.getSource(),
                            EntityArgumentType.getPlayers(ctx, "targets")))))
                .then(argument("target", EntityArgumentType.player())
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> startOne(ctx.getSource(),
                        EntityArgumentType.getPlayer(ctx, "target")))));

            dispatcher.register(literal("rngstop")
                .executes(ctx -> stopOne(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                .then(argument("target", EntityArgumentType.player())
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> stopOne(ctx.getSource(),
                        EntityArgumentType.getPlayer(ctx, "target")))));

            dispatcher.register(literal("rngskip")
                .executes(ctx -> skipOne(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                .then(argument("target", EntityArgumentType.player())
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> skipOne(ctx.getSource(),
                        EntityArgumentType.getPlayer(ctx, "target")))));

            dispatcher.register(literal("rngstats")
                .executes(ctx -> showStats(ctx.getSource(), ctx.getSource().getPlayerOrThrow())));

            dispatcher.register(literal("rngsound")
                .then(literal("on").executes(ctx -> setSound(ctx.getSource(),
                    ctx.getSource().getPlayerOrThrow(), true)))
                .then(literal("off").executes(ctx -> setSound(ctx.getSource(),
                    ctx.getSource().getPlayerOrThrow(), false))));
        });
    }

    private static boolean isSpectator(ServerPlayerEntity player) {
        return player.interactionManager.getGameMode() == GameMode.SPECTATOR;
    }

    private static int startOne(ServerCommandSource source, ServerPlayerEntity player) {
        if (isSpectator(player)) {
            source.sendError(Text.literal("Наблюдатели не участвуют в RNG Challenge."));
            return 0;
        }
        boolean started = SESSIONS.start(player);
        player.sendMessage(Text.literal(started
            ? "§aRNG Challenge запущен! Первое задание должно начинаться сразу."
            : "§eУ тебя уже есть активная сессия."), false);
        return started ? 1 : 0;
    }

    private static int startMany(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        int count = 0;
        for (ServerPlayerEntity player : players) {
            if (!isSpectator(player) && SESSIONS.start(player)) {
                player.sendMessage(Text.literal("§aRNG Challenge запущен!"), false);
                count++;
            }
        }
        final int startedCount = count;
        source.sendFeedback(() -> Text.literal("RNG Challenge запущен для игроков: " + startedCount), true);
        return count;
    }

    private static int stopOne(ServerCommandSource source, ServerPlayerEntity player) {
        boolean stopped = SESSIONS.stop(player.getUuid());
        player.sendMessage(Text.literal(stopped
            ? "§cRNG Challenge остановлен. Прогресс сброшен."
            : "§eУ тебя нет активного испытания."), false);
        return stopped ? 1 : 0;
    }

    private static int skipOne(ServerCommandSource source, ServerPlayerEntity player) {
        PlayerSession session = SESSIONS.get(player.getUuid());
        if (session == null || session.phase != SessionPhase.ACTIVE) {
            player.sendMessage(Text.literal("§eНет активного задания для пропуска."), false);
            return 0;
        }
        SESSIONS.failCurrentTask(player);
        return 1;
    }

    private static int showStats(ServerCommandSource source, ServerPlayerEntity player) {
        PlayerSession s = SESSIONS.get(player.getUuid());
        if (s == null) {
            player.sendMessage(Text.literal("§7Нет активного испытания."), false);
            return 1;
        }
        player.sendMessage(Text.literal("§6RNG Challenge — статистика"), false);
        player.sendMessage(Text.literal("§fПобеды: §a" + s.wins + "  §fПоражения: §c" + s.losses), false);
        player.sendMessage(Text.literal("§fСерия: §e" + s.streak), false);
        player.sendMessage(Text.literal("§fФаза: §b" + (s.phase == SessionPhase.ACTIVE ? "задание" : "передышка")), false);
        player.sendMessage(Text.literal("§fАктивное время: §e" + (s.activeTicks / 20) + " сек."), false);
        return 1;
    }

    private static int setSound(ServerCommandSource source, ServerPlayerEntity player, boolean enabled) {
        PlayerSession s = SESSIONS.getOrCreateSettings(player.getUuid());
        s.soundEnabled = enabled;
        player.sendMessage(Text.literal(enabled ? "§aЗвуки включены." : "§eЗвуки выключены."), false);
        return 1;
    }

    public enum SessionPhase { ACTIVE, BREAK }

    public static final class PlayerSession {
        public final UUID playerId;
        public SessionPhase phase = SessionPhase.ACTIVE;
        public int wins;
        public int losses;
        public int streak;
        public long activeTicks;
        public int breakTicksRemaining;
        public boolean soundEnabled = true;
        public String currentTaskId = "";

        private PlayerSession(UUID playerId) {
            this.playerId = playerId;
        }
    }

    public static final class SessionManager {
        private static final int BREAK_TICKS = 30 * 20;
        private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

        public boolean start(ServerPlayerEntity player) {
            if (isSpectator(player) || sessions.containsKey(player.getUuid())) return false;
            sessions.put(player.getUuid(), new PlayerSession(player.getUuid()));
            // The task engine will assign the first real task immediately in stage 2.
            player.sendMessage(Text.literal("§7Сессия создана. Система заданий будет подключена следующим этапом."), false);
            return true;
        }

        public boolean stop(UUID playerId) {
            return sessions.remove(playerId) != null;
        }

        public PlayerSession get(UUID playerId) {
            return sessions.get(playerId);
        }

        public PlayerSession getOrCreateSettings(UUID playerId) {
            return sessions.computeIfAbsent(playerId, PlayerSession::new);
        }

        public void tick(MinecraftServer server) {
            for (PlayerSession session : sessions.values()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
                if (player == null) continue;
                if (session.phase == SessionPhase.ACTIVE) {
                    session.activeTicks++;
                } else if (session.breakTicksRemaining > 0) {
                    session.breakTicksRemaining--;
                    if (session.breakTicksRemaining == 0) {
                        session.phase = SessionPhase.ACTIVE;
                        player.sendMessage(Text.literal("§aПередышка закончилась."), false);
                        // The task engine will assign the next task here in stage 2.
                    }
                }
            }
        }

        public void completeCurrentTask(ServerPlayerEntity player) {
            PlayerSession s = sessions.get(player.getUuid());
            if (s == null || s.phase != SessionPhase.ACTIVE) return;
            s.wins++;
            s.streak++;
            beginBreak(s, player);
        }

        public void failCurrentTask(ServerPlayerEntity player) {
            PlayerSession s = sessions.get(player.getUuid());
            if (s == null || s.phase != SessionPhase.ACTIVE) return;
            s.losses++;
            s.streak = 0;
            beginBreak(s, player);
        }

        private void beginBreak(PlayerSession s, ServerPlayerEntity player) {
            s.phase = SessionPhase.BREAK;
            s.breakTicksRemaining = BREAK_TICKS;
            s.currentTaskId = "";
            player.sendMessage(Text.literal("§eПередышка: 30 секунд."), false);
        }

        public void clear() {
            sessions.clear();
        }
    }
}
