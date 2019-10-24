/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.core.script.*
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager.Companion.toVfsRoots
import org.jetbrains.kotlin.idea.core.script.configuration.cache.CachedConfiguration
import org.jetbrains.kotlin.idea.core.script.configuration.cache.ScriptConfigurationCache
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsCache
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsIndexer
import org.jetbrains.kotlin.idea.core.script.configuration.utils.getKtFile
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.isNonScript
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationResult
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.script.experimental.api.valueOrNull

/**
 * Abstract [ScriptConfigurationManager] implementation based on [cache] and [reloadConfigurationInTransaction].
 * Among this two methods concrete implementation should provide script changes listening
 * (by calling [ensureUpToDate] and [forceReload] on some event).
 *
 * Basically all requests routed to [cache]. If there is no entry in [cache] or it is considered out-of-date,
 * then [reloadConfigurationInTransaction] will be called, which, in turn, should call [saveChangedConfiguration]
 * immediately or in some future  (e.g. after user will click "apply context" or/and configuration will
 * be calculated by some background thread).
 *
 * [classpathRoots] will be calculated lazily based on [cache]d configurations.
 * Every change in [cache] will invalidate [classpathRoots] cache.
 * Some internal state changes in [cache] may also invalidate [classpathRoots] by calling [clearClassRootsCaches]
 * (for example, when cache loaded from FS to memory)
 */
