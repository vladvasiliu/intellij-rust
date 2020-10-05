/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageFeature

class EnableCargoFeaturesFix(
    private val cargoProject: CargoProject,
    private val features: Set<PackageFeature>
) : LocalQuickFix {
    override fun getFamilyName(): String = "Enable features"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        cargoProject.modifyFeatures(features, FeatureState.Enabled)
    }
}
