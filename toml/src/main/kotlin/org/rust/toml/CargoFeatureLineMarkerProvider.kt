/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageOrigin.WORKSPACE
import org.rust.cargo.toolchain.RustToolchain.Companion.CARGO_TOML
import org.rust.ide.icons.RsIcons
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.lang.core.psi.ext.elementType
import org.rust.lang.core.psi.ext.findCargoPackage
import org.rust.lang.core.psi.ext.findCargoProject
import org.rust.openapiext.saveAllDocuments
import org.toml.lang.psi.*
import java.awt.event.MouseEvent

class CargoFeatureLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (!tomlPluginIsAbiCompatible()) return

        val firstElement = elements.firstOrNull() ?: return
        val file = firstElement.containingFile as? TomlFile ?: return
        if (!file.name.equals(CARGO_TOML, ignoreCase = true)) return
        val cargoPackage = file.findCargoPackage() ?: return
        val features = cargoPackage.featureState

        loop@ for (element in elements) {
            val parent = element.parent
            if (parent is TomlKey) {
                val key = parent
                val isFeatureKey = key.isFeatureKey
                if (!isFeatureKey && !key.isDependencyName) continue@loop
                val featureName = key.text
                if ("." in featureName) continue@loop
                if (!isFeatureKey && featureName !in features) continue@loop
                result += genFeatureLineMarkerInfo(
                    key,
                    featureName,
                    features[featureName],
                    cargoPackage
                )
            }
            if (element.elementType == TomlElementTypes.L_BRACKET && cargoPackage.origin == WORKSPACE) {
                val header = parent as? TomlTableHeader ?: continue@loop
                if (!header.isFeatureListHeader) continue@loop
                result += genSettingsLineMarkerInfo(header)
            }
        }
    }

    private val PsiElement.isDependencyName
        get() = CargoTomlPsiPattern.onDependencyKey.accepts(this) ||
            CargoTomlPsiPattern.onSpecificDependencyHeaderKey.accepts(this)

    private val TomlKey.isFeatureKey: Boolean
        get() {
            val keyValue = parent as? TomlKeyValue ?: return false
            val table = keyValue.parent as? TomlTable ?: return false
            return table.header.isFeatureListHeader
        }

    private fun genFeatureLineMarkerInfo(
        element: TomlKey,
        name: String,
        featureState: FeatureState?,
        cargoPackage: CargoWorkspace.Package
    ): LineMarkerInfo<PsiElement> {
        val anchor = element.firstChild

        return when (cargoPackage.origin) {
            WORKSPACE -> {
                val icon = when (featureState) {
                    FeatureState.Enabled -> RsIcons.FEATURE_CHECKED_MARK
                    FeatureState.Disabled, null -> RsIcons.FEATURE_UNCHECKED_MARK
                }
                LineMarkerInfo(
                    anchor,
                    anchor.textRange,
                    icon,
                    { "Toggle feature `$name`" },
                    ToggleFeatureAction,
                    Alignment.RIGHT
                )
            }

            else -> {
                val icon = when (featureState) {
                    FeatureState.Enabled -> RsIcons.FEATURE_CHECKED_MARK_GRAYED
                    FeatureState.Disabled, null -> RsIcons.FEATURE_UNCHECKED_MARK_GRAYED
                }
                LineMarkerInfo(
                    anchor,
                    anchor.textRange,
                    icon,
                    { "Feature `$name` is $featureState" },
                    null,
                    Alignment.RIGHT
                )
            }
        }
    }

    private fun genSettingsLineMarkerInfo(header: TomlTableHeader): LineMarkerInfo<PsiElement> {
        val anchor = header.firstChild

        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            RsIcons.FEATURES_SETTINGS,
            { "Configure features" },
            OpenSettingsAction,
            Alignment.RIGHT
        )
    }
}

private object ToggleFeatureAction : GutterIconNavigationHandler<PsiElement> {
    override fun navigate(e: MouseEvent, element: PsiElement) {
        val context = getContext(element) ?: return
        val featureName = element.ancestorStrict<TomlKey>()?.text ?: return
        val oldState = context.cargoPackage.featureState.getOrDefault(featureName, FeatureState.Disabled)
        val newState = !oldState
        val tomlDoc = PsiDocumentManager.getInstance(context.cargoProject.project).getDocument(element.containingFile)
        val isDocUnsaved = tomlDoc != null && FileDocumentManager.getInstance().isDocumentUnsaved(tomlDoc)

        if (isDocUnsaved) {
            runWriteAction { saveAllDocuments() }
            context.cargoProjectsService.refreshAllProjects()
        }

        context.cargoProjectsService.updateFeatures(
            context.cargoProject,
            setOf(context.cargoPackage.findFeature(featureName)),
            newState
        )
    }
}

private object OpenSettingsAction : GutterIconNavigationHandler<PsiElement> {
    override fun navigate(e: MouseEvent, element: PsiElement) {
        val context = getContext(element) ?: return
        createActionGroupPopup(context).show(RelativePoint(e))
    }

    private fun createActionGroupPopup(context: Context): JBPopup {
        val actions = listOf(
            FeaturesSettingsCheckboxAction(context, FeatureState.Enabled),
            FeaturesSettingsCheckboxAction(context, FeatureState.Disabled)
        )
        val group = DefaultActionGroup(actions)
        val dataContext = SimpleDataContext.getProjectContext(context.cargoProject.project)
        return JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
    }

    private class FeaturesSettingsCheckboxAction(
        private val context: Context,
        private val newState: FeatureState
    ) : AnAction() {

        init {
            val text = when (newState) {
                FeatureState.Enabled -> "Enable all features"
                FeatureState.Disabled -> "Disable all features"
            }
            templatePresentation.description = text
            templatePresentation.text = text
        }

        override fun actionPerformed(e: AnActionEvent) {
            context.cargoProjectsService.updateFeatures(context.cargoProject, context.cargoPackage.features, newState)
        }
    }
}

private data class Context(
    val cargoProjectsService: CargoProjectsService,
    val cargoProject: CargoProject,
    val cargoPackage: CargoWorkspace.Package
)

private fun getContext(element: PsiElement): Context? {
    val file = element.containingFile as? TomlFile ?: return null
    if (!file.name.equals(CARGO_TOML, ignoreCase = true)) return null

    val cargoProject = file.findCargoProject() ?: return null
    val cargoPackage = file.findCargoPackage() ?: return null
    return Context(file.project.cargoProjects, cargoProject, cargoPackage)
}
