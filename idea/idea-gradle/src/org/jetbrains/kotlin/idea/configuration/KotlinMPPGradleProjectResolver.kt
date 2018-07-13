/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PathUtilRt
import com.intellij.util.SmartList
import com.intellij.util.containers.MultiMap
import com.intellij.util.text.VersionComparatorUtil
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.gradle.*
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.plugins.gradle.model.*
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolver
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolver.CONFIGURATION_ARTIFACTS
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolver.MODULES_OUTPUTS
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil.buildDependencies
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil.getModuleId
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

var DataNode<GradleSourceSetData>.kotlinTargetDataNode: DataNode<KotlinTargetData>?
        by UserDataProperty(Key.create("KOTLIN_TARGET_DATA_NODE"))

class KotlinMPPGradleProjectResolver : AbstractProjectResolverExtension() {
    override fun getToolingExtensionsClasses(): Set<Class<out Any>> {
        return setOf(KotlinMPPGradleModelBuilder::class.java, Unit::class.java)
    }

    override fun getExtraProjectModelClasses(): Set<Class<out Any>> {
        return setOf(KotlinMPPGradleModel::class.java)
    }

    override fun populateProjectExtraModels(gradleProject: IdeaProject, ideProject: DataNode<ProjectData>) {
        super.populateProjectExtraModels(gradleProject, ideProject)
        ideProject.kotlinModulesToExternalSourceSets = LinkedHashMap()
    }

