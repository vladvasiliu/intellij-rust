/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.EncodingEnvironmentUtil
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.runconfig.buildtool.CargoPatch
import org.rust.cargo.runconfig.buildtool.cargoPatches
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.Cargo
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RustToolchain
import org.rust.ide.sdk.RsSdkUtils
import org.rust.remote.RsRemoteProcessStarter.startRemoteProcess
import java.nio.file.Path

abstract class CargoRunStateBase(
    environment: ExecutionEnvironment,
    val runConfiguration: CargoCommandConfiguration,
    val config: CargoCommandConfiguration.CleanConfiguration.Ok
) : CommandLineState(environment) {
    val toolchain: RustToolchain = config.toolchain
    val commandLine: CargoCommandLine = config.cmd
    val cargoProject: CargoProject? = CargoCommandConfiguration.findCargoProject(
        environment.project,
        commandLine.additionalArguments,
        commandLine.workingDirectory
    )
    private val workingDirectory: Path? get() = cargoProject?.workingDirectory

    protected val commandLinePatches: MutableList<CargoPatch> = mutableListOf()

    init {
        commandLinePatches.addAll(environment.cargoPatches)
    }

    fun cargo(): Cargo = toolchain.cargoOrWrapper(workingDirectory)

    fun rustVersion(): RustToolchain.VersionInfo = toolchain.queryVersions()

    fun prepareCommandLine(vararg additionalPatches: CargoPatch): CargoCommandLine {
        var commandLine = commandLine
        for (patch in commandLinePatches) {
            commandLine = patch(commandLine)
        }
        for (patch in additionalPatches) {
            commandLine = patch(commandLine)
        }
        return commandLine
    }

    override fun startProcess(): ProcessHandler = startProcess(emulateTerminal = false)

    fun startProcess(emulateTerminal: Boolean): ProcessHandler {
        var commandLine = cargo().toColoredCommandLine(environment.project, prepareCommandLine())

        if (emulateTerminal) {
            commandLine = PtyCommandLine(commandLine)
                .withInitialColumns(PtyCommandLine.MAX_COLUMNS)
                .withConsoleMode(false)
        }

        val sdk = runConfiguration.sdk
        if (sdk == null || !RsSdkUtils.isRemote(sdk)) {
            EncodingEnvironmentUtil.setLocaleEnvironmentIfMac(commandLine)
            return RsProcessHandler.create(commandLine)
        }

        return startRemoteProcess(sdk, commandLine, runConfiguration.project, null)
    }
}
