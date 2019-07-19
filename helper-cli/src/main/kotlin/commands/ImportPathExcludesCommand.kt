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

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

import com.here.ort.CommandWithHelp
import com.here.ort.analyzer.Analyzer
import com.here.ort.model.config.*
import com.here.ort.model.readValue
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL

import java.io.File
import java.lang.Exception

@Parameters(
    commandNames = ["import-path-excludes"],
    commandDescription = "Import path excludes from a global path excludes file."
)
internal class ImportPathExcludesCommand : CommandWithHelp() {
    @Parameter(
        names = ["--path-excludes-file"],
        required = true,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private lateinit var pathExcludesFile: File

    @Parameter(
        names = ["--source-code-dir"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY
    )
    private lateinit var sourceCodeDir: File

    @Parameter(
        names = ["--repository-configuration-file"],
        required = true,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private lateinit var repositoryConfigurationFile: File

    override fun runCommand(jc: JCommander): Int {
        val allFiles = findAllFiles(sourceCodeDir)
        val repositoryConfiguration = try {
            repositoryConfigurationFile.readValue<RepositoryConfiguration>()
        } catch (e: Exception) { RepositoryConfiguration() }

        val existingPathExcludes = repositoryConfiguration.excludes?.paths ?: emptyList()
        val importedPathExcludes = importPathExcludes()
            .filter { pathExclude ->
                allFiles.any { pathExclude.matches(it) }
            }

        val pathExcludes = (importedPathExcludes + existingPathExcludes).distinctBy { it.pattern }

        repositoryConfiguration
            .replacePathExcludes(pathExcludes)
            .sortPathExcludes()
            .prettyPrintAsYaml(repositoryConfigurationFile)

        return 0
    }

    private fun importPathExcludes(): List<PathExclude> {
        println("Analyzing $sourceCodeDir...")
        val repositoryPaths = findRepositoryPaths(sourceCodeDir)
        println("Found ${repositoryPaths.size} repositories in ${repositoryPaths.values.sumBy { it.size }} locations.")

        println("Loading $pathExcludesFile...")
        val pathExcludes: Map<String, List<PathExclude>> = pathExcludesFile.readValue()
        println("Found ${pathExcludes.values.sumBy { it.size }} excludes for ${pathExcludes.size} repositories.")

        val result = mutableListOf<PathExclude>()

        repositoryPaths.forEach { vcsUrl, relativePaths ->
            pathExcludes[vcsUrl]?.let { pathExcludesForRepository ->
                pathExcludesForRepository.forEach { pathExclude ->
                    relativePaths.forEach { path ->
                        result.add(pathExclude.copy(pattern = path + '/' + pathExclude.pattern))
                    }
                }
            }
        }

        return result
    }
}

private fun findAllFiles(dir: File): List<String> {
    val result = mutableListOf<String>()

    dir.walk().forEach { file ->
        if (!file.isDirectory) {
            result.add(file.relativeTo(dir).path)
        }
    }

    return result
}

private fun findRepositoryPaths(dir: File): Map<String, Set<String>> {
    val analyzer = Analyzer(AnalyzerConfiguration(true, true))
    val ortResult = analyzer.analyze(
        absoluteProjectPath = dir,
        packageManagers = emptyList(),
        packageCurationsFile = null,
        repositoryConfigurationFile = null
    )

    val result = mutableMapOf<String, MutableSet<String>>()

    ortResult.repository.nestedRepositories.forEach { path, vcs ->
        result
            .getOrPut(vcs.url) { mutableSetOf() }
            .add(path)
    }

    // TODO: strip the user info from the vcs URLs
    return result
}