    override fun createModule(gradleModule: IdeaModule, projectDataNode: DataNode<ProjectData>): DataNode<ModuleData> {
        val mainModuleNode = super.createModule(gradleModule, projectDataNode)
        val mainModuleData = mainModuleNode.data
        val mainModuleConfigPath = mainModuleData.linkedExternalProjectPath
        val mainModuleFileDirectoryPath = mainModuleData.moduleFileDirectoryPath

        val externalProject = resolverCtx.getExtraProject(gradleModule, ExternalProject::class.java)
        val mppModel = resolverCtx.getExtraProject(gradleModule, KotlinMPPGradleModel::class.java)
        if (mppModel == null || externalProject == null) return mainModuleNode

        val jdkName = gradleModule.jdkNameIfAny

        var moduleGroup: Array<String>? = null
        if (!resolverCtx.isUseQualifiedModuleNames) {
            val gradlePath = gradleModule.gradleProject.path
            val isRootModule = gradlePath.isEmpty() || gradlePath == ":"
            moduleGroup = if (isRootModule) {
                arrayOf(mainModuleData.internalName)
            } else {
                gradlePath.split(":").drop(1).toTypedArray()
            }
            mainModuleData.ideModuleGroup = if (isRootModule) null else moduleGroup
        }

        val sourceSetMap = projectDataNode.getUserData(GradleProjectResolver.RESOLVED_SOURCE_SETS)!!

        val sourceSetToCompilationData = LinkedHashMap<KotlinSourceSet, MutableSet<KotlinSourceSetData>>()
        for (target in mppModel.targets) {
            val targetData = KotlinTargetData(target.name).also {
                it.archiveFile = target.jar.archiveFile
            }
            val targetDataNode = mainModuleNode.createChild<KotlinTargetData>(KotlinTargetData.KEY, targetData)

            val compilationIds = LinkedHashSet<String>()
            for (compilation in target.compilations) {
                val moduleId = getModuleId(gradleModule, compilation)
                if (sourceSetMap.containsKey(moduleId)) continue
                compilationIds += moduleId
                val moduleExternalName = getExternalModuleName(gradleModule, compilation)
                val moduleInternalName = getInternalModuleName(gradleModule, externalProject, compilation)
                val compilationData = KotlinSourceSetData(
                    moduleId, moduleExternalName, moduleInternalName, mainModuleFileDirectoryPath, mainModuleConfigPath
                ).also {
                    it.group = externalProject.group
                    it.version = externalProject.version

                    when (compilation.name) {
                        KotlinCompilation.MAIN_COMPILATION_NAME -> {
                            it.publication = ProjectId(externalProject.group, externalProject.name, externalProject.version)
                        }
                        KotlinCompilation.TEST_COMPILATION_NAME -> {
                            it.productionModuleId = getInternalModuleName(
                                gradleModule,
                                externalProject,
                                compilation,
                                KotlinCompilation.MAIN_COMPILATION_NAME
                            )
                        }
                    }

                    it.ideModuleGroup = moduleGroup
                    it.sdkName = jdkName
                    it.platform = compilation.platform
                    it.isTestModule = compilation.isTestModule
                    it.compilerArguments = createCompilerArguments(compilation.arguments.currentArguments, compilation.platform)
                    it.dependencyClasspath = compilation.dependencyClasspath
                    it.defaultCompilerArguments = createCompilerArguments(compilation.arguments.defaultArguments, compilation.platform)
                    if (compilation.platform == KotlinPlatform.JVM) {
                        it.targetCompatibility = (it.compilerArguments as? K2JVMCompilerArguments)?.jvmTarget
                    }
                    it.sourceSetIds = compilation.sourceSets
                        .asSequence()
                        .filter { it.fullName() != compilation.fullName() }
                        .map { getModuleId(gradleModule, it) }
                        .toList()
                }

                for (sourceSet in compilation.sourceSets) {
                    sourceSetToCompilationData.getOrPut(sourceSet) { LinkedHashSet() } += compilationData
                }

                val compilationDataNode = mainModuleNode.createChild(GradleSourceSetData.KEY, compilationData)
                compilationDataNode.kotlinTargetDataNode = targetDataNode
                sourceSetMap[moduleId] = Pair(compilationDataNode, createExternalSourceSet(compilation, compilationData))
            }

            targetData.moduleIds = compilationIds
        }

        for (sourceSet in mppModel.sourceSets) {
            val moduleId = getModuleId(gradleModule, sourceSet)
            if (sourceSetMap.containsKey(moduleId)) continue
            val moduleExternalName = getExternalModuleName(gradleModule, sourceSet)
            val moduleInternalName = getInternalModuleName(gradleModule, externalProject, sourceSet)

            val sourceSetData = KotlinSourceSetData(
                moduleId, moduleExternalName, moduleInternalName, mainModuleFileDirectoryPath, mainModuleConfigPath
            ).also {
                it.group = externalProject.group
                it.version = externalProject.version

                val name = sourceSet.name
                val baseName = name.removeSuffix("Test")
                if (baseName != name) {
                    it.productionModuleId = getInternalModuleName(
                        gradleModule,
                        externalProject,
                        sourceSet,
                        baseName + "Main"
                    )
                }

                it.ideModuleGroup = moduleGroup
                it.sdkName = jdkName
                it.platform = sourceSet.platform
                it.isTestModule = sourceSet.isTestModule
                it.targetCompatibility = sourceSetToCompilationData[sourceSet]
                    ?.mapNotNull { it.targetCompatibility }
                    ?.minWith(VersionComparatorUtil.COMPARATOR)
            }

            val sourceSetDataNode = mainModuleNode.createChild(GradleSourceSetData.KEY, sourceSetData)
            sourceSetMap[moduleId] = Pair(sourceSetDataNode, createExternalSourceSet(sourceSet, sourceSetData))
        }

        with(projectDataNode.data) {
            if (mainModuleData.linkedExternalProjectPath == linkedExternalProjectPath) {
                group = mainModuleData.group
                version = mainModuleData.version
            }
        }

        mainModuleNode.coroutines = mppModel.extraFeatures.coroutinesState

        return mainModuleNode
    }

    override fun populateModuleContentRoots(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        super.populateModuleContentRoots(gradleModule, ideModule)

        val mppModel = resolverCtx.getExtraProject(gradleModule, KotlinMPPGradleModel::class.java) ?: return
        processSourceSets(gradleModule, mppModel, ideModule) { dataNode, sourceSet ->
            createContentRootData(sourceSet.sourceDirs, sourceSet.sourceType, dataNode)
            createContentRootData(sourceSet.resourceDirs, sourceSet.resourceType, dataNode)
        }

        gradleModule.contentRoots?.forEach { gradleContentRoot ->
            val rootDirectory = gradleContentRoot.rootDirectory ?: return@forEach
            val ideContentRoot = ContentRootData(GradleConstants.SYSTEM_ID, rootDirectory.absolutePath)
            gradleContentRoot.excludeDirectories?.forEach { file ->
                ideContentRoot.storePath(ExternalSystemSourceType.EXCLUDED, file.absolutePath)
            }
            ideModule.createChild(ProjectKeys.CONTENT_ROOT, ideContentRoot)
        }
    }

