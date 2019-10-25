/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.core.script.*
import org.jetbrains.kotlin.idea.core.script.configuration.cache.*
import org.jetbrains.kotlin.idea.core.script.configuration.loader.DefaultScriptConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptConfigurationLoadingContext
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptOutsiderFileConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.utils.BackgroundExecutor
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptsListener
import org.jetbrains.kotlin.idea.core.script.settings.KotlinScriptingSettings
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationResult
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.scripting.resolve.ScriptReportSink
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.valueOrNull

/**
 * Standard implementation of scripts configuration loading and caching
 * (we have plans for separate implementation for Gradle scripts).
 *
 * ## Loading initiation
 *
 * [getConfiguration] will be called when we need to show or analyze some script file.
 *
 * As described in [AbstractScriptConfigurationManager], configuration may be loaded from [cache]
 * or [reloadConfigurationInTransaction] will be called on [cache] miss.
 *
 * There are 2 tiers [ScriptConfigurationCompositeCache] [cache]: memory and FS.
 * Please see [ScriptConfigurationCompositeCache] for more details.
 *
 * [listener] will initiate scripts configuration reloading:
 *  - configuration will be reloaded after editor activation, even it is already up-to-date
 *    this is required for Gradle scripts, since it's classpath may depend on other files (`.properties` for example)
 *  - after each typing [ensureUpToDate] will be called
 *
 * Also, [ensureUpToDate] may be called from [UnusedSymbolInspection] to ensure that configuration of all scripts
 * containing some symbol are up-to-date. Note: it makes sence only in case of "auto apply" mode and sync loader.
 *
 * ## Loading
 *
 * When requested, configuration will be loaded using [loader]. [loader] may work synchronously or asynchronously.
 *
 * Synchronous [loader] will be called just immediately. Despite this, its result may not be applied immediately,
 * see next section for details.
 *
 * Asynchronous [loader] will be called in background thread (by [BackgroundExecutor]).
 *
 * ## Applying
 *
 * By default loaded configuration will *not* be applied immediately. Instead, we show in editor notification
 * that suggests user to apply changed configuration. This was done to avoid sporadically starting indexing of new roots,
 * which may happens regularly for large Gradle projects.
 *
 * Notification will be displayed when configuration is going to be updated. First configuration will be loaded
 * without notification.
 *
 * This behavior may be disabled by enabling "auto reload" in project settings.
 * When enabled, all loaded configurations will be applied immediately, without any notification.
 *
 * ## Concurrency
 *
 * Each files may be in on of this state:
 * - scriptDefinition is not ready
 * - not loaded
 * - up-to-date
 * - invalid, in queue (in [BackgroundExecutor] queue)
 * - invalid, loading
 * - invalid, waiting for apply
 *
 * [reloadConfigurationInTransaction] guard this states. See it's docs for more details.
 */
