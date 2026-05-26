package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 右侧常驻记分牌，展示玩家当前生态战斗等级与金币余额。
 */
public class ScoreboardManager implements Listener {

    private static final String OBJECTIVE_NAME = "servercore";
    private static final String[] ENTRIES = {
            "§0", "§1", "§2", "§3", "§4", "§5"
    };

    private final ServerCorePlugin plugin;
    private final PowerLevelManager powerLevelManager;
    private final EconomyManager economyManager;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ScoreboardManager(ServerCorePlugin plugin, PowerLevelManager powerLevelManager, EconomyManager economyManager) {
        this.plugin = plugin;
        this.powerLevelManager = powerLevelManager;
        this.economyManager = economyManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> update(event.getPlayer()), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        if (!player.isOnline()) return;

        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), uuid -> createBoard());
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            board = createBoard();
            boards.put(player.getUniqueId(), board);
            objective = board.getObjective(OBJECTIVE_NAME);
        }

        double currentPower = powerLevelManager.getCurrentPower(player);
        double targetPower = powerLevelManager.calculateTargetPower(player);
        long coins = economyManager.getBalance(player.getUniqueId());

        setLine(board, objective, 5, "<dark_gray>────────────</dark_gray>");
        setLine(board, objective, 4, "<gray>战斗等级</gray>");
        setLine(board, objective, 3, "<gold>Lv. " + formatPower(currentPower) + "</gold> <dark_gray>/ " + formatPower(targetPower) + "</dark_gray>");
        setLine(board, objective, 2, "<gray>Coins</gray>");
        setLine(board, objective, 1, "<yellow>" + NumberFormat.getIntegerInstance(Locale.US).format(coins) + "</yellow>");
        setLine(board, objective, 0, "<dark_gray>────────────</dark_gray>");

        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
    }

    private Scoreboard createBoard() {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        board.registerNewObjective(
                OBJECTIVE_NAME,
                Criteria.DUMMY,
                ServerCorePlugin.getMiniMessage().deserialize("<gradient:#00ffaa:#00aaff><bold>ServerCore</bold></gradient>")
        ).setDisplaySlot(DisplaySlot.SIDEBAR);
        return board;
    }

    private void setLine(Scoreboard board, Objective objective, int score, String miniMessage) {
        String entry = ENTRIES[score];
        String teamName = "line_" + score;
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.addEntry(entry);
        }

        Component text = ServerCorePlugin.getMiniMessage().deserialize(miniMessage);
        team.prefix(text);
        objective.getScore(entry).setScore(score);
    }

    private String formatPower(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
