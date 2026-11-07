package ru.rngchallenge;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RNGChallenge implements ModInitializer {
    public static final String MOD_ID = "rngchallenge";
    private static final Map<UUID, PlayerRun> RUNS = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    private static final List<Task> TASKS = List.of(
        new Task("Добыть ведро молока", Difficulty.EASY, 60, "milk"),
        new Task("Получить 8 булыжника", Difficulty.VERY_EASY, 30, "cobble"),
        new Task("Скрафтить факелы", Difficulty.EASY, 60, "torch"),
        new Task("Победить зомби", Difficulty.MEDIUM, 120, "zombie"),
        new Task("Добыть железный слиток", Difficulty.MEDIUM, 120, "iron")
    );

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("rngstart").executes(ctx -> start(ctx.getSource().getPlayerOrThrow()))
                .then(argument("target", StringArgumentType.word()).executes(ctx -> {
                    requireOperator(ctx.getSource());
                    String target = StringArgumentType.getString(ctx, "target");
                    if (target.equalsIgnoreCase("all")) {
                        int count = 0;
                        for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                            start(p); count++;
                        }
                        ctx.getSource().sendFeedback(() -> Text.literal("RNG Challenge запущен для " + count + " игроков."), true);
                        return count;
                    }
                    ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(target);
                    if (p == null) { ctx.getSource().sendError(Text.literal("Игрок не найден.")); return 0; }
                    return start(p);
                })));
            dispatcher.register(literal("rngstop").executes(ctx -> stop(ctx.getSource().getPlayerOrThrow()))
                .then(argument("target", StringArgumentType.word()).executes(ctx -> {
                    requireOperator(ctx.getSource());
                    ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(StringArgumentType.getString(ctx, "target"));
                    if (p == null) { ctx.getSource().sendError(Text.literal("Игрок не найден.")); return 0; }
                    return stop(p);
                })));
            dispatcher.register(literal("rngskip").executes(ctx -> skip(ctx.getSource().getPlayerOrThrow()))
                .then(argument("target", StringArgumentType.word()).executes(ctx -> {
                    requireOperator(ctx.getSource());
                    ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(StringArgumentType.getString(ctx, "target"));
                    if (p == null) { ctx.getSource().sendError(Text.literal("Игрок не найден.")); return 0; }
                    return skip(p);
                })));
            dispatcher.register(literal("rngstats").executes(ctx -> stats(ctx.getSource().getPlayerOrThrow())));
            dispatcher.register(literal("rngsound").then(literal("on").executes(ctx -> sound(ctx.getSource().getPlayerOrThrow(), true)))
                .then(literal("off").executes(ctx -> sound(ctx.getSource().getPlayerOrThrow(), false))));
        });

        ServerTickEvents.END_SERVER_TICK.register(RNGChallenge::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlayerRun run = RUNS.remove(handler.getPlayer().getUuid());
            if (run != null && run.activePenalty) {
                // Отключение во время наказания считается смертью/провалом.
                run.streak = 0;
                run.failures++;
            }
            // Прогресс сессии не сохраняется после выхода.
        });
    }

    private static void requireOperator(net.minecraft.server.command.ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (source.hasPermissionLevel(2)) return;
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
            Text.literal("Только оператор может управлять испытанием другого игрока.")
        ).create();
    }

    private static int start(ServerPlayerEntity p) {
        PlayerRun run = new PlayerRun();
        RUNS.put(p.getUuid(), run);
        nextTask(p, run);
        p.sendMessage(Text.literal("RNG Challenge запущен!").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int stop(ServerPlayerEntity p) {
        RUNS.remove(p.getUuid());
        p.sendMessage(Text.literal("RNG Challenge остановлен.").formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static int skip(ServerPlayerEntity p) {
        PlayerRun run = RUNS.get(p.getUuid());
        if (run == null) { p.sendMessage(Text.literal("Испытание не запущено."), false); return 0; }
        fail(p, run, "Задание пропущено");
        return 1;
    }

    private static int stats(ServerPlayerEntity p) {
        PlayerRun r = RUNS.get(p.getUuid());
        if (r == null) {
            p.sendMessage(Text.literal("Испытание не запущено. Статистика текущей сессии недоступна."), false);
            return 0;
        }
        p.sendMessage(Text.literal("RNG статистика: победы " + r.wins + ", провалы " + r.failures +
            ", серия " + r.streak + ", рекорд серии " + r.bestStreak + ".").formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int sound(ServerPlayerEntity p, boolean enabled) {
        PlayerRun r = RUNS.computeIfAbsent(p.getUuid(), id -> new PlayerRun());
        r.sound = enabled;
        p.sendMessage(Text.literal("Звуки RNG: " + (enabled ? "включены" : "выключены")), false);
        return 1;
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerRun run = RUNS.get(p.getUuid());
            if (run == null) continue;
            if (run.breakTicks > 0) {
                run.breakTicks--;
                if (run.breakTicks == 0) nextTask(p, run);
                continue;
            }
            if (run.task == null) continue;
            run.elapsedActiveTicks++;
            run.taskTicks--;
            if (run.taskTicks <= 0) {
                fail(p, run, "Время вышло!");
                continue;
            }
            if (isTaskComplete(p, run.task)) {
                win(p, run);
            }
            if (run.elapsedActiveTicks % 20 == 0) {
                p.sendMessage(Text.literal("Задание: " + run.task.name + " | Осталось: " + (run.taskTicks / 20) + " сек.")
                    .formatted(Formatting.WHITE), true);
            }
        }
    }

    private static boolean isTaskComplete(ServerPlayerEntity p, Task task) {
        // Базовые проверки-инвентарь. Счётчики добычи/крафта/убийств будут расширены
        // специализированными событиями в следующих итерациях.
        return switch (task.trigger) {
            case "milk" -> p.getInventory().contains(net.minecraft.item.Items.MILK_BUCKET.getDefaultStack());
            case "cobble" -> count(p, net.minecraft.item.Items.COBBLESTONE) >= 8;
            case "torch" -> count(p, net.minecraft.item.Items.TORCH) >= 4;
            case "iron" -> count(p, net.minecraft.item.Items.IRON_INGOT) >= 1;
            default -> false;
        };
    }

    private static int count(ServerPlayerEntity p, net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            var stack = p.getInventory().getStack(i);
            if (stack.isOf(item)) n += stack.getCount();
        }
        return n;
    }

    private static void win(ServerPlayerEntity p, PlayerRun r) {
        int base = switch (r.task.difficulty) {
            case VERY_EASY -> 1;
            case EASY -> 1 + RANDOM.nextInt(3);
            case MEDIUM -> 3 + RANDOM.nextInt(4);
            case HARD -> 6 + RANDOM.nextInt(5);
            case VERY_HARD -> 10 + RANDOM.nextInt(6);
            case EXTREME -> 15 + RANDOM.nextInt(6);
        };
        r.wins++; r.streak++; r.bestStreak = Math.max(r.bestStreak, r.streak);
        int bonus = switch (r.streak) {
            case 3 -> 3; case 5 -> 5; case 10 -> 10; case 15 -> 15; case 20 -> 20;
            default -> r.streak > 20 && r.streak % 5 == 0 ? 5 : 0;
        };
        giveDiamonds(p, base + bonus);
        p.sendMessage(Text.literal("Задание выполнено! +" + (base + bonus) + " алмазов.").formatted(Formatting.GREEN), false);
        r.task = null; r.breakTicks = 600; r.taskTicks = 0;
    }

    private static void giveDiamonds(ServerPlayerEntity p, int amount) {
        int left = amount;
        while (left > 0) {
            int part = Math.min(left, 64);
            var stack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, part);
            if (!p.getInventory().insertStack(stack)) {
                // Очередь ожидающей награды в базовом ядре будет расширена отдельным хранилищем.
                p.sendMessage(Text.literal("Награда ожидает свободного места в инвентаре!").formatted(Formatting.YELLOW), false);
                return;
            }
            left -= part;
        }
    }

    private static void fail(ServerPlayerEntity p, PlayerRun r, String reason) {
        r.failures++; r.streak = 0; r.task = null;
        p.sendMessage(Text.literal(reason + " RNG задание провалено!").formatted(Formatting.RED), false);
        p.sendMessage(Text.literal("Наказание: случайное последствие (ядро наказаний подключается следующим этапом).").formatted(Formatting.RED), false);
        r.activePenalty = true;
        r.penaltyTicks = 60;
        r.breakTicks = 60; // передышка начинается после короткой базовой фазы; наказания расширяются отдельно.
    }

    private static void nextTask(ServerPlayerEntity p, PlayerRun r) {
        Difficulty stage = stageFor(r.elapsedActiveTicks / 20);
        List<Task> pool = new ArrayList<>();
        for (Task t : TASKS) if (t.difficulty.ordinal() <= stage.ordinal()) pool.add(t);
        if (pool.isEmpty()) pool.add(TASKS.get(0));
        r.task = pool.get(RANDOM.nextInt(pool.size()));
        r.taskTicks = r.task.seconds * 20;
        r.activePenalty = false;
        r.penaltyTicks = 0;
        p.sendMessage(Text.literal("НОВОЕ ЗАДАНИЕ: " + r.task.name + " (" + r.task.seconds + " сек.)")
            .formatted(Formatting.GOLD), false);
    }

    private static Difficulty stageFor(long seconds) {
        if (seconds < 600) return Difficulty.EASY;
        if (seconds < 1200) return Difficulty.MEDIUM;
        if (seconds < 1800) return Difficulty.HARD;
        if (seconds < 2400) return Difficulty.VERY_HARD;
        return Difficulty.EXTREME;
    }

    private enum Difficulty { VERY_EASY, EASY, MEDIUM, HARD, VERY_HARD, EXTREME }
    private record Task(String name, Difficulty difficulty, int seconds, String trigger) {}

    private static class PlayerRun {
        Task task;
        int taskTicks, breakTicks, penaltyTicks;
        long elapsedActiveTicks;
        int wins, failures, streak, bestStreak;
        boolean activePenalty, sound = true;
    }
}
