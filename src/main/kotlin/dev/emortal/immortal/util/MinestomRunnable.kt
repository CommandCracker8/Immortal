package dev.emortal.immortal.util

import net.minestom.server.timer.ExecutionType
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import world.cepi.kstom.Manager
import java.time.Duration

/**
 * A utility to make it easier to build tasks
 *
 * @author emortal
 * @author DasLixou
 */
abstract class MinestomRunnable : Runnable {

    var currentIteration: Long = 0L
    val iterations: Long

    private var task: Task? = null
    var delaySchedule: TaskSchedule = TaskSchedule.immediate()
        set(value) {
            field = value
            schedule()
        }
    var repeatSchedule: TaskSchedule = TaskSchedule.stop()
        set(value) {
            field = value
            schedule()
        }
    private var executionType: ExecutionType = ExecutionType.SYNC
    private var taskGroup: TaskGroup? = null

    constructor(delay: Duration = Duration.ZERO, repeat: Duration = Duration.ZERO, executionType: ExecutionType = ExecutionType.SYNC, iterations: Long = -1L, taskGroup: TaskGroup? = null) {
        this.iterations = iterations
        this.taskGroup = taskGroup
        this.delaySchedule = if (delay != Duration.ZERO) TaskSchedule.duration(delay) else TaskSchedule.immediate()
        this.repeatSchedule = if (repeat != Duration.ZERO) TaskSchedule.duration(repeat) else TaskSchedule.stop()
        this.executionType = executionType

        schedule()
    }

    constructor(delay: TaskSchedule = TaskSchedule.immediate(), repeat: TaskSchedule = TaskSchedule.stop(), executionType: ExecutionType = ExecutionType.SYNC, iterations: Long = -1L, taskGroup: TaskGroup? = null) {
        this.iterations = iterations
        this.taskGroup = taskGroup
        this.delaySchedule = delay
        this.repeatSchedule = repeat
        this.executionType = executionType

        schedule()
    }

    private fun schedule(): Task {
        val t = Manager.scheduler.buildTask {
            if (iterations != -1L && currentIteration >= iterations) {
                cancel()
                cancelled()
                return@buildTask
            }

            try {
                this.run()
            } catch (e: Throwable) {
                Manager.exception.handleException(e)
            }

            currentIteration++
        }
            .delay(delaySchedule)
            .repeat(repeatSchedule)
            .executionType(executionType)
            .schedule()

        if (this.task != null) {
            this.task?.cancel()
            taskGroup?.removeTask(this.task)
        }

        taskGroup?.addTask(t)

        this.task = t
        return t
    }

    fun cancel() = task?.cancel()

    /**
        Called when iterations run out
     */
    open fun cancelled() {}
}