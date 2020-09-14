/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl.sdk

import com.intellij.execution.wsl.WSLDistributionWithRoot
import com.intellij.execution.wsl.WSLUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.wsl.WSLCredentialsEditor
import com.intellij.wsl.WSLCredentialsHolder
import com.intellij.wsl.WSLCredentialsType
import org.rust.ide.sdk.add.RsAddSdkPanel
import org.rust.ide.sdk.add.RsAddSdkProvider
import org.rust.openapiext.RsExecutionException
import org.rust.remote.RsAddSdkUsingCredentialsEditor
import org.rust.remote.RsRemoteSdkAdditionalData
import org.rust.remote.createAndInitRemoteSdk
import org.rust.remote.getRemoteToolchainVersion
import org.rust.stdext.Result
import org.rust.wsl.RsWslPathBrowser
import org.rust.wsl.distribution
import org.rust.wsl.toRemotePath
import org.rust.wsl.wslCredentials
import java.awt.BorderLayout
import java.awt.event.ActionListener
import javax.swing.Icon
import javax.swing.JTextPane

private const val URL: String = "ms-windows-store://search/?query=Linux"
private const val MESSAGE: String = "<html>You don't have WSL distribution installed. <a href=\"$URL\">Install WSL distributions.</a></html>"

class RsAddWslPanel(existingSdks: List<Sdk>)
    : RsAddSdkUsingCredentialsEditor<WSLCredentialsHolder>(existingSdks, WSLCredentialsType.getInstance()) {
    override val panelName: String = "WSL"
    override val icon: Icon = AllIcons.RunConfigurations.Wsl
    override val credentialsEditor: WSLCredentialsEditor by lazy { WSLCredentialsEditor() }
    private val browser: RsWslPathBrowser = RsWslPathBrowser(toolchainPathField)

    init {
        if (WSLUtil.hasAvailableDistributions()) {
            initUI()
        } else {
            layout = BorderLayout()
            add(Messages.configureMessagePaneUi(JTextPane(), MESSAGE), BorderLayout.NORTH)
        }
        toolchainPathField.text = FileUtil.expandUserHome("~/.cargo/bin/cargo")
    }

    override fun getHelpersPath(credentials: WSLCredentialsHolder): String {
        val helpersPath = super.getHelpersPath(credentials)
        return credentials.distribution?.toRemotePath(helpersPath) ?: helpersPath
    }

    override fun validateAll(): List<ValidationInfo> =
        if (!WSLUtil.hasAvailableDistributions()) {
            listOf(ValidationInfo("Can't find installed WSL distribution. Make sure you have one."))
        } else {
            super.validateAll()
        }

    override fun getBrowseButtonActionListener(): ActionListener =
        ActionListener {
            credentialsEditor.wslDistribution?.let { distro ->
                browser.browsePath(WSLDistributionWithRoot(distro), credentialsEditor.mainPanel)
            }
        }

    override fun createSdk(additionalData: RsRemoteSdkAdditionalData) =
        createAndInitRemoteSdk(additionalData, existingSdks, validateDataAndSuggestName(additionalData))

    private fun validateDataAndSuggestName(data: RsRemoteSdkAdditionalData): String? {
        val versionInfo = getRemoteToolchainVersion(null, data, false)
            ?: throw RsExecutionException("Can't obtain command:", data.interpreterPath, emptyList())
        val version = versionInfo.rustc?.semver?.parsedVersion
        return when (val distribution = data.distribution) {
            is Result.Success -> "$version @ ${distribution.result.presentableName}"
            is Result.Failure -> "Error: ${distribution.error}"
        }
    }

    fun configure(data: RsRemoteSdkAdditionalData) {
        toolchainPathField.text = data.interpreterPath
        credentialsEditor.init(data.wslCredentials)
    }

    override fun dispose() {
    }
}

class RsAddWslSdkProvider : RsAddSdkProvider {
    override fun createView(project: Project?, existingSdks: List<Sdk>): RsAddSdkPanel? =
        if (WSLUtil.isSystemCompatible()) RsAddWslPanel(existingSdks) else null
}