    override fun populateModuleCompileOutputSettings(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        super.populateModuleCompileOutputSettings(gradleModule, ideModule)

        val mppModel = resolverCtx.getExtraProject(gradleModule, KotlinMPPGradleModel::class.java) ?: return
        val ideaOutDir = File(ideModule.data.linkedExternalProjectPath, "out")
        val projectDataNode = ideModule.getDataNode(ProjectKeys.PROJECT)!!
        val moduleOutputsMap = projectDataNode.getUserData(MODULES_OUTPUTS)!!
        val outputDirs = HashSet<String>()
        processCompilations(gradleModule, mppModel, ideModule) { dataNode, compilation ->
            var gradleOutputMap = dataNode.getUserData(GradleProjectResolver.GRADLE_OUTPUTS)
            if (gradleOutputMap == null) {
                gradleOutputMap = MultiMap.create()
                dataNode.putUserData(GradleProjectResolver.GRADLE_OUTPUTS, gradleOutputMap)
            }

            val moduleData = dataNode.data

            with(compilation.output) {
                effectiveClassesDir?.let {
                    moduleData.isInheritProjectCompileOutputPath = false
                    moduleData.setCompileOutputPath(compilation.sourceType, it.absolutePath)
                    for (gradleOutputDir in classesDirs) {
                        recordOutputDir(gradleOutputDir, it, compilation.sourceType, moduleData, moduleOutputsMap, gradleOutputMap)
                    }
                }
                resourcesDir?.let {
                    moduleData.setCompileOutputPath(compilation.resourceType, it.absolutePath)
                    recordOutputDir(it, it, compilation.resourceType, moduleData, moduleOutputsMap, gradleOutputMap)
                }
            }
        }
        if (outputDirs.any { FileUtil.isAncestor(ideaOutDir, File(it), false) }) {
            excludeOutDir(ideModule, ideaOutDir)
        }
    }

    override fun populateModuleDependencies(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>, ideProject: DataNode<ProjectData>) {
        super.populateModuleDependencies(gradleModule, ideModule, ideProject)
        val mppModel = resolverCtx.getExtraProject(gradleModule, KotlinMPPGradleModel::class.java) ?: return
        val sourceSetMap = ideProject.getUserData(GradleProjectResolver.RESOLVED_SOURCE_SETS) ?: return
        val artifactsMap = ideProject.getUserData(CONFIGURATION_ARTIFACTS) ?: return
        val processedModuleIds = HashSet<String>()
        processCompilations(gradleModule, mppModel, ideModule) { dataNode, compilation ->
            if (processedModuleIds.add(getModuleId(gradleModule, compilation))) {
                buildDependencies(resolverCtx, sourceSetMap, artifactsMap, dataNode, compilation.dependencies, ideProject)
                for (sourceSet in compilation.sourceSets) {
                    if (sourceSet.fullName() == compilation.fullName()) continue
                    val usedModuleId = getModuleId(gradleModule, sourceSet)
                    val usedModuleDataNode = ideModule.findChildModuleById(usedModuleId) ?: continue
                    addDependency(dataNode, usedModuleDataNode)
                }
            }
        }
        processSourceSets(gradleModule, mppModel, ideModule) { dataNode, sourceSet ->
            dataNode.data.productionModuleId?.let {
                val productionModuleDataNode = ideModule.findChildModuleByInternalName(it)
                if (productionModuleDataNode != null) {
                    addDependency(dataNode, productionModuleDataNode)
                }
            }
            if (processedModuleIds.add(getModuleId(gradleModule, sourceSet))) {
                buildDependencies(resolverCtx, sourceSetMap, artifactsMap, dataNode, sourceSet.dependencies, ideProject)
            }
        }
    }

    private fun addDependency(fromModule: DataNode<*>, toModule: DataNode<*>) {
        val fromData = fromModule.data as? ModuleData ?: return
        val toData = toModule.data as? ModuleData ?: return
        val moduleDependencyData = ModuleDependencyData(fromData, toData).also {
            it.scope = DependencyScope.COMPILE
            it.isExported = false
        }
        fromModule.createChild(ProjectKeys.MODULE_DEPENDENCY, moduleDependencyData)
    }