internal class DefaultScriptConfigurationManager(project: Project) :
    AbstractScriptConfigurationManager(project) {
    private val backgroundExecutor = BackgroundExecutor(project, rootsIndexer)
    private val listener = ScriptsListener(project, this)

    /**
     * Loaded but not applied result.
     * Weakness required since it is hard to track editor and notification hiding.
     */
    private val notApplied = WeakHashMap<VirtualFile, CachedConfiguration>()
    private val saveLock = ReentrantLock()

    private val loaders = listOf(
        ScriptOutsiderFileConfigurationLoader(project),
        ScriptConfigurationFileAttributeCache(project),
        DefaultScriptConfigurationLoader(project)
    )

    private val inputStampCalculators = listOf(
        DefaultScriptConfigurationInputsStampCalculator(project),
        GradleKotlinScriptConfigurationInputsStampCalculator(project)
    )

    override fun createCache(): ScriptConfigurationCache {
        return object : ScriptConfigurationMemoryCache(project) {
            override fun getInputs(file: VirtualFile): Any {
                inputStampCalculators.forEach {
                    val result = it.getInputsStamp(file)
                    if (result != null) return result
                }
                return Any()
            }

            override fun markOutOfDate(file: VirtualFile) {
                super.markOutOfDate(file)

                synchronized(notApplied) {
                    notApplied.remove(file)
                }
            }
        }
    }

    /**
     * Will be called on [cache] miss to initiate loading of [file]'s script configuration.
     *
     * ## Concurrency
     *
     * Each files may be in on of the states described below:
     * - scriptDefinition is not ready. `ScriptDefinitionsManager.getInstance(project).isReady() == false`.
     * [clearConfigurationCachesAndRehighlight] will be called when [ScriptDefinitionsManager] will be ready
     * which will call cause [reloadConfigurationInTransaction] for opened editors.
     * - unknown. When [isFirstLoad] true (`cache[file] == null`).
     * - up-to-date. `cache[file]?.upToDate == true`.
     * - invalid, in queue. `cache[file]?.upToDate == false && file in backgroundExecutor`.
     * - invalid, loading. `cache[file]?.upToDate == false && file !in backgroundExecutor`.
     * - invalid, waiting for apply. `cache[file]?.upToDate == false && file !in backgroundExecutor` and has notification panel?
     *
     * Async:
     * - up-to-date:
     *   [reloadConfigurationInTransaction] will not be called.
     * - `unknown` and `invalid, in queue`:
     *   Concurrent async loading will be guarded by ensureSchedule
     *   (only one task per file will be scheduled at same time)
     * - `invalid`:
     *   Loading should be rescheduled, since the work already started for old input.
     *   This will work, because file will be removed from backgroundExecutor.
     *   - `loading`: Scheduled loading for unchanged file will be noop thanks to isUpToDate check
     *   - `not applied`: Scheduled loading for unchanged file with loaded but not applied
     *      configuration will be also noop thanks check in [notApplied] map.
     *
     * Sync:
     * - up-to-date:
     *   [reloadConfigurationInTransaction] will not be called.
     * - all other states, i.e: `unknown`, `invalid, in queue`, `invalid, loading` and `invalid, ready for apply`:
     *   everything will be computed just in place, possible concurrently.
     *   [saveConfiguration] calls will be serialized by the [saveLock]
     */
    override fun reloadConfigurationInTransaction(
        file: KtFile,
        isFirstLoad: Boolean,
        loadEvenWillNotBeApplied: Boolean,
        forceSync: Boolean
    ) {
        val virtualFile = file.originalFile.virtualFile ?: return

        val autoReloadEnabled = KotlinScriptingSettings.getInstance(project).isAutoReloadEnabled
        val shouldLoad = isFirstLoad || loadEvenWillNotBeApplied || autoReloadEnabled
        if (!shouldLoad) return

        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return
        val scriptDefinition = file.findScriptDefinition() ?: return

        val (async, sync) = loaders.partition { it.isAsync(scriptDefinition) }

        sync.forEach {
            if (it.loadDependencies(isFirstLoad, virtualFile, scriptDefinition,)) {
                return
            }
        }

        if (!forceSync) {
            backgroundExecutor.ensureScheduled(virtualFile) {
                // don't start loading if nothing was changed
                // (in case we checking for up-to-date and loading concurrently)
                val cached = getCachedConfiguration(virtualFile)
                if (cached?.isUpToDate != true) {
                    val prevNotApplied = synchronized(notApplied) { notApplied[virtualFile] }
                    if (prevNotApplied?.isUpToDate == true) {
                        // reuse loaded but not applied result
                        // (in case we checking for up-to-date and waiting notification answer concurrently)
                        loadingContext.saveConfiguration(virtualFile, prevNotApplied.configuration.asSuccess(), false)
                    } else {
                        synchronized(notApplied) {
                            notApplied.remove(virtualFile)
                        }

                        async.first {
                            it.loadDependencies(cached == null, virtualFile, scriptDefinition, loadingContext)
                        }
                    }
                }
            }
        } else {
            async.first {
                it.loadDependencies(isFirstLoad, virtualFile, scriptDefinition,)
            }
        }
    }

    /**
     * Save configurations into cache.
     * Start indexing for new class/source roots.
     * Re-highlight opened scripts with changed configuration.
     */
    override fun saveCompilationConfigurationAfterImport(files: List<Pair<VirtualFile, ScriptCompilationConfigurationResult>>) {
        rootsIndexer.transaction {
            for ((file, result) in files) {
                loadingContext.saveConfiguration(file, result, skipNotification = true)
            }
        }
    }

    private val loadingContext = object : ScriptConfigurationLoadingContext {
        override fun getCachedConfiguration(file: VirtualFile): CachedConfiguration? =
            this@DefaultScriptConfigurationManager.getCachedConfiguration(file)

        /**
         * Save [newResult] for [file] into caches and update highlighting.
         * Should be called inside `rootsManager.transaction { ... }`.
         *
         * @param skipNotification forces loading new configuration even if auto reload is disabled.
         *
         * @sample ScriptConfigurationManager.getConfiguration
         */
        override fun saveConfiguration(
            file: VirtualFile,
            newResult: ScriptCompilationConfigurationResult,
            skipNotification: Boolean
        ) {
            saveLock.withLock {
                debug(file) { "configuration received = $newResult" }

                saveReports(file, newResult.reports)

                val newConfiguration = newResult.valueOrNull()
                if (newConfiguration != null) {
                    val oldConfiguration = getCachedConfiguration(file)?.configuration
                    if (oldConfiguration == newConfiguration) {
                        file.removeScriptDependenciesNotificationPanel(project)
                    } else {
                        val autoReload = skipNotification
                                || oldConfiguration == null
                                || KotlinScriptingSettings.getInstance(project).isAutoReloadEnabled
                                || ApplicationManager.getApplication().isUnitTestMode

                        if (autoReload) {
                            if (oldConfiguration != null) {
                                file.removeScriptDependenciesNotificationPanel(project)
                            }
                            saveChangedConfiguration(file, newConfiguration)
                        } else {
                            debug(file) {
                                "configuration changed, notification is shown: old = $oldConfiguration, new = $newConfiguration"
                            }
                            synchronized(notApplied) {
                                notApplied[file] = CachedConfiguration(cache, file, newConfiguration)
                            }
                            file.addScriptDependenciesNotificationPanel(
                                newConfiguration, project,
                                onClick = {
                                    file.removeScriptDependenciesNotificationPanel(project)
                                    rootsIndexer.transaction {
                                        saveChangedConfiguration(file, it)
                                    }
                                },
                                onHide = {
                                    synchronized(notApplied) {
                                        notApplied.remove(file)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun saveChangedConfiguration(file: VirtualFile, newConfiguration: ScriptCompilationConfigurationWrapper?) {
        super.saveChangedConfiguration(file, newConfiguration)

        synchronized(notApplied) {
            notApplied.remove(file)
        }
    }

    private fun saveReports(
        file: VirtualFile,
        newReports: List<ScriptDiagnostic>
    ) {
        val oldReports = IdeScriptReportSink.getReports(file)
        if (oldReports != newReports) {
            debug(file) { "new script reports = $newReports" }

            ServiceManager.getService(project, ScriptReportSink::class.java).attachReports(file, newReports)

            GlobalScope.launch(EDT(project)) {
                if (project.isDisposed) return@launch

                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }
}