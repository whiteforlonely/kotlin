/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData

fun DataNode<*>.findChildModuleById(id: String) = children.firstOrNull { (it.data as? ModuleData)?.id == id }

fun DataNode<*>.findChildModuleByInternalName(name: String) = children.firstOrNull { (it.data as? ModuleData)?.internalName == name }