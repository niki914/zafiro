package com.tricreta.zafiro.autonomy

import android.app.Application
import android.content.Context
import com.niki914.zafiro.api.AgentDelegator
import com.niki914.zafiro.service.installService
import com.tricreta.zafiro.autonomy.data.AutonomyDatabase
import com.tricreta.zafiro.autonomy.data.AutonomyRepository
import com.tricreta.zafiro.autonomy.run.DelegateRunner
import com.tricreta.zafiro.autonomy.run.RunExecutor
import com.tricreta.zafiro.autonomy.schedule.AlarmProjection
import com.tricreta.zafiro.autonomy.schedule.AutonomyScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide wiring for the autonomy layer. A plain lazy singleton (not the upstream ServiceRegistry) because receivers
 * and the foreground service must be able to build it in a cold process without depending on install order.
 */
class AutonomyGraph private constructor(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AutonomyDatabase.create(appContext)
    val repo = AutonomyRepository(db)
    val notifier = AutonomyNotifier(appContext)
    val alarm = AlarmProjection(appContext)
    val scheduler = AutonomyScheduler(appContext, repo, alarm)
    val executor = RunExecutor(repo, notifier)

    companion object {
        @Volatile
        private var instance: AutonomyGraph? = null

        /** For code that runs after the app started (ViewModels). Fails loudly if the installer did not run. */
        fun require(): AutonomyGraph = checkNotNull(instance) { "AutonomyGraph is not installed" }

        fun get(context: Context): AutonomyGraph =
            instance ?: synchronized(this) { instance ?: AutonomyGraph(context).also { instance = it } }
    }
}

/** The single line upstream's composition root calls. */
object AutonomyInstaller {
    fun install(application: Application) {
        val graph = AutonomyGraph.get(application)
        installService<AgentDelegator>(DelegateRunner(graph.repo, graph.scope))
        // Re-arm the alarm and record anything missed whenever the process starts.
        graph.scope.launch { graph.scheduler.reconcile("app_start") }
    }
}
