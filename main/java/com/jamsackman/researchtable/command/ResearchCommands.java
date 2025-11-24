package com.jamsackman.researchtable.command;

import com.jamsackman.researchtable.ResearchTableMod;
import com.jamsackman.researchtable.config.ResearchTableConfig;
import com.jamsackman.researchtable.state.ResearchPersistentState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.Locale;

public class ResearchCommands {

    private static final SuggestionProvider<ServerCommandSource> PROGRESSION_SUGGESTIONS = (ctx, builder) -> {
        for (ResearchTableConfig.ProgressionSetting setting : ResearchTableConfig.ProgressionSetting.values()) {
            builder.suggest(setting.name().toLowerCase(Locale.ROOT));
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("research")
                // /research give <targets> <amount> <enchantmentId>
                .then(CommandManager.literal("give")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .then(CommandManager.argument("amount", IntegerArgumentType.integer(Integer.MIN_VALUE))
                                        .then(CommandManager.argument("enchantment", IdentifierArgumentType.identifier())
                                                .executes(ctx -> {
                                                    Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
                                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                    Identifier enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment");

                                                    var server = ctx.getSource().getServer();
                                                    var state = ResearchTableMod.getResearchState(server);

                                                    for (ServerPlayerEntity p : targets) {
                                                        state.addProgress(p.getUuid(), enchId.toString(), amount);
                                                        if (amount > 0) state.addTotalPoints(p.getUuid(), amount);
                                                        // optionally: re-check thresholds and grant progress advs
                                                        ResearchTableMod.sendResearchSync(p);
                                                    }

                                                    ctx.getSource().sendFeedback(
                                                            () -> Text.literal("Added " + amount + " to " + enchId + " for " + targets.size() + " player(s)."),
                                                            true
                                                    );
                                                    return 1;
                                                })
                                        ))))
                // /research unlock <targets> <enchantment|all> <level>
                .then(CommandManager.literal("unlock")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .then(CommandManager.argument("enchantment", IdentifierArgumentType.identifier())
                                        .then(CommandManager.argument("level", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
                                                    Identifier enchOrAll = IdentifierArgumentType.getIdentifier(ctx, "enchantment");
                                                    int level = IntegerArgumentType.getInteger(ctx, "level");

                                                    var server = ctx.getSource().getServer();
                                                    var state = ResearchTableMod.getResearchState(server);

                                                    boolean all = enchOrAll.getPath().equals("all");
                                                    if (all) {
                                                        // set every enchant to at least this level
                                                        var reg = server.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
                                                        reg.forEach(ench -> {
                                                            var id = reg.getId(ench);
                                                            if (id == null) return;
                                                            int needed = ResearchPersistentState.requiredPointsForLevel(level, ench.getMaxLevel(),
                                                                    ResearchTableMod.getProgressionMultiplier(ctx.getSource().getWorld()));
                                                            for (ServerPlayerEntity p : targets) {
                                                                state.setProgressToAtLeast(p.getUuid(), id.toString(), needed);
                                                            }
                                                        });
                                                    } else {
                                                        float mult = ResearchTableMod.getProgressionMultiplier(ctx.getSource().getWorld());
                                                        Enchantment targetEnch = server.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT).get(enchOrAll);
                                                        int needed = ResearchPersistentState.requiredPointsForLevel(level,
                                                                targetEnch != null ? targetEnch.getMaxLevel() : level,
                                                                mult);
                                                        for (ServerPlayerEntity p : targets) {
                                                            state.setProgressToAtLeast(p.getUuid(), enchOrAll.toString(), needed);
                                                        }
                                                    }

                                                    for (ServerPlayerEntity p : targets) ResearchTableMod.sendResearchSync(p);

                                                    ctx.getSource().sendFeedback(
                                                            () -> Text.literal("Unlocked " + (all ? "ALL enchants" : enchOrAll) + " to level " + level + " for " + targets.size() + " player(s)."),
                                                            true
                                                    );
                                                    return 1;
                                                })
                                        ))))
                // /research reset <targets>
                .then(CommandManager.literal("reset")
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .executes(ctx -> {
                                    Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
                                    var server = ctx.getSource().getServer();
                                    var state = ResearchTableMod.getResearchState(server);
                                    for (ServerPlayerEntity p : targets) {
                                        state.resetAll(p.getUuid());
                                        ResearchTableMod.sendResearchSync(p);
                                    }
                                    ctx.getSource().sendFeedback(
                                            () -> Text.literal("Reset research for " + targets.size() + " player(s)."),
                                            true
                                    );
                                    return 1;
                                })
                        ))
        );

        d.register(CommandManager.literal("rt_progression")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.argument("mode", StringArgumentType.word())
                        .suggests(PROGRESSION_SUGGESTIONS)
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "mode").toUpperCase(Locale.ROOT);
                            ResearchTableConfig.ProgressionSetting setting;
                            try {
                                setting = ResearchTableConfig.ProgressionSetting.valueOf(raw);
                            } catch (IllegalArgumentException ex) {
                                ctx.getSource().sendError(Text.literal("Unknown progression mode: " + raw));
                                return 0;
                            }

                            var world = ctx.getSource().getWorld();
                            world.getGameRules().get(ResearchTableMod.GR_PROGRESSION)
                                    .set(setting.toRuleValue(), ctx.getSource().getServer());

                            ctx.getSource().getServer().getPlayerManager().getPlayerList()
                                    .forEach(ResearchTableMod::sendResearchSync);

                            ctx.getSource().sendFeedback(
                                    () -> Text.literal("Set research progression to " + setting.displayName()),
                                    true
                            );
                            return 1;
                        }))
        );
    }
}