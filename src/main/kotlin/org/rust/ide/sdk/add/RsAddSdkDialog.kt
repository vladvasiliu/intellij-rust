/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk.add

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.ListItemDescriptorAdapter
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBCardLayout
import com.intellij.ui.components.JBList
import com.intellij.ui.popup.list.GroupedItemsListRenderer
import com.intellij.util.ui.JBUI
import java.awt.CardLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

class RsAddSdkDialog private constructor(
    project: Project?,
    private val existingSdks: List<Sdk>
) : DialogWrapper(project) {
    private var selectedPanel: RsAddToolchainPanel? = null
    private var panels: List<RsAddToolchainPanel> = emptyList()
    private var navigationPanelCardLayout: CardLayout? = null
    private var southPanel: JPanel? = null

    init {
        title = "Add Rust Toolchain"
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(JBCardLayout())
        val panels = listOf(RsAddLocalToolchainPanel(existingSdks))
        mainPanel.add(SPLITTER_COMPONENT_CARD_PANE, createCardSplitter(panels))
        return mainPanel
    }

    override fun createSouthPanel(): JComponent {
        val regularDialogSouthPanel = super.createSouthPanel()
        navigationPanelCardLayout = CardLayout()
        val result = JPanel(navigationPanelCardLayout).apply {
            add(regularDialogSouthPanel, REGULAR_CARD_PANE)
        }
        southPanel = result
        return result
    }

    override fun postponeValidation(): Boolean = false

    override fun doValidateAll(): List<ValidationInfo> = selectedPanel?.validateAll().orEmpty()

    fun getOrCreateSdk(): Sdk? = selectedPanel?.getOrCreateSdk()

    private fun createCardSplitter(panels: List<RsAddToolchainPanel>): Splitter {
        this.panels = panels
        return Splitter(false, 0.25f).apply {
            val cardLayout = CardLayout()
            val cardPanel = JPanel(cardLayout).apply {
                preferredSize = JBUI.size(640, 480)
                for (panel in panels) {
                    add(panel, panel.panelName)
                }
            }
            val cardsList = JBList(panels).apply {
                val descriptor = object : ListItemDescriptorAdapter<RsAddToolchainPanel>() {
                    override fun getTextFor(value: RsAddToolchainPanel): String = value.panelName
                    override fun getIconFor(value: RsAddToolchainPanel): Icon = value.icon
                }

                cellRenderer = object : GroupedItemsListRenderer<RsAddToolchainPanel>(descriptor) {
                    override fun createItemComponent(): JComponent = super.createItemComponent()
                        .apply { border = JBUI.Borders.empty(4, 4, 4, 10) }
                }

                addListSelectionListener {
                    selectedPanel = selectedValue
                    cardLayout.show(cardPanel, selectedValue.panelName)

                    val southPanel = southPanel ?: return@addListSelectionListener
                    navigationPanelCardLayout?.show(southPanel, REGULAR_CARD_PANE)
                    rootPane.defaultButton = getButton(okAction)
                }

                selectedPanel = panels.firstOrNull()
                selectedIndex = 0
            }

            firstComponent = cardsList
            secondComponent = cardPanel
        }
    }

    override fun dispose() {
        panels.forEach { Disposer.dispose(it) }
        super.dispose()
    }

    companion object {
        private const val SPLITTER_COMPONENT_CARD_PANE: String = "Splitter"
        private const val REGULAR_CARD_PANE: String = "Regular"

        fun show(project: Project?, existingSdks: List<Sdk>, sdkAddedCallback: (Sdk?) -> Unit) {
            val dialog = RsAddSdkDialog(project, existingSdks)
            dialog.init()

            val sdk = if (dialog.showAndGet()) dialog.getOrCreateSdk() else null
            sdkAddedCallback(sdk)
        }
    }
}
