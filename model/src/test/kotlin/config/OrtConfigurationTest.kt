/*
 * Copyright (C) 2019 HERE Europe B.V.
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

package org.ossreviewtoolkit.model.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.containExactly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

import java.io.File
import java.lang.IllegalArgumentException

import org.ossreviewtoolkit.utils.ORT_NAME
import org.ossreviewtoolkit.utils.test.containExactly as containExactlyEntries
import org.ossreviewtoolkit.utils.test.shouldNotBeNull

class OrtConfigurationTest : WordSpec({
    "OrtConfiguration" should {
        "be deserializable from HOCON" {
            val refConfig = File("src/test/assets/reference.conf")
            val ortConfig = OrtConfiguration.load(configFile = refConfig)

            ortConfig.scanner shouldNotBeNull {
                archive shouldNotBeNull {
                    patterns should containExactly("LICENSE*", "COPYING*")
                    storage.httpFileStorage.shouldBeNull()
                    storage.localFileStorage shouldNotBeNull {
                        directory shouldBe File("~/.ort/scanner/archive")
                    }
                }

                storages shouldNotBeNull {
                    keys shouldContainExactlyInAnyOrder setOf("local", "http", "clearlyDefined", "postgres")
                    val httpStorage = this["http"]
                    httpStorage.shouldBeInstanceOf<FileBasedStorageConfiguration>()
                    httpStorage.backend.httpFileStorage shouldNotBeNull {
                        url shouldBe "https://your-http-server"
                        headers should containExactlyEntries("key1" to "value1", "key2" to "value2")
                    }

                    val localStorage = this["local"]
                    localStorage.shouldBeInstanceOf<FileBasedStorageConfiguration>()
                    localStorage.backend.localFileStorage shouldNotBeNull {
                        directory shouldBe File("~/.ort/scanner/results")
                    }

                    val postgresStorage = this["postgres"]
                    postgresStorage.shouldBeInstanceOf<PostgresStorageConfiguration>()
                    postgresStorage.url shouldBe "jdbc:postgresql://your-postgresql-server:5444/your-database"
                    postgresStorage.schema shouldBe "schema"
                    postgresStorage.username shouldBe "username"
                    postgresStorage.password shouldBe "password"
                    postgresStorage.sslmode shouldBe "required"
                    postgresStorage.sslcert shouldBe "/defaultdir/postgresql.crt"
                    postgresStorage.sslkey shouldBe "/defaultdir/postgresql.pk8"
                    postgresStorage.sslrootcert shouldBe "/defaultdir/root.crt"

                    val cdStorage = this["clearlyDefined"]
                    cdStorage.shouldBeInstanceOf<ClearlyDefinedStorageConfiguration>()
                    cdStorage.serverUrl shouldBe "https://api.clearlydefined.io"
                }

                options.shouldNotBeNull()
                storageReaders shouldContainExactly listOf("local", "postgres", "http", "clearlyDefined")
                storageWriters shouldContainExactly listOf("postgres")
            }
        }

        "correctly prioritize the sources" {
            val configFile = createTestConfig(
                """
                ort {
                  scanner {
                    storages {
                      postgresStorage {
                        url = "postgresql://your-postgresql-server:5444/your-database"
                        schema = schema
                        username = username
                        password = password
                      }
                    }
                  }
                }
                """.trimIndent()
            )

            val config = OrtConfiguration.load(
                args = mapOf(
                    "ort.scanner.storages.postgresStorage.schema" to "argsSchema",
                    "other.property" to "someValue"
                ),
                configFile = configFile
            )

            config.scanner?.storages shouldNotBeNull {
                val postgresStorage = this["postgresStorage"]
                postgresStorage.shouldBeInstanceOf<PostgresStorageConfiguration>()
                postgresStorage.username shouldBe "username"
                postgresStorage.schema shouldBe "argsSchema"
            }
        }

        "fail for an invalid configuration" {
            val configFile = createTestConfig(
                """
                ort {
                  scanner {
                    storages {
                      foo = baz
                    }
                  }
                }
                """.trimIndent()
            )

            shouldThrow<IllegalArgumentException> {
                OrtConfiguration.load(configFile = configFile)
            }
        }

        "fail for invalid properties in the map with arguments" {
            val file = File("anotherNonExistingConfig.conf")
            val args = mapOf("ort.scanner.storages.new" to "test")

            shouldThrow<IllegalArgumentException> {
                OrtConfiguration.load(configFile = file, args = args)
            }
        }

        "ignore a non-existing configuration file" {
            val args = mapOf("foo" to "bar")
            val file = File("nonExistingConfig.conf")

            val config = OrtConfiguration.load(configFile = file, args = args)

            config.scanner.shouldBeNull()
        }
    }
})

/**
 * Create a test configuration with the [data] specified.
 */
private fun createTestConfig(data: String): File =
    createTempFile(ORT_NAME, ".conf").apply {
        writeText(data)
        deleteOnExit()
    }
