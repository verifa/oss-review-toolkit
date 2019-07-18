package com.here.ort.helper.commands

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

import com.here.ort.CommandWithHelp
import com.here.ort.model.OrtResult
import com.here.ort.model.config.ErrorResolution
import com.here.ort.model.config.ErrorResolutionReason
import com.here.ort.model.config.Resolutions
import com.here.ort.model.readValue
import com.here.ort.model.yamlMapper
import com.here.ort.reporter.DefaultResolutionProvider
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL

import java.io.File

@Parameters(
    commandNames = ["generate-timeout-error-resolutions"],
    commandDescription = "Generates resolutions for scanner timeout errors."
)
internal class GenerateTimeoutErrorResolutionsCommand : CommandWithHelp() {
    @Parameter(
        names = ["--ort-result-file"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY
    )
    private lateinit var ortResultFile: File

    @Parameter(
        names = ["--repository-configuration-file"],
        required = false,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var repositoryConfigurationFile: File? = null

    @Parameter(
        names = ["--resolutions-file"],
        required = false,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var resolutionsFile: File? = null

    @Parameter(
        names = ["--omit-excluded"],
        required = false,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var omitExcluded: Boolean = false

    override fun runCommand(jc: JCommander): Int {
        var ortResult = ortResultFile.readValue<OrtResult>()
        repositoryConfigurationFile?.let {
            ortResult = ortResult.replaceConfig(it.readValue())
        }

        val resolutionProvider = DefaultResolutionProvider().apply {
            var resolutions = Resolutions()

            resolutionsFile?.let {
                resolutions = resolutions.merge(it.readValue())
            }

            ortResult.repository.config.resolutions?.let {
                resolutions = resolutions.merge(it)
            }

            add(resolutions)
        }

        val timeoutIssues = ortResult
            .getScanIssues(omitExcluded)
            .filter {
                it.message.startsWith("ERROR: Timeout")
                        && resolutionProvider.getErrorResolutionsFor(it).isEmpty()
            }

        val generatedResolutions = timeoutIssues.map {
            ErrorResolution(
                message = it.message,
                reason = ErrorResolutionReason.SCANNER_ISSUE,
                comment = "TODO"
            )
        }.distinct().sortedBy { it.message }

        val yaml = yamlMapper.writeValueAsString(generatedResolutions)
        println(yaml)

        return 0
    }
}
