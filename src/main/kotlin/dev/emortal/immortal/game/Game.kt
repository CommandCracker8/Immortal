package dev.emortal.immortal.game

import dev.emortal.immortal.config.GameOptions
import dev.emortal.immortal.event.GameDestroyEvent
import dev.emortal.immortal.event.PlayerJoinGameEvent
import dev.emortal.immortal.event.PlayerLeaveGameEvent
import dev.emortal.immortal.game.GameManager.gameNameTag
import dev.emortal.immortal.game.GameManager.joinGameOrNew
import dev.emortal.immortal.inventory.SpectatingGUI
import dev.emortal.immortal.util.*
import dev.emortal.immortal.util.RedisStorage.redisson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.adventure.audience.PacketGroupingAudience
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerStartSneakingEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.trait.PlayerEvent
import net.minestom.server.instance.Instance
import net.minestom.server.item.Material
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import org.tinylog.kotlin.Logger
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

abstract class Game(var gameOptions: GameOptions) : PacketGroupingAudience {

    private val playerCountTopic = redisson?.getTopic("playercount")

    val players: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    val spectators: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    val teams: MutableSet<Team> = ConcurrentHashMap.newKeySet()

    val playerLock = Object()

    val uuid = UUID.randomUUID()

    var gameState = GameState.WAITING_FOR_PLAYERS

    val gameName = GameManager.registeredClassMap[this::class]!!
    val gameTypeInfo = GameManager.registeredGameMap[gameName] ?: throw Error("Game type not registered")

    open var spawnPosition = Pos(0.5, 70.0, 0.5)

    val instance by lazy {
        instanceCreate().also {
            it.setTag(gameNameTag, gameTypeInfo.name)
        }
    }

    val eventNode = EventNode.type(
        "${gameTypeInfo.name}-$uuid",
        EventFilter.INSTANCE
    ) { a, b ->
        if (a is PlayerEvent) {
            return@type players.contains(a.player)
        } else {
            return@type b.uniqueId == instance.uniqueId
        }
    }

    val spectatorNode = if (gameOptions.allowsSpectators) EventNode.type(
        "${gameTypeInfo.name}-$uuid-spectator",
        EventFilter.INSTANCE
    ) { a, b ->
        if (a is PlayerEvent) {
            return@type spectators.contains(a.player)
        } else {
            return@type b.uniqueId == instance.uniqueId
        }
    } else null

    val coroutineScope = CoroutineScope(Dispatchers.IO)

    var startingTask: MinestomRunnable? = null
    var scoreboard: Sidebar? = null

    val spectatorGUI = SpectatingGUI()

    private var destroyed = false

