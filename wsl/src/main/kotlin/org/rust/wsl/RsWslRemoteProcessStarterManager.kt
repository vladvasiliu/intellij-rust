/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessRunner
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.project.Project
import org.rust.cargo.runconfig.RsProcessHandler
import org.rust.remote.RsRemotePathMapper
import org.rust.remote.RsRemoteProcessStarterManager
import org.rust.remote.sdk.RsRemoteSdkAdditionalData
import org.rust.stdext.Result

object RsWslRemoteProcessStarterManager : RsRemoteProcessStarterManager {

    override fun supports(sdkAdditionalData: RsRemoteSdkAdditionalData): Boolean = sdkAdditionalData.isWsl

    override fun startRemoteProcess(
        project: Project?,
        commandLine: GeneralCommandLine,
        sdkAdditionalData: RsRemoteSdkAdditionalData,
        pathMapper: RsRemotePathMapper
    ): RsProcessHandler = startWslProcess(
        project,
        commandLine,
        sdkAdditionalData,
        sudo = false
    )

    override fun executeRemoteProcess(
        project: Project?,
        command: Array<String>,
        workingDir: String?,
        sdkAdditionalData: RsRemoteSdkAdditionalData,
        pathMapper: RsRemotePathMapper
    ): ProcessOutput {
        val distribution = when (val result = sdkAdditionalData.distribution) {
            is Result.Success -> result.result
            is Result.Failure -> throw ExecutionException(result.error)
        }
        val localDir = workingDir?.let { distribution.getWindowsPath(it) }
        val commandLine = GeneralCommandLine(*command).withWorkDirectory(localDir)
        val process = startRemoteProcess(project, commandLine, sdkAdditionalData, pathMapper)
        return CapturingProcessRunner(process).runProcess()
    }
}
