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

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

import com.here.ort.CommandWithHelp
import com.here.ort.model.*
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY

import java.io.File
import java.lang.Exception

@Parameters(
    commandNames = ["list-licenses"],
    commandDescription = "Sort entries of the repository configuration alphabetically."
)
internal class ListLicensesCommand : CommandWithHelp() {
    @Parameter(
        names = ["--ort-result-file"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY
    )
    private lateinit var ortResultFile: File

    @Parameter(
        names = ["--source-code-dir"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY
    )
    private lateinit var sourceCodeDir: File

    @Parameter(
        names = ["--package-id"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY,
        converter = IdentifierConverter::class
    )
    private lateinit var packageId: Identifier

    @Parameter(
        names = ["--only-offending"],
        required = false,
        order = PARAMETER_ORDER_MANDATORY
    )
    private var onlyOffending: Boolean = false

    override fun runCommand(jc: JCommander): Int {
        val ortResult = ortResultFile.readValue<OrtResult>()

        val offendingLicenses = ortResult.getOffendingLicensesById(packageId, Severity.ERROR)
        var licenseFindings = ortResult
            .getLicenseFindingsById(packageId)
            .filter {
                !onlyOffending || offendingLicenses.contains(it.key)
            }
            .mapValues {
                it.value.groupByText(sourceCodeDir)
            }
            .toSortedMap()

        val result = buildString {
            licenseFindings.forEach { (license, textLocationGroups) ->
                appendln("  $license:")

                textLocationGroups
                    .sortedBy { it.locations.size }
                    .forEachIndexed { i, group ->
                        group.locations.forEach {
                            appendln("    [$i] ${it.path}:${it.startLine}-${it.endLine}")
                        }
                    }
            }
        }

        println(result)

        return 0
    }
}

private fun OrtResult.getOffendingLicensesById(id: Identifier, minSeverity: Severity): Set<String> =
        getRuleViolations()
            .filter {
                it.pkg == id && it.severity.ordinal <= minSeverity.ordinal
            }
            .mapNotNull { it.license }.toSet()

private fun OrtResult.getLicenseFindingsById(id: Identifier): Map<String, Set<TextLocation>> {
    val result = mutableMapOf<String, MutableSet<TextLocation>>()

    val pkg = getPackageOrProject(id)!!
    scanner?.results?.scanResults?.forEach { container ->
        container.results.forEach { scanResult ->
            if (scanResult.provenance.matches(pkg)) {
                scanResult.summary.licenseFindings.forEach {
                    val locations = result.getOrPut(it.license, { mutableSetOf() } )
                    locations.addAll(it.locations)
                }
            }

        }
    }

    return result
}

private fun OrtResult.getPackageOrProject(id: Identifier): Package? =
    getProject(id)?.let { it.toPackage() } ?: getPackage(id)?.pkg

data class TextLocationGroup(
    val locations: Set<TextLocation>,
    val text: String? = null
)

private fun Collection<TextLocation>.groupByText(baseDir: File): List<TextLocationGroup> {
    val map = mutableMapOf<String, MutableSet<TextLocation>>()

    forEach { textLocation ->
        val text = textLocation.resolve(baseDir)
        if (text == null) {
            println("Could not resolve: ${textLocation.path}:${textLocation.startLine}-${textLocation.endLine}")

            return map { TextLocationGroup(locations = setOf(it)) }
        }

        map.getOrPut(text, { mutableSetOf() }).add(textLocation)
    }

    return map.map { (text, locations) ->
        TextLocationGroup(
            locations = locations,
            text = text
        )
    }
}

private fun TextLocation.resolve(baseDir: File): String? =
    try {
        baseDir
            .resolve(path)
            .readText()
            .lines()
            .subList(startLine - 1, endLine)
            .joinToString( separator = "\n" )
    } catch (e: Exception) { null }


class IdentifierConverter : IStringConverter<Identifier> {
    override fun convert(value: String?): Identifier {
        return Identifier(value!!)
    }
}