internal abstract class AbstractScriptConfigurationManager(
    protected val project: Project
) : ScriptConfigurationManager {
    protected val rootsIndexer = ScriptClassRootsIndexer(project)

    @Suppress("LeakingThis")
    protected val cache: ScriptConfigurationCache = createCache()

    protected abstract fun createCache(): ScriptConfigurationCache

    /**
     * Will be called on [cache] miss or when [file] is changed.
     * Implementation should initiate loading of [file]'s script configuration and call [saveChangedConfiguration]
     * immediately or in some future
     * (e.g. after user will click "apply context" or/and configuration will be calculated by some background thread).
     *
     * @param isFirstLoad may be set explicitly for optimization reasons (to avoid expensive fs cache access)
     * @param loadEvenWillNotBeApplied may should be set to false only on requests from particular editor, when
     * user can see potential notification and accept new configuration. In other cases this should `false` since
     * loaded configuration will be just leaved in hidden user notification cannot be used in any way, so there is
     * no reason to load it
     * @param forceSync should be used in tests only
     */
    protected abstract fun reloadConfigurationInTransaction(
        file: KtFile,
        isFirstLoad: Boolean = getCachedConfiguration(file.originalFile.virtualFile) == null,
        loadEvenWillNotBeApplied: Boolean = false,
        forceSync: Boolean = false
    )

    @Deprecated("Use getScriptClasspath(KtFile) instead")
    override fun getScriptClasspath(file: VirtualFile): List<VirtualFile> {
        val ktFile = project.getKtFile(file) ?: return emptyList()
        return getScriptClasspath(ktFile)
    }

    override fun getScriptClasspath(file: KtFile): List<VirtualFile> =
        toVfsRoots(getConfiguration(file)?.dependenciesClassPath.orEmpty())

    fun getCachedConfiguration(file: VirtualFile?): CachedConfiguration? {
        if (file == null) return null
        return cache[file]
    }

    override fun hasConfiguration(file: KtFile): Boolean {
        return getCachedConfiguration(file.originalFile.virtualFile) != null
    }

    override fun getConfiguration(file: KtFile): ScriptCompilationConfigurationWrapper? {
        return getConfiguration(file.originalFile.virtualFile, file)
    }

    fun getConfiguration(
        virtualFile: VirtualFile,
        preloadedKtFile: KtFile? = null
    ): ScriptCompilationConfigurationWrapper? {
        val cached = getCachedConfiguration(virtualFile)
        if (cached != null) return cached.configuration

        val ktFile = project.getKtFile(virtualFile, preloadedKtFile) ?: return null
        rootsIndexer.transaction {
            reloadConfigurationInTransaction(ktFile, isFirstLoad = true)
        }

        return getCachedConfiguration(virtualFile)?.configuration
    }

    protected open fun isUpToDate(file: KtFile) =
        cache[file.virtualFile]?.isUpToDate == true

    override fun ensureUpToDate(files: List<KtFile>, loadEvenWillNotBeApplied: Boolean): Boolean {
        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return false

        var upToDate = true
        rootsIndexer.transaction {
            files.forEach { file ->
                val virtualFile = file.originalFile.virtualFile
                if (virtualFile != null) {
                    val state = cache[virtualFile]
                    if (state == null || !state.isUpToDate) {
                        upToDate = false
                        reloadConfigurationInTransaction(
                            file,
                            isFirstLoad = state == null,
                            loadEvenWillNotBeApplied = loadEvenWillNotBeApplied
                        )
                    }
                }
            }
        }

        return upToDate
    }

    internal fun forceReload(file: KtFile) {
        val virtualFile = file.originalFile.virtualFile ?: return
        cache.markOutOfDate(virtualFile)

        rootsIndexer.transaction {
            reloadConfigurationInTransaction(file, loadEvenWillNotBeApplied = true)
        }
    }

    @TestOnly
    internal fun updateScriptDependenciesSynchronously(file: PsiFile) {
        file.findScriptDefinition() ?: return

        assert(file is KtFile) {
            "PsiFile should be a KtFile, otherwise script dependencies cannot be loaded"
        }

        if (cache[file.virtualFile]?.isUpToDate == true) return

        rootsIndexer.transaction {
            reloadConfigurationInTransaction(file as KtFile, isFirstLoad = true, forceSync = true)
        }
    }

    protected open fun saveChangedConfiguration(
        file: VirtualFile,
        newConfiguration: ScriptCompilationConfigurationWrapper?
    ) {
        rootsIndexer.checkInTransaction()
        debug(file) { "configuration changed = $newConfiguration" }

        if (newConfiguration != null) {
            if (hasNotCachedRoots(newConfiguration)) {
                rootsIndexer.markNewRoot(file, newConfiguration)
            }

            this.cache[file] = newConfiguration

            clearClassRootsCaches()
        }

        updateHighlighting(listOf(file))
    }

    private fun hasNotCachedRoots(configuration: ScriptCompilationConfigurationWrapper): Boolean {
        return classpathRoots.hasNotCachedRoots(configuration)
    }

    override fun saveCompilationConfigurationAfterImport(files: List<Pair<VirtualFile, ScriptCompilationConfigurationResult>>) {
        rootsIndexer.transaction {
            for ((file, result) in files) {
                saveChangedConfiguration(file, result.valueOrNull())
            }
        }
    }

    /**
     * Clear configuration caches
     * Start re-highlighting for opened scripts
     */
    override fun clearConfigurationCachesAndRehighlight() {
        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()

        // todo: invalidate caches?

        if (project.isOpen) {
            val openedScripts = FileEditorManager.getInstance(project).openFiles.filterNot { it.isNonScript() }
            updateHighlighting(openedScripts)
        }
    }

    private fun updateHighlighting(files: List<VirtualFile>) {
        if (files.isEmpty()) return

        GlobalScope.launch(EDT(project)) {
            if (project.isDisposed) return@launch

            val openFiles = FileEditorManager.getInstance(project).openFiles
            val openScripts = files.filter { it.isValid && openFiles.contains(it) }

            openScripts.forEach {
                PsiManager.getInstance(project).findFile(it)?.let { psiFile ->
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                }
            }
        }
    }

    ///////////////////
    // ScriptRootsCache

    private val rootsLock = ReentrantLock()
    @Volatile
    private var _roots: ScriptClassRootsCache? = null
    private val classpathRoots: ScriptClassRootsCache
        get() {
            val value1 = _roots
            if (value1 != null) return value1

            rootsLock.withLock {
                val value2 = _roots
                if (value2 != null) return value2

                val value3 =
                    ScriptClassRootsCache(project, cache)
                _roots = value3
                return value3
            }
        }

    private fun clearClassRootsCaches() {
        debug { "class roots caches cleared" }

        rootsLock.withLock {
            _roots = null
        }

        val kotlinScriptDependenciesClassFinder =
            Extensions.getArea(project).getExtensionPoint(PsiElementFinder.EP_NAME).extensions
                .filterIsInstance<KotlinScriptDependenciesClassFinder>()
                .single()

        kotlinScriptDependenciesClassFinder.clearCache()

        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()
    }

    override fun getScriptSdk(file: VirtualFile): Sdk? = classpathRoots.getScriptSdk(file)

    override fun getFirstScriptsSdk(): Sdk? = classpathRoots.getFirstScriptsSdk()

    override fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope =
        classpathRoots.getScriptDependenciesClassFilesScope(file)

    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope = classpathRoots.allDependenciesClassFilesScope

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope = classpathRoots.allDependenciesSourcesScope

    override fun getAllScriptsDependenciesClassFiles(): List<VirtualFile> = classpathRoots.allDependenciesClassFiles

    override fun getAllScriptDependenciesSources(): List<VirtualFile> = classpathRoots.allDependenciesSources
}