    init {
        Manager.globalEvent.addChild(eventNode)
        if (spectatorNode != null) {
            Manager.globalEvent.addChild(spectatorNode)
        }

        spectatorNode?.listenOnly<PlayerUseItemEvent> {
            if (player.itemInMainHand.material() != Material.COMPASS) return@listenOnly
            player.openInventory(spectatorGUI.inventory)
        }
        spectatorNode?.listenOnly<PlayerStartSneakingEvent> {
            player.stopSpectating()
        }
        spectatorNode?.listenOnly<PlayerEntityInteractEvent> {
            val playerToSpectate = entity as? Player ?: return@listenOnly
            player.spectate(playerToSpectate)
        }

        if (gameTypeInfo.whenToRegisterEvents == WhenToRegisterEvents.IMMEDIATELY) registerEvents()

        if (gameOptions.showScoreboard) {
            scoreboard = Sidebar(gameTypeInfo.title)

            scoreboard?.createLine(Sidebar.ScoreboardLine("headerSpacer", Component.empty(), 99))

            scoreboard?.createLine(
                Sidebar.ScoreboardLine(
                    "infoLine",
                    Component.text()
                        .append(Component.text("Waiting for players...", NamedTextColor.GRAY))
                        .build(),
                    0
                )
            )
            scoreboard?.createLine(Sidebar.ScoreboardLine("footerSpacer", Component.empty(), -8))
            scoreboard?.createLine(
                Sidebar.ScoreboardLine(
                    "ipLine",
                    Component.text()
                        .append(Component.text("mc.emortal.dev ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("       ", NamedTextColor.DARK_GRAY, TextDecoration.STRIKETHROUGH))
                        .build(),
                    -9
                )
            )
        }

        Logger.info("A game of '${gameTypeInfo.name}' was created")
    }

    internal fun addPlayer(player: Player, joinMessage: Boolean = gameOptions.showsJoinLeaveMessages): CompletableFuture<Boolean> {
        synchronized(playerLock) {
            if (players.contains(player)) {
                Logger.warn("Game already contains player")
                return CompletableFuture.completedFuture(false)
            }

            Logger.info("${player.username} joining game '${gameTypeInfo.name}'")

            players.add(player)
            //spectatorGUI.refresh(players)

            player.isActive

            if (gameOptions.minPlayers > players.size && gameState == GameState.WAITING_FOR_PLAYERS) {
                scoreboard?.updateLineContent(
                    "infoLine",
                    Component.text(
                        "Waiting for players... (${gameOptions.minPlayers - players.size} more)",
                        NamedTextColor.GRAY
                    )
                )
            }

            player.respawnPoint = spawnPosition

            if (!instance.isRegistered) {
                players.forEach {
                    it.kick("Game failed to start")
                }
                destroy()
                return CompletableFuture.completedFuture(false)
            }

            val future = CompletableFuture<Boolean>()
            player.safeSetInstance(instance, spawnPosition).thenRun {
                player.reset()
                player.resetTeam()

                scoreboard?.addViewer(player)

                if (joinMessage) sendMessage(
                    Component.text()
                        .append(Component.text("JOIN", NamedTextColor.GREEN, TextDecoration.BOLD))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(player.username, NamedTextColor.GREEN))
                        .append(Component.text(" joined the game ", NamedTextColor.GRAY))
                        .also {
                            if (gameState == GameState.WAITING_FOR_PLAYERS) it.append(Component.text("(${players.size}/${gameOptions.maxPlayers})", NamedTextColor.DARK_GRAY))
                        }
                )
                playSound(Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 1.2f))
                player.playSound(Sound.sound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1f, 1f))
                player.clearTitle()
                player.sendActionBar(Component.empty())

                val joinEvent = PlayerJoinGameEvent(this, player)
                EventDispatcher.call(joinEvent)

                playerCountTopic?.publishAsync("$gameName ${GameManager.gameMap[gameName]?.sumOf { it.players.size } ?: 0}")

                CoroutineScope(Dispatchers.IO).launch {
                    playerJoin(player)
                }

                if (gameState == GameState.WAITING_FOR_PLAYERS && players.size >= gameOptions.minPlayers) {
                    if (startingTask == null) {
                        startCountdown()
                    }
                }

                future.complete(true)
            }

            return future
        }
    }

    internal fun removePlayer(player: Player, leaveMessage: Boolean = gameOptions.showsJoinLeaveMessages) {
        synchronized(playerLock) {
            if (!players.contains(player)) return

            Logger.info("${player.username} leaving game '${gameTypeInfo.name}'")

            teams.forEach {
                it.remove(player)
            }
            players.remove(player)

            playerCountTopic?.publishAsync("$gameName ${GameManager.gameMap[gameName]?.sumOf { it.players.size } ?: 0}")

            if (gameOptions.minPlayers > players.size && gameState == GameState.WAITING_FOR_PLAYERS) {
                scoreboard?.updateLineContent(
                    "infoLine",
                    Component.text(
                        "Waiting for players... (${gameOptions.minPlayers - players.size} more)",
                        NamedTextColor.GRAY
                    )
                )
            }

            //spectatorGUI.refresh(players)
            scoreboard?.removeViewer(player)

            val leaveEvent = PlayerLeaveGameEvent(this, player)
            EventDispatcher.call(leaveEvent)

            if (leaveMessage) sendMessage(
                Component.text()
                    .append(Component.text("QUIT", NamedTextColor.RED, TextDecoration.BOLD))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(player.username, NamedTextColor.RED))
                    .append(Component.text(" left the game ", NamedTextColor.GRAY))
                    .also {
                        if (gameState == GameState.WAITING_FOR_PLAYERS) it.append(Component.text("(${players.size}/${gameOptions.maxPlayers})", NamedTextColor.DARK_GRAY))
                    }
            )
            playSound(Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 0.5f))

            if (players.size < gameOptions.minPlayers) {
                if (startingTask != null) {
                    cancelCountdown()
                }
            }

            if (gameState == GameState.PLAYING) {
                val teamsWithPlayers = teams.filter { it.players.isNotEmpty() }
                if (teamsWithPlayers.size == 1) {
                    victory(teamsWithPlayers.first())
                }
                if (players.size == 1) {
                    victory(players.first())
                }
            }

            if (players.size == 0) {
                destroy()
            }

            playerLeave(player)
        }
    }

    internal fun addSpectator(player: Player): CompletableFuture<Boolean> {
        synchronized(playerLock) {
            if (spectators.contains(player)) return CompletableFuture.completedFuture(false)
            if (players.contains(player)) return CompletableFuture.completedFuture(false)

            Logger.info("${player.username} started spectating game '${gameTypeInfo.name}'")

            player.respawnPoint = spawnPosition

            val future = CompletableFuture<Boolean>()
            player.safeSetInstance(instance).thenRun {
                player.reset()

                scoreboard?.addViewer(player)

                player.isInvisible = true
                player.gameMode = GameMode.SPECTATOR
                player.isAllowFlying = true
                player.isFlying = true
                //player.inventory.setItemStack(4, ItemStack.of(Material.COMPASS))
                player.playSound(Sound.sound(SoundEvent.ENTITY_BAT_AMBIENT, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

                spectatorJoin(player)

                future.complete(true)
            }

            spectators.add(player)

            return future
        }
    }

    internal fun removeSpectator(player: Player) {
        synchronized(playerLock) {
            if (!spectators.contains(player)) return

            Logger.info("${player.username} stopped spectating game '${gameTypeInfo.name}'")

            spectators.remove(player)
            scoreboard?.removeViewer(player)

            spectatorLeave(player)
        }
    }

    open fun spectatorJoin(player: Player) {}
    open fun spectatorLeave(player: Player) {}

    abstract fun playerJoin(player: Player)
    abstract fun playerLeave(player: Player)
    abstract fun gameStarted()
    abstract fun gameDestroyed()

    private fun startCountdown() {
        if (gameOptions.countdownSeconds == 0) {
            start()
            return
        }

        startingTask = object : MinestomRunnable(coroutineScope = coroutineScope, repeat = Duration.ofSeconds(1), iterations = gameOptions.countdownSeconds) {

            override suspend fun run() {
                val currentIter = currentIteration.get()

                scoreboard?.updateLineContent(
                    "infoLine",
                    Component.text()
                        .append(Component.text("Starting in ${gameOptions.countdownSeconds - currentIter} seconds", NamedTextColor.GREEN))
                        .build()
                )

                if ((gameOptions.countdownSeconds - currentIter) < 5 || currentIter % 5 == 0) {
                    playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.AMBIENT, 1f, 1.2f))
                    showTitle(
                        Title.title(
                            Component.text(gameOptions.countdownSeconds - currentIter, NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.empty(),
                            Title.Times.times(
                                Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(250)
                            )
                        )
                    )
                }
            }

            override fun cancelled() {
                start()
            }

        }
    }

    fun cancelCountdown() {
        startingTask?.cancel()
        startingTask = null

        showTitle(
            Title.title(
                Component.empty(),
                Component.text("Start cancelled!", NamedTextColor.RED, TextDecoration.BOLD),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(1))
            )
        )
        playSound(Sound.sound(SoundEvent.ENTITY_VILLAGER_NO, Sound.Source.AMBIENT, 1f, 1f))
    }

    abstract fun registerEvents()

    fun start() {
        if (gameState == GameState.PLAYING) return

        playSound(Sound.sound(SoundEvent.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

        startingTask = null
        gameState = GameState.PLAYING
        scoreboard?.updateLineContent("infoLine", Component.empty())

        if (gameTypeInfo.whenToRegisterEvents == WhenToRegisterEvents.GAME_START) registerEvents()
        gameStarted()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true

        Logger.info("A game of '${gameTypeInfo.name}' is ending")

        Manager.globalEvent.removeChild(eventNode)

        try {
            coroutineScope.cancel()
        } catch(ignored: Throwable) {
            Logger.warn("Coroutine scope cancelled without a Job, likely not an issue")
        }

        gameDestroyed()

        val destroyEvent = GameDestroyEvent(this)
        EventDispatcher.call(destroyEvent)

        GameManager.gameMap[gameName]?.remove(this)

        teams.forEach {
            it.destroy()
        }

        val debugMode = System.getProperty("debug").toBoolean()
        val debugGame = System.getProperty("debuggame")

        // Both spectators and players
        getPlayers().forEach {
            scoreboard?.removeViewer(it)

            if (debugMode) {
                it.joinGameOrNew(debugGame)
            } else {
                it.sendServer("lobby")
            }
        }
        players.clear()
        spectators.clear()

        playerCountTopic?.publishAsync("$gameName ${GameManager.gameMap[gameName]?.sumOf { it.players.size } ?: 0}")
    }

    open fun canBeJoined(player: Player): Boolean {
        synchronized(players) {
            if (players.contains(player)) return false
            if (players.size >= gameOptions.maxPlayers) {
                return false
            }
            if (gameState == GameState.PLAYING) {
                return gameOptions.canJoinDuringGame
            }
            //if (gameOptions.private) {
            //    val party = gameCreator?.party ?: return false
            //
            //    return party.players.contains(player)
            //}
            return gameState.joinable
        }
    }

    fun registerTeam(team: Team): Team {
        teams.add(team)
        return team
    }

    fun victory(team: Team) {
        victory(team.players)
    }
    fun victory(player: Player) {
        victory(listOf(player))
    }

    open fun victory(winningPlayers: Collection<Player>) {
        gameState = GameState.ENDING

        val victoryTitle = Title.title(
            Component.text("VICTORY!", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text(EndGameQuotes.victory.random(), NamedTextColor.GRAY),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(3))
        )
        val defeatTitle = Title.title(
            Component.text("DEFEAT!", NamedTextColor.RED, TextDecoration.BOLD),
            Component.text(EndGameQuotes.defeat.random(), NamedTextColor.GRAY),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(3))
        )

        val victorySound = Sound.sound(SoundEvent.ENTITY_VILLAGER_CELEBRATE, Sound.Source.MASTER, 1f, 1f)
        val victorySound2 = Sound.sound(SoundEvent.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f)

        val defeatSound = Sound.sound(SoundEvent.ENTITY_VILLAGER_DEATH, Sound.Source.MASTER, 1f, 0.8f)

        players.forEach {
            if (winningPlayers.contains(it)) {
                it.showTitle(victoryTitle)
                it.playSound(victorySound)
                it.playSound(victorySound2)
            } else {
                it.showTitle(defeatTitle)
                it.playSound(defeatSound)
            }
        }

        gameWon(winningPlayers)

        Manager.scheduler.buildTask { destroy() }.delay(Duration.ofSeconds(6)).schedule()
    }

    open fun gameWon(winningPlayers: Collection<Player>) {}

    abstract fun instanceCreate(): Instance

    override fun getPlayers(): MutableCollection<Player> = (players + spectators).toMutableSet()

    override fun equals(other: Any?): Boolean {
        if (other !is Game) return false
        return other.uuid == this.uuid
    }

}
