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

package com.here.ort.analyzer

import com.here.ort.analyzer.managers.BitBake
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.yamlMapper
import com.here.ort.utils.normalizeVcsUrl
import com.here.ort.utils.test.USER_DIR
import com.here.ort.utils.test.patchExpectedResult
import io.kotlintest.specs.WordSpec
import java.io.File

class BitBakeTest : WordSpec() {
//    private val projectsDir = File("/home/hs/code/here/ort_tests/poky")
//    private val vcsDir = VersionControlSystem.forDirectory(projectsDir)!!
//    private val vcsRevision = vcsDir.getRevision()
//    private val vcsUrl = vcsDir.getRemoteUrl()
//
//    init {
//        "BitBake" should {
//            "resolve dependencies correctly" {
//                val definitionFile = File(projectsDir, "lockfile/Gemfile")
//
//                try {
//                    val config = AnalyzerConfiguration(false, false)
//                    val actualResult = BitBake.create(config)
//                            .resolveDependencies(USER_DIR, listOf(definitionFile))[definitionFile]
//                    val expectedResult = patchExpectedResult(
//                            File(projectsDir.parentFile, "bundler-expected-output-lockfile.yml"),
//                            url = normalizeVcsUrl(vcsUrl),
//                            revision = vcsRevision,
//                            path = vcsDir.getPathToRoot(definitionFile.parentFile)
//                    )
//
//                    yamlMapper.writeValueAsString(actualResult) shouldBe expectedResult
//                } finally {
//                    File(definitionFile.parentFile, ".bundle").safeDeleteRecursively()
//                }
//            }
//
//            "show error if no lockfile is present" {
//                val definitionFile = File(projectsDir, "no-lockfile/Gemfile")
//
//                val config = AnalyzerConfiguration(false, false)
//                val actualResult = Bundler.create(config)
//                        .resolveDependencies(USER_DIR, listOf(definitionFile))[definitionFile]
//
//                actualResult shouldNotBe null
//                actualResult!!.project.id shouldBe Identifier
//                        .fromString("Bundler::src/funTest/assets/projects/synthetic/bundler/no-lockfile/Gemfile:")
//                actualResult.project.definitionFilePath shouldBe
//                        "analyzer/src/funTest/assets/projects/synthetic/bundler/no-lockfile/Gemfile"
//                actualResult.packages.size shouldBe 0
//                actualResult.errors.size shouldBe 1
//                actualResult.errors.first() should startWith("IllegalArgumentException: No lockfile found in")
//            }
//
//            "resolve dependencies correctly when the project is a Gem" {
//                val definitionFile = File(projectsDir, "gemspec/Gemfile")
//
//                try {
//                    val config = AnalyzerConfiguration(false, false)
//                    val actualResult = Bundler.create(config)
//                            .resolveDependencies(USER_DIR, listOf(definitionFile))[definitionFile]
//                    val expectedResult = patchExpectedResult(
//                            File(projectsDir.parentFile, "bundler-expected-output-gemspec.yml"),
//                            url = normalizeVcsUrl(vcsUrl),
//                            revision = vcsRevision,
//                            path = vcsDir.getPathToRoot(definitionFile.parentFile)
//                    )
//
//                    yamlMapper.writeValueAsString(actualResult) shouldBe expectedResult
//                } finally {
//                    File(definitionFile.parentFile, ".bundle").safeDeleteRecursively()
//                }
//            }
//        }
//    }
}

