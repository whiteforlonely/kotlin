/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.libraries.Library
import org.jetbrains.kotlin.config.CoroutineSupport
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.TargetPlatformKind
import org.jetbrains.kotlin.gradle.KotlinPlatform
import org.jetbrains.kotlin.idea.facet.applyCompilerArgumentsToFacet
import org.jetbrains.kotlin.idea.facet.configureFacet
import org.jetbrains.kotlin.idea.facet.getOrCreateFacet
import org.jetbrains.kotlin.idea.facet.noVersionAutoAdvance
import org.jetbrains.kotlin.idea.framework.CommonLibraryKind
import org.jetbrains.kotlin.idea.framework.JSLibraryKind
import org.jetbrains.kotlin.idea.framework.effectiveKind
import org.jetbrains.kotlin.idea.inspections.gradle.findAll
import org.jetbrains.kotlin.idea.inspections.gradle.findKotlinPluginVersion
import org.jetbrains.kotlin.idea.roots.migrateNonJvmSourceFolders
import org.jetbrains.plugins.gradle.model.data.BuildScriptClasspathData
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData

class KotlinSourceSetDataService : AbstractProjectDataService<GradleSourceSetData, Void>() {
    override fun getTargetDataKey() = GradleSourceSetData.KEY

    override fun postProcess(
        toImport: MutableCollection<DataNode<GradleSourceSetData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider
    ) {
        for (nodeToImport in toImport) {
            val mainModuleData = ExternalSystemApiUtil.findParent(
                nodeToImport,
                ProjectKeys.MODULE
            ) ?: continue
            val sourceSetData = nodeToImport.data as? KotlinSourceSetData ?: continue
            val ideModule = modelsProvider.findIdeModule(sourceSetData) ?: continue
            val platform = sourceSetData.platform
            val rootModel = modelsProvider.getModifiableRootModel(ideModule)

            dropWrongLibraries(rootModel, platform, project)

            if (platform != KotlinPlatform.JVM) {
                migrateNonJvmSourceFolders(rootModel)
            }

            configureFacet(sourceSetData, mainModuleData, ideModule, modelsProvider)
        }
    }

    private fun configureFacet(
        sourceSetData: KotlinSourceSetData,
        mainModuleNode: DataNode<ModuleData>,
        ideModule: Module,
        modelsProvider: IdeModifiableModelsProvider
    ) {
        val compilerVersion = mainModuleNode
            .findAll(BuildScriptClasspathData.KEY)
            .firstOrNull()
            ?.data
            ?.let { findKotlinPluginVersion(it) } ?: return
        val platformKind = when (sourceSetData.platform) {
            KotlinPlatform.JVM -> TargetPlatformKind.Jvm[JvmTarget.fromString(
                sourceSetData.targetCompatibility ?: ""
            ) ?: JvmTarget.DEFAULT]
            KotlinPlatform.JS -> TargetPlatformKind.JavaScript
            KotlinPlatform.COMMON -> TargetPlatformKind.Common
        }
        val coroutinesProperty = CoroutineSupport.byCompilerArgument(
            mainModuleNode.coroutines ?: findKotlinCoroutinesProperty(ideModule.project)
        )

        val kotlinFacet = ideModule.getOrCreateFacet(modelsProvider, false)
        kotlinFacet.configureFacet(compilerVersion, coroutinesProperty, platformKind, modelsProvider)

        val compilerArguments = sourceSetData.compilerArguments
        val defaultCompilerArguments = sourceSetData.defaultCompilerArguments
        if (compilerArguments != null) {
            applyCompilerArgumentsToFacet(
                compilerArguments,
                defaultCompilerArguments,
                kotlinFacet,
                modelsProvider
            )
        }

        adjustClasspath(kotlinFacet, sourceSetData.dependencyClasspath)

        kotlinFacet.noVersionAutoAdvance()

        with(kotlinFacet.configuration.settings) {
            sourceSetNames = sourceSetData.sourceSetIds.mapNotNull { sourceSetId ->
                val node = mainModuleNode.findChildModuleById(sourceSetId) ?: return@mapNotNull null
                val data = node.data as? ModuleData ?: return@mapNotNull null
                modelsProvider.findIdeModule(data)?.name
            }

            if (sourceSetData.isTestModule) {
                testOutputPath = sourceSetData.getCompileOutputPath(ExternalSystemSourceType.TEST)
                productionOutputPath = null
            } else {
                productionOutputPath = sourceSetData.getCompileOutputPath(ExternalSystemSourceType.SOURCE)
                testOutputPath = null
            }
        }
    }

    private fun dropWrongLibraries(
        rootModel: ModifiableRootModel,
        platform: KotlinPlatform,
        project: Project
    ) {
        rootModel.orderEntries().librariesOnly().forEach { orderEntry ->
            val library = (orderEntry as? LibraryOrderEntry)?.library
            if (library != null && !library.matchesPlatform(platform, project)) {
                rootModel.removeOrderEntry(orderEntry)
            }
            true
        }
    }

    private fun Library.matchesPlatform(platform: KotlinPlatform, project: Project): Boolean {
        val kind = (this as? LibraryEx)?.effectiveKind(project)
        return when (kind) {
            CommonLibraryKind -> true
            JSLibraryKind -> platform == KotlinPlatform.JS
            else -> platform == KotlinPlatform.JVM
        }
    }
}