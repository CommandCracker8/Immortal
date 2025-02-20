package dev.emortal.immortal.commands

import dev.emortal.immortal.util.armify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.minestom.server.command.builder.Command
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.instance.SharedInstance
import net.minestom.server.monitoring.TickMonitor
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference
import javax.management.Attribute
import javax.management.AttributeList
import javax.management.ObjectName
import kotlin.math.floor


internal object StatsCommand : Command("tps") {

    private val LAST_TICK = AtomicReference<TickMonitor>()

    init {
        Manager.globalEvent.listenOnly<ServerTickMonitorEvent> {
            LAST_TICK.set(tickMonitor)
        }
    }

    init {

        setDefaultExecutor { sender, _ ->
            val totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024
            val freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024
            val ramUsage = totalMem - freeMem

            val mbs = ManagementFactory.getPlatformMBeanServer()
            val name = ObjectName.getInstance("java.lang:type=OperatingSystem")
            val list: AttributeList = mbs.getAttributes(name, arrayOf("ProcessCpuLoad"))
            val att = list.firstOrNull() as? Attribute
            val value = (att?.value as? Double) ?: 0.0
            val cpuPercent = floor(value * 100_00) / 100.0

            val monitor = LAST_TICK.get()
            val tickMs = monitor.tickTime
            val tps = floor(1000 / tickMs).toInt().coerceAtMost(20)

            val onlinePlayers = Manager.connection.onlinePlayers.size
            val entities = Manager.instance.instances.sumOf { it.entities.size } - onlinePlayers
            val instances = Manager.instance.instances
            val sharedInstances = instances.count { it is SharedInstance }
            val chunks = Manager.instance.instances.sumOf { it.chunks.size }

            sender.sendMessage(
                Component.text()
                    .append(Component.text("RAM Usage: ", NamedTextColor.GRAY))
                    .append(Component.text("${ramUsage}MB / ${totalMem}MB", NamedTextColor.GRAY))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(Component.text("${floor((ramUsage.toDouble() / totalMem.toDouble()) * 100.0) / 100}}", NamedTextColor.GREEN))

                    .append(Component.text("\nCPU Usage: ", NamedTextColor.GRAY))
                    .append(Component.text("${if (cpuPercent < 0) "..." else cpuPercent}%", NamedTextColor.GREEN))

                    .append(Component.text("\nTPS: ", NamedTextColor.GRAY))
                    .append(Component.text(tps, NamedTextColor.GREEN))

                    .append(Component.text("(", NamedTextColor.GRAY))
                    .append(Component.text("${floor(tickMs * 100.0) / 100}ms", TextColor.lerp(tickMs.toFloat() / 50f, NamedTextColor.GREEN, NamedTextColor.RED)))
                    .append(Component.text(")\n", NamedTextColor.GRAY))

                    .append(Component.text("\nPlayers: ", NamedTextColor.GRAY))
                    .append(Component.text(onlinePlayers, NamedTextColor.GOLD))

                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))

                    .append(Component.text("Entities: ", NamedTextColor.GRAY))
                    .append(Component.text(entities, NamedTextColor.GOLD))

                    .append(Component.text("\nInstances: ", NamedTextColor.GRAY))
                    .append(Component.text(instances.size - sharedInstances, NamedTextColor.GOLD))
                    .append(Component.text(" (Shared: ${sharedInstances})", NamedTextColor.GRAY))

                    .append(Component.text("\nChunks: ", NamedTextColor.GRAY))
                    .append(Component.text(chunks, NamedTextColor.GOLD))

                    .armify(40)
            )
        }

    }

}