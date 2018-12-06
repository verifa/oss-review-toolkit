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

package com.here.ort.commands

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.here.ort.CommandWithHelp
import com.here.ort.model.*
import com.here.ort.model.config.*
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import java.io.File

@Parameters(commandNames = ["format-repository-configuration"], commandDescription = "Imports path excludes into an ort.yml file.")
object FormatRepositoryConfigurationCommand : CommandWithHelp() {
    @Parameter(description = "Target ort.yml file.",
        names = ["--target-ort-yml-file", "-t"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY)
    private lateinit var targetFile: File

    fun sortExcludes(config: RepositoryConfiguration): RepositoryConfiguration {
        val excludes = config.excludes?.let {
            val pathExcludes = it.paths.sortedBy {
                it.pattern.replace("*", "")
            }
            val scopeExcludes = it.scopes.sortedBy {
                it.name.toString().replace("\\.*", "")
            }
            it.copy(
                paths = pathExcludes,
                scopes = scopeExcludes
            )
        }

        return config.copy(excludes = excludes)
    }

    override fun runCommand(jc: JCommander): Int {
        println("format repository configuration: ${targetFile.absolutePath}")
        if (!targetFile.isFile) {
            println("cannot read file: ${targetFile.absolutePath}")
            return -1
        }

        val config = targetFile.readValue<RepositoryConfiguration>()
        yamlMapper.writeValue(targetFile, sortExcludes(config))

        println("configuration written to: '${targetFile.absolutePath}'")

        return 0
    }
}
