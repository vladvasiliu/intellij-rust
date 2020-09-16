/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessRunner
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.wsl.WSLCredentialsHolder
import com.intellij.wsl.WSLCredentialsType
import org.rust.openapiext.runUnderProgress
import org.rust.remote.sdk.RsRemoteSdkAdditionalData
import org.rust.stdext.Result
import java.io.File

internal val Sdk.distribution: Result<WSLDistribution, String>?
    get() = (sdkAdditionalData as? RsRemoteSdkAdditionalData)?.distribution

internal val Sdk.isWsl: Boolean
    get() = (sdkAdditionalData as? RsRemoteSdkAdditionalData)?.isWsl == true

internal val RsRemoteSdkAdditionalData.wslCredentials: WSLCredentialsHolder?
    get() = connectionCredentials().credentials as? WSLCredentialsHolder

internal val RsRemoteSdkAdditionalData.distribution: Result<WSLDistribution, String>
    get() = wslCredentials?.distribution?.let { Result.Success<WSLDistribution, String>(it) }
        ?: Result.Failure("Unknown distribution ${wslCredentials?.distributionId}")

internal fun WSLDistribution.toRemotePath(localPath: String): String =
    localPath.split(File.pathSeparatorChar).joinToString(":") { getWslPath(it) ?: it }

internal val RsRemoteSdkAdditionalData.isWsl: Boolean
    get() = remoteConnectionType == WSLCredentialsType.getInstance()

internal fun startWslProcess(
    project: Project?,
    commandLine: GeneralCommandLine,
    sdkAdditionalData: RsRemoteSdkAdditionalData,
    sudo: Boolean,
    remoteWorkDir: String? = null,
    patchExe: Boolean = true
): RsWslProcessHandler = startWslProcessImpl(
    project,
    commandLine,
    sdkAdditionalData,
    sudo,
    remoteWorkDir,
    patchExe,
    closeStdin = !isWsl1(project, sdkAdditionalData)
)

internal fun isWsl1(project: Project?, sdkAdditionalData: RsRemoteSdkAdditionalData) =
    getWslOutput(project, "Obtaining WSL version...", sdkAdditionalData, "uname -v")
        .successOrNull
        ?.contains("Microsoft")
        ?: true

private val ProcessOutput.result: Result<String, Pair<Int, String>>
    get() = when (exitCode) {
        0 -> Result.Success(stdout)
        else -> Result.Failure(Pair(exitCode, stderr))
    }

private fun getWslOutput(
    project: Project?,
    title: String,
    sdkAdditionalData: RsRemoteSdkAdditionalData,
    command: String,
    patchExe: Boolean = false
): Result<String, Pair<Int, String>> =
    ProgressManager.getInstance().runUnderProgress(title) {
        val wslProcess = startWslProcessImpl(
            project = project,
            commandLine = GeneralCommandLine(command.split(" ")),
            sdkData = sdkAdditionalData,
            closeStdin = true,
            patchExe = patchExe,
            sudo = false
        )
        CapturingProcessRunner(wslProcess).runProcess().result
    }

private fun startWslProcessImpl(
    project: Project?,
    commandLine: GeneralCommandLine,
    sdkData: RsRemoteSdkAdditionalData,
    sudo: Boolean,
    remoteWorkDir: String? = null,
    patchExe: Boolean = true,
    closeStdin: Boolean
): RsWslProcessHandler {
    val distribution = when (val result = sdkData.distribution) {
        is Result.Success -> result.result
        is Result.Failure -> throw ExecutionException(result.error)
    }

    if (patchExe) {
        commandLine.exePath = sdkData.interpreterPath
    }

    for (group in commandLine.parametersList.paramsGroups) {
        val params = ArrayList(group.parameters)
        group.parametersList.clearAll()
        group.parametersList.addAll(params.map { distribution.toRemotePath(it) })
    }

    commandLine.environment.forEach { (k, v) ->
        commandLine.environment[k] = distribution.toRemotePath(v)
    }
    commandLine.workDirectory?.let {
        if (it.path.startsWith("/")) {
            commandLine.workDirectory = File(distribution.getWindowsPath(it.path) ?: it.path)
        }
    }

    val effectiveRemoteWorkDir = remoteWorkDir
        ?: commandLine.workDirectory?.toString()?.let { distribution.toRemotePath(it) }
    distribution.patchCommandLine(commandLine, project, effectiveRemoteWorkDir, sudo)

    val processHandler = RsWslProcessHandler(commandLine)
    if (closeStdin) {
        @Suppress("UnstableApiUsage")
        WSLUtil.addInputCloseListener(processHandler)
    }

    return distribution.patchProcessHandler(commandLine, processHandler)
}
