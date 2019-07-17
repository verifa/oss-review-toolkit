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

package com.here.ort.helper

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.beust.jcommander.converters.FileConverter

import com.here.ort.CommandWithHelp
import com.here.ort.helper.commands.prettyPrintAsYaml
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.model.readValue
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY

import java.io.File

@Parameters(
    commandNames = ["sort-repository-configuration"],
    commandDescription = "Sort entries of the repository configuration alphabetically."
)
internal class SortRepositoryConfigurationCommand : CommandWithHelp() {
    @Parameter(
        description = "repository configuration file.",
        required = true,
        order = PARAMETER_ORDER_MANDATORY,
        converter = FileConverter::class
    )
    private lateinit var repositoryConfigurationFile: File

    override fun runCommand(jc: JCommander): Int {
        repositoryConfigurationFile
            .readValue<RepositoryConfiguration>()
            .sortExcludes()
            .prettyPrintAsYaml(repositoryConfigurationFile)

        return 0
    }
}

private fun RepositoryConfiguration.sortExcludes(): RepositoryConfiguration {
    val excludes = excludes?.let {
        val pathExcludes = it.paths.sortedBy { pathExclude ->
            pathExclude.pattern.replace("*", "")
        }
        val scopeExcludes = it.scopes.sortedBy { scopeExclude ->
            scopeExclude.name.toString().replace("\\.*", "")
        }
        it.copy(
            paths = pathExcludes,
            scopes = scopeExcludes
        )
    }

    return copy(excludes = excludes)
}
