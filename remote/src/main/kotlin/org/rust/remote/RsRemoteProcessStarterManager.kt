/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import org.rust.remote.sdk.RsRemoteSdkAdditionalData

/**
 * For anything but plain code execution consider introducing a separate extension point with implementations for
 * different [com.intellij.remote.CredentialsType] using [RsRemoteSdkAdditionalData.switchOnConnectionType].
 */
interface RsRemoteProcessStarterManager {
    fun supports(sdkAdditionalData: RsRemoteSdkAdditionalData): Boolean

    @Throws(ExecutionException::class, InterruptedException::class)
    fun startRemoteProcess(
        project: Project?,
        commandLine: GeneralCommandLine,
        sdkAdditionalData: RsRemoteSdkAdditionalData,
        pathMapper: RsRemotePathMapper
    ): ProcessHandler

    @Throws(ExecutionException::class, InterruptedException::class)
    fun executeRemoteProcess(
        project: Project?,
        command: Array<String>,
        workingDir: String?,
        sdkAdditionalData: RsRemoteSdkAdditionalData,
        pathMapper: RsRemotePathMapper
    ): ProcessOutput

    companion object {
        private val EP_NAME: ExtensionPointName<RsRemoteProcessStarterManager> =
            ExtensionPointName.create("org.rust.remoteProcessStarterManager")

        fun getManager(additionalData: RsRemoteSdkAdditionalData): RsRemoteProcessStarterManager =
            EP_NAME.extensions.find { it.supports(additionalData) } ?: throw RsUnsupportedSdkTypeException()
    }
}
