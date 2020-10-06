/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.idea

import com.intellij.ide.util.importProject.ProjectDescriptor
import com.intellij.ide.util.projectWizard.ModuleBuilder.ModuleConfigurationUpdater
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.layout.panel
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.model.setup
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.toolchain.RustToolchain
import org.rust.ide.newProject.ui.RsNewProjectPanel
import org.rust.openapiext.pathAsPath
import javax.swing.JComponent

class CargoConfigurationWizardStep private constructor(
    private val context: WizardContext,
    private val projectDescriptor: ProjectDescriptor? = null
) : ModuleWizardStep() {

    private val newProjectPanel = RsNewProjectPanel(context.project, projectDescriptor == null)

    override fun getComponent(): JComponent = panel {
        newProjectPanel.attachTo(this)
    }

    override fun disposeUIResources() = Disposer.dispose(newProjectPanel)

    override fun updateDataModel() {
        val data = newProjectPanel.data
        ConfigurationUpdater.sdk = data.sdk

        val projectBuilder = context.projectBuilder
        if (projectBuilder is RsModuleBuilder) {
            projectBuilder.configurationData = data
            projectBuilder.addModuleConfigurationUpdater(ConfigurationUpdater)
        } else {
            projectDescriptor?.modules?.firstOrNull()?.addConfigurationUpdater(ConfigurationUpdater)
        }
    }

    @Throws(ConfigurationException::class)
    override fun validate(): Boolean {
        newProjectPanel.validateSettings()
        return true
    }

    private object ConfigurationUpdater : ModuleConfigurationUpdater() {
        var sdk: Sdk? = null

        override fun update(module: Module, rootModel: ModifiableRootModel) {
            val sdk = sdk
            if (sdk != null) {
                rootModel.sdk = sdk
                module.project.rustSettings.modify {
                    it.sdk = sdk
                }
            }
            // We don't use SDK, but let's inherit one to reduce the amount of
            // "SDK not configured" errors
            // https://github.com/intellij-rust/intellij-rust/issues/1062
            rootModel.inheritSdk()

            val contentEntry = rootModel.contentEntries.singleOrNull()
            if (contentEntry != null) {
                val manifest = contentEntry.file?.findChild(RustToolchain.CARGO_TOML)
                if (manifest != null) {
                    module.project.cargoProjects.attachCargoProject(manifest.pathAsPath)
                }

                val projectRoot = contentEntry.file ?: return
                contentEntry.setup(projectRoot)
            }
        }
    }

    companion object {
        fun newProject(context: WizardContext) =
            CargoConfigurationWizardStep(context, null)

        fun importExistingProject(context: WizardContext, projectDescriptor: ProjectDescriptor) =
            CargoConfigurationWizardStep(context, projectDescriptor)
    }
}
