/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration.cache

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.util.application.runReadAction

interface ScriptConfigurationInputsStampCalculator {
    fun getInputsStamp(file: VirtualFile): Any?
}

class DefaultScriptConfigurationInputsStampCalculator(val project: Project) : ScriptConfigurationInputsStampCalculator {
    override fun getInputsStamp(file: VirtualFile): Any = runReadAction {
        PsiManager.getInstance(project).findFile(file)?.modificationStamp ?: Any()
    }
}