/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration.loader

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.configuration.cache.CachedConfiguration
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationResult

interface ScriptConfigurationLoadingContext {
    fun getCachedConfiguration(file: VirtualFile): CachedConfiguration?

    fun saveConfiguration(
        file: VirtualFile,
        newResult: ScriptCompilationConfigurationResult,
        skipNotification: Boolean
    )
}