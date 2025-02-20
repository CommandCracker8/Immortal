package dev.emortal.immortal.npc

import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.TaskGroup
import dev.emortal.immortal.util.sendServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.*
import net.minestom.server.network.packet.server.play.*
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.PacketUtils
import net.minestom.server.utils.time.TimeUnit
import world.cepi.kstom.Manager
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PacketNPC(val position: Pos, val hologramLines: List<Component>, val gameName: String, val playerSkin: PlayerSkin? = null, val entityType: EntityType = EntityType.PLAYER, val taskGroup: TaskGroup? = null) {

    private val viewers: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    private val taskMap = ConcurrentHashMap<UUID, MinestomRunnable>()

    companion object {
        val viewerMap = ConcurrentHashMap<UUID, CopyOnWriteArrayList<PacketNPC>>()

        val createTeamPacket = Manager.team.createBuilder("npcTeam")
            .nameTagVisibility(TeamsPacket.NameTagVisibility.NEVER)
            .build()
            .createTeamsCreationPacket()
    }

    val playerId = Entity.generateId()
    val prop = if (playerSkin == null) listOf() else listOf(
        PlayerInfoPacket.AddPlayer.Property(
            "textures",
            playerSkin.textures(),
            playerSkin.signature()
        )
    )
    val uuid = UUID.randomUUID()

    val playerInfo = PlayerInfoPacket(PlayerInfoPacket.Action.ADD_PLAYER, PlayerInfoPacket.AddPlayer(uuid, gameName, prop, GameMode.CREATIVE, 0, Component.empty(), null))
    val spawnPlayer = SpawnPlayerPacket(playerId, uuid, position)
    val teamPacket = TeamsPacket("npcTeam", TeamsPacket.AddEntitiesToTeamAction(listOf(gameName)))
    val metaPacket = EntityMetaDataPacket(playerId, mapOf(17 to Metadata.Byte(127 /*All layers enabled*/)))
    val removeFromList = PlayerInfoPacket(PlayerInfoPacket.Action.REMOVE_PLAYER, PlayerInfoPacket.RemovePlayer(uuid))

    fun addViewer(viewer: Player) {
        viewers.add(viewer)
        if (!viewerMap.containsKey(viewer.uuid)) viewerMap[viewer.uuid] = CopyOnWriteArrayList()
        viewerMap[viewer.uuid]?.add(this)


        if (entityType == EntityType.PLAYER) {
            viewer.sendPacket(playerInfo)
            viewer.sendPacket(spawnPlayer)
            viewer.sendPacket(metaPacket)
            viewer.sendPacket(createTeamPacket)
            viewer.sendPacket(teamPacket)

            viewer.scheduler().buildTask {
                viewer.sendPacket(removeFromList)
            }.delay(Duration.ofSeconds(3)).schedule()
        } else {
            val entitySpawn = SpawnEntityPacket(playerId, uuid, entityType.id(), position, position.yaw, 0, 0, 0, 0)

            viewer.sendPacket(entitySpawn)
        }

        taskMap[viewer.uuid] = object : MinestomRunnable(delay = TaskSchedule.duration(3, TimeUnit.SECOND), repeat = TaskSchedule.tick(3), taskGroup = taskGroup) {
            override fun run() {
                val lookFromPos = position.add(0.0, entityType.height(), 0.0)
                val lookToPos = viewer.position.add(0.0, if (viewer.isSneaking) 1.5 else 1.8, 0.0)

                if (lookFromPos.distanceSquared(lookToPos) > 10*10) return
                val pos = lookFromPos.withDirection(lookToPos.sub(lookFromPos))

                val lookPacket = EntityRotationPacket(playerId, pos.yaw, pos.pitch, true)
                val headLook = EntityHeadLookPacket(playerId, pos.yaw)
                viewer.sendPacket(lookPacket)
                viewer.sendPacket(headLook)
            }
        }

        //npcIdMap[playerId] = this
    }

    fun removeViewer(viewer: Player) {
        viewers.remove(viewer)
        viewerMap[viewer.uuid]?.remove(this)

        viewer.sendPackets(DestroyEntitiesPacket(playerId))
        taskMap[viewer.uuid]?.cancel()
        taskMap.remove(viewer.uuid)
    }

    fun destroy() {
        PacketUtils.sendGroupedPacket(viewers, DestroyEntitiesPacket(playerId))

        viewers.forEach {
            viewerMap[it.uuid]?.remove(this)
        }
        taskMap.values.forEach {
            it.cancel()
        }
        viewers.clear()
        taskMap.clear()
    }

    fun onClick(clicker: Player) = runBlocking {
        launch {
            clicker.sendServer(gameName)
        }
    }

}