    private fun recordOutputDir(
        gradleOutputDir: File,
        effectiveOutputDir: File,
        sourceType: ExternalSystemSourceType,
        moduleData: KotlinSourceSetData,
        moduleOutputsMap: MutableMap<String, Pair<String, ExternalSystemSourceType>>,
        gradleOutputMap: MultiMap<ExternalSystemSourceType, String>
    ) {
        val gradleOutputPath = ExternalSystemApiUtil.toCanonicalPath(gradleOutputDir.absolutePath)
        gradleOutputMap.putValue(sourceType, gradleOutputPath)
        if (gradleOutputDir.path != effectiveOutputDir.path) {
            moduleOutputsMap[gradleOutputPath] = Pair(moduleData.id, sourceType)
        }
    }

    private fun excludeOutDir(ideModule: DataNode<ModuleData>, ideaOutDir: File) {
        val contentRootDataDataNode = ExternalSystemApiUtil.find(ideModule, ProjectKeys.CONTENT_ROOT)

        val excludedContentRootData: ContentRootData
        if (contentRootDataDataNode == null || !FileUtil.isAncestor(File(contentRootDataDataNode.data.rootPath), ideaOutDir, false)) {
            excludedContentRootData = ContentRootData(GradleConstants.SYSTEM_ID, ideaOutDir.absolutePath)
            ideModule.createChild(ProjectKeys.CONTENT_ROOT, excludedContentRootData)
        } else {
            excludedContentRootData = contentRootDataDataNode.data
        }

        excludedContentRootData.storePath(ExternalSystemSourceType.EXCLUDED, ideaOutDir.absolutePath)
    }

    private fun createContentRootData(sourceDirs: Set<File>, sourceType: ExternalSystemSourceType, parentNode: DataNode<*>) {
        for (sourceDir in sourceDirs) {
            val contentRootData = ContentRootData(GradleConstants.SYSTEM_ID, sourceDir.absolutePath)
            contentRootData.storePath(sourceType, sourceDir.absolutePath)
            parentNode.createChild(ProjectKeys.CONTENT_ROOT, contentRootData)
        }
    }

    private fun processSourceSets(
        gradleModule: IdeaModule,
        mppModel: KotlinMPPGradleModel,
        ideModule: DataNode<ModuleData>,
        processor: (DataNode<KotlinSourceSetData>, KotlinSourceSet) -> Unit
    ) {
        val sourceSetsMap = HashMap<String, DataNode<KotlinSourceSetData>>()
        for (dataNode in ExternalSystemApiUtil.findAll(ideModule, GradleSourceSetData.KEY)) {
            if (dataNode.data is KotlinSourceSetData) {
                @Suppress("UNCHECKED_CAST")
                sourceSetsMap[dataNode.data.id] = dataNode as DataNode<KotlinSourceSetData>
            }
        }
        for (sourceSet in mppModel.sourceSets) {
            val moduleId = getModuleId(gradleModule, sourceSet)
            val moduleDataNode = sourceSetsMap[moduleId] ?: continue
            processor(moduleDataNode, sourceSet)
        }
    }

    private fun processCompilations(
        gradleModule: IdeaModule,
        mppModel: KotlinMPPGradleModel,
        ideModule: DataNode<ModuleData>,
        processor: (DataNode<KotlinSourceSetData>, KotlinCompilation) -> Unit
    ) {
        val sourceSetsMap = HashMap<String, DataNode<KotlinSourceSetData>>()
        for (dataNode in ExternalSystemApiUtil.findAll(ideModule, GradleSourceSetData.KEY)) {
            if (dataNode.data is KotlinSourceSetData) {
                @Suppress("UNCHECKED_CAST")
                sourceSetsMap[dataNode.data.id] = dataNode as DataNode<KotlinSourceSetData>
            }
        }
        for (target in mppModel.targets) {
            for (compilation in target.compilations) {
                val moduleId = getModuleId(gradleModule, compilation)
                val moduleDataNode = sourceSetsMap[moduleId] ?: continue
                processor(moduleDataNode, compilation)
            }
        }
    }

    private fun createCompilerArguments(args: List<String>, platform: KotlinPlatform): CommonCompilerArguments {
        return when (platform) {
            KotlinPlatform.COMMON -> K2MetadataCompilerArguments()
            KotlinPlatform.JVM -> K2JVMCompilerArguments()
            KotlinPlatform.JS -> K2JSCompilerArguments()
        }.also {
            parseCommandLineArguments(args, it)
        }
    }

    private fun KotlinModule.fullName(simpleName: String = name) = when (this) {
        is KotlinCompilation -> target.disambiguationClassifier + simpleName.capitalize()
        else -> simpleName
    }

