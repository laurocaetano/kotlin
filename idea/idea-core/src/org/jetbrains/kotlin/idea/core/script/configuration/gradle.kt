/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.idea.core.script.configuration.cache.ScriptConfigurationInputsStampCalculator
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType

class GradleKotlinScriptConfigurationInputsStampCalculator(val project: Project) : ScriptConfigurationInputsStampCalculator {
    override fun getInputsStamp(file: VirtualFile): Any? {
        if (!isGradleKotlinScript(file)) return null

        return runReadAction {
            val ktFile = PsiManager.getInstance(project).findFile(file) as? KtFile

            if (ktFile != null) {
                val result = StringBuilder()
                ktFile.script?.blockExpression
                    ?.getChildrenOfType<KtScriptInitializer>()
                    ?.forEach {
                        val call = it.children.singleOrNull() as? KtCallExpression
                        val callRef = call?.firstChild?.text
                        if (callRef == "buildscript" || callRef == "plugins") {
                            result.append(callRef)
                            val lambda = call.lambdaArguments.singleOrNull()
                            lambda?.accept(object : PsiRecursiveElementVisitor(false) {
                                override fun visitElement(element: PsiElement) {
                                    super.visitElement(element)
                                    when (element) {
                                        is PsiWhiteSpace -> if (element.text.contains("\n")) result.append("\n")
                                        is LeafPsiElement -> result.append(element.text)
                                    }
                                }
                            })
                            result.append("\n")
                        }
                    }

                GradleKotlinScriptConfigurationInputs(
                    result.toString(),
                    ServiceManager.getService(project, GradleRelatedFilesListener::class.java)?.lastModified
                        ?: Long.MIN_VALUE
                )
            } else null
        }
    }
}

data class GradleKotlinScriptConfigurationInputs(
    val buildScriptAndPluginsSections: String,
    val relatedFilesModificationsTimestamp: Long
)

fun isGradleKotlinScript(virtualFile: VirtualFile) = virtualFile.name.endsWith(".gradle.kts")

class GradleRelatedFilesListener {
    @Volatile
    var lastModified: Long = Long.MIN_VALUE
}