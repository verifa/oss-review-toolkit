/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.helper.commands

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

import com.here.ort.CommandWithHelp
import com.here.ort.model.OrtResult
import com.here.ort.model.Severity
import com.here.ort.model.config.*
import com.here.ort.model.readValue
import com.here.ort.model.yamlMapper
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL

import java.io.File

@Parameters(
    commandNames = ["generate-rule-violation-resolutions"],
    commandDescription = "Generates resolutions for all unresolved rule violations."
)
internal class GenerateRuleViolationResolutionsCommand : CommandWithHelp() {
    @Parameter(
        names = ["--ort-result-file"],
        required = true,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private lateinit var ortResultFile: File

    @Parameter(
        names = ["--repository-configuration-file"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY
    )
    private lateinit var repositoryConfigurationFile: File

    @Parameter(
        names = ["--severity"],
        required = false,
        order = PARAMETER_ORDER_OPTIONAL,
        converter = SeverityConverter::class
    )
    private var severity: Severity? = null

    override fun runCommand(jc: JCommander): Int {
        val repositoryConfiguration = repositoryConfigurationFile.readValue<RepositoryConfiguration>()
        var ortResult = ortResultFile.readValue<OrtResult>().replaceConfig(repositoryConfiguration)

        val resolutions = ortResult
            .getUnresolvedRuleViolations()
            .filter { (severity == null || it.severity == severity) }
            .map {
                RuleViolationResolution(
                    message = it.message,
                    reason = RuleViolationResolutionReason.CANT_FIX_EXCEPTION,
                    comment = "TODO"
                )
            }

        repositoryConfiguration.replaceRuleViolationResolutions(
            (repositoryConfiguration.resolutions?.ruleViolations ?: emptyList()) + resolutions
        )

        return 0
    }
}

internal class SeverityConverter : IStringConverter<Severity?> {
    override fun convert(value: String?): Severity? {
        return value?.let { Severity.valueOf(value) }
    }
}