    private fun getModuleId(gradleModule: IdeaModule, kotlinModule: KotlinModule) =
        getModuleId(resolverCtx, gradleModule) + ":" + kotlinModule.fullName()

    private fun getExternalModuleName(gradleModule: IdeaModule, kotlinModule: KotlinModule) =
        gradleModule.name + ":" + kotlinModule.fullName()

    private fun getInternalModuleName(
        gradleModule: IdeaModule,
        externalProject: ExternalProject,
        kotlinModule: KotlinModule,
        actualName: String = kotlinModule.name
    ): String {
        val delimiter: String
        val moduleName = StringBuilder()
        if (resolverCtx.isUseQualifiedModuleNames) {
            delimiter = "."
            if (StringUtil.isNotEmpty(externalProject.group)) {
                moduleName.append(externalProject.group).append(delimiter)
            }
            moduleName.append(externalProject.name)
        } else {
            delimiter = "_"
            moduleName.append(gradleModule.name)
        }
        moduleName.append(delimiter)
        moduleName.append(kotlinModule.fullName(actualName))
        return PathUtilRt.suggestFileName(moduleName.toString(), true, false)
    }

    private fun createExternalSourceSet(compilation: KotlinCompilation, compilationData: KotlinSourceSetData): ExternalSourceSet {
        return DefaultExternalSourceSet().also { sourceSet ->
            val effectiveClassesDir = compilation.output.effectiveClassesDir
            val resourcesDir = compilation.output.resourcesDir

            sourceSet.name = compilation.fullName()
            sourceSet.targetCompatibility = compilationData.targetCompatibility
            sourceSet.dependencies += compilation.dependencies
            val sourcesWithTypes = SmartList<kotlin.Pair<IExternalSystemSourceType, ExternalSourceDirectorySet>>()
            if (effectiveClassesDir != null) {
                sourcesWithTypes += compilation.sourceType to DefaultExternalSourceDirectorySet().also { dirSet ->
                    dirSet.outputDir = effectiveClassesDir
                    dirSet.srcDirs = compilation.sourceSets.flatMapTo(LinkedHashSet()) { it.sourceDirs }
                    dirSet.gradleOutputDirs += compilation.output.classesDirs
                    dirSet.setInheritedCompilerOutput(false)
                }
            }
            if (resourcesDir != null) {
                sourcesWithTypes += compilation.resourceType to DefaultExternalSourceDirectorySet().also { dirSet ->
                    dirSet.outputDir = resourcesDir
                    dirSet.srcDirs = compilation.sourceSets.flatMapTo(LinkedHashSet()) { it.resourceDirs }
                    dirSet.gradleOutputDirs += resourcesDir
                    dirSet.setInheritedCompilerOutput(false)
                }
            }
            sourceSet.sources = sourcesWithTypes.toMap()
        }
    }

    private fun createExternalSourceSet(ktSourceSet: KotlinSourceSet, ktSourceSetData: KotlinSourceSetData): ExternalSourceSet {
        return DefaultExternalSourceSet().also { sourceSet ->
            sourceSet.name = ktSourceSet.name
            sourceSet.targetCompatibility = ktSourceSetData.targetCompatibility
            sourceSet.dependencies += ktSourceSet.dependencies
            sourceSet.sources = linkedMapOf<IExternalSystemSourceType, ExternalSourceDirectorySet>(
                ktSourceSet.sourceType to DefaultExternalSourceDirectorySet().also { dirSet ->
                    dirSet.srcDirs = ktSourceSet.sourceDirs
                },
                ktSourceSet.resourceType to DefaultExternalSourceDirectorySet().also { dirSet ->
                    dirSet.srcDirs = ktSourceSet.resourceDirs
                }
            )
        }
    }

    private val KotlinModule.sourceType
        get() = if (isTestModule) ExternalSystemSourceType.TEST else ExternalSystemSourceType.SOURCE

    private val KotlinModule.resourceType
        get() = if (isTestModule) ExternalSystemSourceType.TEST_RESOURCE else ExternalSystemSourceType.RESOURCE

    private val IdeaModule.jdkNameIfAny
        get() = try {
            jdkName
        } catch (e: UnsupportedMethodException) {
            null
        }

    companion object {
        private var DataNode<ProjectData>.kotlinModulesToExternalSourceSets: MutableMap<KotlinModule, ExternalSourceSet>?
                by UserDataProperty(Key.create("KOTLIN_MODULES_TO_EXTERNAL_SOURCE_SETS"))
    }
}