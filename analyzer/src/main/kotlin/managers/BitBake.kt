/*
 * Copyright (C) 2019 HERE Europe B.V.
 * Copyright (C) 2019 Verifa Oy
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

package com.here.ort.analyzer.managers

//import ch.frankel.slf4k.debug
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.here.ort.analyzer.AbstractPackageManagerFactory
import com.here.ort.analyzer.PackageManager
import com.here.ort.analyzer.PackageManagerFactory
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.*
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log
import com.here.ort.utils.textValueOrEmpty
import com.paypal.digraph.parser.GraphEdge
import com.paypal.digraph.parser.GraphNode
import com.paypal.digraph.parser.GraphParser

import java.io.File
import java.io.FileInputStream
import java.util.*
import java.util.Collections.emptySortedSet

open class BitBake(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    companion object {
        private const val BITBAKE_URL = "https://github.com/openembedded/bitbake"
        private const val NA = "n/a"
        //private val SOURCE_FILE_NAME = listOf("oe-init-build-env")
    }
    /*companion object : PackageManagerFactory<BitBake>(
            "https://github.com/openembedded/bitbake",
            "n/a",
            listOf("oe-init-build-env")
    )*/
    class Factory : AbstractPackageManagerFactory<BitBake>("BitBake") {
        override val globsForDefinitionFiles = listOf("oe-init-build-env")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = BitBake(managerName, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = "bitbake"

    override fun toString() = BitBake.toString()

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        val workingDir = definitionFile.parentFile
        //val bbPath = initBuildEnvironment(definitionFile)

        val parser = GraphParser( FileInputStream(workingDir.absolutePath.toString() + "\\package-depends-minimal.dot") );

        val nodes = parser.getNodes()!!;
        val edges = parser.getEdges()!!;

        log.info("--- nodes:");
        for ( node in nodes) {
            log.info(node);
        }

        log.info("--- edges:");

        for (edge in edges) {
            log.info(edge);
        }

        /*
        val scriptFile = File(bbPath, "find_packages.py")
        scriptFile.writeBytes(javaClass.classLoader.getResource("find_packages.py").readBytes())
        val scriptCmd = ProcessCapture(
                bbPath,
          //      mapOf("BBPATH" to bbPath.absolutePath),
                "python3",
                scriptFile.absolutePath,
                // FIXME: add a list of bitbake recipes to query in AnalyzerConfiguration
                "gdb"
        )

        val errors = mutableListOf<Error>()
        val packages = mutableMapOf<String, Package>()
        val packageReferences = mutableSetOf<PackageReference>()

        jsonMapper.readValue<JsonNode>(scriptCmd.requireSuccess().stdout).forEach {
            parseDependency(it, packages, packageReferences, errors)
        }



        return ProjectAnalyzerResult(
            project = Project(
                id = Identifier(toString(), "", "FIXME", "23.42"),
                definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
                declaredLicenses = emptySet<String>().toSortedSet(),
                vcs = VcsInfo.EMPTY,
                vcsProcessed = processProjectVcs(workingDir),
                homepageUrl = "",
                scopes = sortedSetOf(Scope("default", packageReferences.toSortedSet()))
            ),
            packages = packages.values.map { it.toCuratedPackage() }.toSortedSet()
        )*/

        return ProjectAnalyzerResult(
            project = Project(
                id = Identifier("ID"),
                definitionFilePath = "",
                declaredLicenses = emptySet<String>().toSortedSet(),
                vcs = VcsInfo.EMPTY,
                vcsProcessed = processProjectVcs(workingDir),
                homepageUrl = "",
                scopes = emptySortedSet<Scope>()
            ),
            packages = emptySortedSet<CuratedPackage>()
        )


    }

    // TODO: error handling
    private fun parseDependency(node: JsonNode, packages: MutableMap<String, Package>,
                                scopeDependencies: MutableSet<PackageReference>,
                                errors: MutableList<Error>) {

        val name = node["name"].textValue()
        log.debug { "Parsing recipe '$name'." }

        val pkg = packages[name] ?: addPackage(name, node, packages)
        val transitiveDependencies = mutableSetOf<PackageReference>()

        node["dependencies"].forEach {
            parseDependency(it, packages, transitiveDependencies, errors)
        }

        scopeDependencies += PackageReference(
            id = pkg.id,
            dependencies = transitiveDependencies.toSortedSet()
        )
    }

    private fun addPackage(name: String, node: JsonNode, packages: MutableMap<String, Package>): Package {
        // TODO: Figure out what to do about patch files and lack of hashing.
        val srcUri = node["src_uri"].toList().let {
            if (it.isEmpty())
                ""
            else
                it.first().textValueOrEmpty()
        }
        val sourceArtifact = RemoteArtifact(url = srcUri, hash = Hash.NONE) //, hashAlgorithm = HashAlgorithm.UNKNOWN)

        val pkg = Package(
                id = Identifier(
                    type = toString(),
                    namespace = "",
                    name = name,
                    version = node["version"].textValueOrEmpty()
                ),
                declaredLicenses = sortedSetOf(node["license"].textValueOrEmpty()),
                description = node["description"].textValueOrEmpty(),
                homepageUrl = node["homepage"].textValueOrEmpty(),
                binaryArtifact = RemoteArtifact.EMPTY,
                sourceArtifact = sourceArtifact,
                vcs = VcsInfo.EMPTY,
                vcsProcessed = VcsInfo.EMPTY
        )

        packages[name] = pkg
        return pkg
    }

    private fun initBuildEnvironment(definitionFile: File): File {
        val pwd = definitionFile.parentFile
        val buildDir = File(pwd, "build").absolutePath

        val bashCmd = ProcessCapture(
                pwd,
                "/bin/bash",
                "-c",
                "'set $buildDir && . ./${definitionFile.name} $buildDir'" // > /dev/null; echo \$BBPATH'"
        )

        return File(bashCmd.requireSuccess().stdout)
    }
}
