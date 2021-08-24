package emortal.immortal

import emortal.immortal.commands.PlayCommand
import emortal.immortal.game.GameManager.game
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.extensions.Extension
import world.cepi.kstom.command.register
import world.cepi.kstom.event.listenOnly

class ImmortalExtension : Extension() {

    override fun initialize() {
        eventNode.listenOnly<PlayerDisconnectEvent> {
            player.game?.removePlayer(player)
        }
        eventNode.listenOnly<PlayerSpawnEvent> {
            if (player.game != null) {
                if (player.game!!.instance != spawnInstance) {
                    player.game!!.removePlayer(player)
                }
            }
        }

        PlayCommand.register()

        logger.info("[ImmortalExtension] has been enabled!")
    }

    override fun terminate() {
        logger.info("[ImmortalExtension] has been disabled!")
    }

}