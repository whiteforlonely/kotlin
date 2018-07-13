/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractNamedData
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.gradle.KotlinPlatform
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

class KotlinSourceSetData(
    id: String,
    externalName: String,
    internalName: String,
    moduleFileDirectoryPath: String,
    externalConfigPath: String
) : GradleSourceSetData(id, externalName, internalName, moduleFileDirectoryPath, externalConfigPath) {
    var platform: KotlinPlatform = KotlinPlatform.COMMON
    var defaultCompilerArguments: CommonCompilerArguments? = null
    var compilerArguments: CommonCompilerArguments? = null
    var dependencyClasspath: List<String> = emptyList()
    var isTestModule: Boolean = false
    var sourceSetIds: Collection<String> = emptyList()
}

class KotlinTargetData(name: String) : AbstractNamedData(GradleConstants.SYSTEM_ID, name) {
    var moduleIds: Set<String> = emptySet()
    var archiveFile: File? = null

    companion object {
        val KEY = Key.create(KotlinTargetData::class.java, ProjectKeys.MODULE.processingWeight + 1)
    }
}