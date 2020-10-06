/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.ui

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.Label
import com.intellij.ui.layout.CCFlags
import com.intellij.ui.layout.LayoutBuilder
import com.intellij.ui.layout.Row
import com.intellij.ui.layout.panel
import org.rust.cargo.project.configurable.RsConfigurableToolchainList
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.BacktraceMode
import org.rust.cargo.util.CargoCommandCompletionProvider
import org.rust.cargo.util.RsCommandLineEditor
import org.rust.ide.sdk.RsSdkListCellRenderer
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class CargoCommandConfigurationEditor(project: Project)
    : RsCommandConfigurationEditor<CargoCommandConfiguration>(project) {

    override val command = RsCommandLineEditor(
        project, CargoCommandCompletionProvider(project.cargoProjects) { currentWorkspace() }
    )

    private val allCargoProjects: List<CargoProject> =
        project.cargoProjects.allProjects.sortedBy { it.presentableName }

    private val backtraceMode = ComboBox<BacktraceMode>().apply {
        BacktraceMode.values()
            .sortedBy { it.index }
            .forEach { addItem(it) }
    }

    private val toolchainList = RsConfigurableToolchainList.getInstance(project)

    var sdk: Sdk?
        get() = sdkList.component.selectedItem as? Sdk
        set(value) {
            val items: MutableList<Sdk?> = toolchainList.allRustSdks.toMutableList()
            items.add(0, null)
            val selection = value?.let { toolchainList.model.findSdk(it.name) }
            sdkList.component.model = CollectionComboBoxModel(items, selection)
        }
    private val sdkList = run {
        val comboBox = ComboBox<Sdk?>().apply {
            renderer = RsSdkListCellRenderer(null, "<Project Default>")
        }
        LabeledComponent.create(comboBox, "Rust &toolchain:")
    }

    override fun disposeEditor() {
        toolchainList.disposeModel()
        super.disposeEditor()
    }

    private val cargoProject = ComboBox<CargoProject>().apply {
        renderer = SimpleListCellRenderer.create("") { it.presentableName }
        allCargoProjects.forEach { addItem(it) }

        addItemListener {
            setWorkingDirectoryFromSelectedProject()
        }
    }

    private fun setWorkingDirectoryFromSelectedProject() {
        val selectedProject = run {
            val idx = cargoProject.selectedIndex
            if (idx == -1) return
            cargoProject.getItemAt(idx)
        }
        workingDirectory.component.text = selectedProject.workingDirectory.toString()
    }

    private val environmentVariables = EnvironmentVariablesComponent()
    private val allFeatures = CheckBox("Use all features in tests", false)
    private val emulateTerminal = CheckBox("Emulate terminal in output console", false)

    override fun resetEditorFrom(configuration: CargoCommandConfiguration) {
        sdk = configuration.sdk
        command.text = configuration.command
        allFeatures.isSelected = configuration.allFeatures
        emulateTerminal.isSelected = configuration.emulateTerminal
        backtraceMode.selectedIndex = configuration.backtrace.index
        workingDirectory.component.text = configuration.workingDirectory?.toString() ?: ""
        environmentVariables.envData = configuration.env
        val vFile = currentWorkingDirectory?.let { LocalFileSystem.getInstance().findFileByIoFile(it.toFile()) }
        if (vFile == null) {
            cargoProject.selectedIndex = -1
        } else {
            val projectForWd = project.cargoProjects.findProjectForFile(vFile)
            cargoProject.selectedIndex = allCargoProjects.indexOf(projectForWd)
        }
    }

    @Throws(ConfigurationException::class)
    override fun applyEditorTo(configuration: CargoCommandConfiguration) {
        configuration.sdk = sdk
        configuration.command = command.text
        configuration.allFeatures = allFeatures.isSelected
        configuration.emulateTerminal = emulateTerminal.isSelected && !SystemInfo.isWindows
        configuration.backtrace = BacktraceMode.fromIndex(backtraceMode.selectedIndex)
        configuration.workingDirectory = currentWorkingDirectory
        configuration.env = environmentVariables.envData
    }

    override fun createEditor(): JComponent = panel {
        labeledRow("&Command:", command) {
            command(CCFlags.pushX, CCFlags.growX)
        }

        row { allFeatures() }

        if (!SystemInfo.isWindows) {
            row { emulateTerminal() }
        }

        row(environmentVariables.label) { environmentVariables.apply { makeWide() }() }
        row(sdkList.label) { sdkList.apply { makeWide() }() }
        row(workingDirectory.label) {
            workingDirectory.apply { makeWide() }()
            if (project.cargoProjects.allProjects.size > 1) {
                cargoProject()
            }
        }
        labeledRow("&Backtrace:", backtraceMode) { backtraceMode() }
    }

    private fun LayoutBuilder.labeledRow(labelText: String, component: JComponent, init: Row.() -> Unit) {
        val label = Label(labelText)
        label.labelFor = component
        row(label) { init() }
    }

    private fun JPanel.makeWide() {
        preferredSize = Dimension(1000, height)
    }
}
