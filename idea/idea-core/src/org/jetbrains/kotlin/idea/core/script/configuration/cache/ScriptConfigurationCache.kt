/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration.cache

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper

data class CachedConfiguration(
    val cache: ScriptConfigurationCache,
    val file: VirtualFile,
    val configuration: ScriptCompilationConfigurationWrapper,
    val inputs: Any? = cache.getInputs(file)
) {
    val isUpToDate
        get() = cache.getInputs(file) == inputs
}

interface ScriptConfigurationCache {
    fun getInputs(file: VirtualFile): Any

    operator fun get(file: VirtualFile): CachedConfiguration?
    operator fun set(file: VirtualFile, configuration: ScriptCompilationConfigurationWrapper)
    fun markOutOfDate(file: VirtualFile)

    fun all(): Collection<CachedConfiguration>
}