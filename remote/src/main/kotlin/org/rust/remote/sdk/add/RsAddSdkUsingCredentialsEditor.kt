/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote.sdk.add

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.remote.CredentialsType
import com.intellij.remote.ext.CredentialsEditor
import com.intellij.ui.PanelWithAnchor
import com.intellij.ui.StatusPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import org.rust.ide.sdk.add.RsAddSdkPanel
import org.rust.remote.createAndInitRemoteSdk
import org.rust.remote.sdk.RsRemoteSdkAdditionalData
import java.awt.BorderLayout
import java.awt.event.ActionListener

abstract class RsAddSdkUsingCredentialsEditor<T>(
    protected val existingSdks: List<Sdk>,
    private val credentialsType: CredentialsType<T>
) : RsAddSdkPanel() {
    private val statusPanel: StatusPanel = StatusPanel()

    protected val toolchainPathField = JBTextField("cargo")

    protected abstract val credentialsEditor: CredentialsEditor<T>

    override fun onSelected() = credentialsEditor.onSelected()

    override fun validateAll(): List<ValidationInfo> = listOfNotNull(credentialsEditor.validate())

    private var preparedSdk: Sdk? = null

    override val sdk: Sdk?
        get() = preparedSdk

    final override fun complete() {
        val sdkAdditionalData = RsRemoteSdkAdditionalData(toolchainPathField.text)
        val credentials = credentialsType.createCredentials()
        credentialsEditor.saveCredentials(credentials)
        sdkAdditionalData.setCredentials(credentialsType.credentialsKey, credentials)
        val createAndInitRemoteSdk = createSdk(sdkAdditionalData)
        preparedSdk = createAndInitRemoteSdk
    }

    protected open fun createSdk(additionalData: RsRemoteSdkAdditionalData): Sdk =
        createAndInitRemoteSdk(additionalData, existingSdks)

    protected fun initUI() {
        layout = BorderLayout()

        val toolchainPathLabel = JBLabel("Toolchain path:")

        val form = FormBuilder().addComponent(credentialsEditor.mainPanel)

        val listener = getBrowseButtonActionListener()
        form.addLabeledComponent(toolchainPathLabel, ComponentWithBrowseButton(toolchainPathField, listener))
        form.addComponent(statusPanel)

        (credentialsEditor as? PanelWithAnchor)?.anchor = toolchainPathLabel

        add(form.panel, BorderLayout.NORTH)
    }

    /**
     * If return value is not null then toolchain path has "browse" button with this listener.
     */
    abstract fun getBrowseButtonActionListener(): ActionListener
}
