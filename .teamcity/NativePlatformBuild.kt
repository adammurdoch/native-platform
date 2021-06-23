/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import jetbrains.buildServer.configs.kotlin.v2019_2.BuildType
import jetbrains.buildServer.configs.kotlin.v2019_2.DslContext
import jetbrains.buildServer.configs.kotlin.v2019_2.FailureAction
import jetbrains.buildServer.configs.kotlin.v2019_2.RelativeId
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.vcs

open class NativePlatformBuild(agent: Agent, init: BuildType.() -> Unit = {}) : BuildType({
    name = "Test on $agent"
    id = RelativeId("Build$agent")

    runOn(agent)

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        gradle {
            tasks = "clean ${if (agent == Agent.UbuntuAmd64) "testDistributed" else "build"}${agent.allPublishTasks}"
            buildFile = ""
        }
    }

    features {
        publishCommitStatus()
        lowerRequiredFreeDiskSpace()
    }

    failureConditions {
        testFailure = false
        executionTimeoutMin = 15
    }

    artifactRules = """
        hs_err*
        **/build/**/output.txt
        build/repo => repo
    """.trimIndent() + "\n$archiveReports"

    init(this)
})

class BuildTrigger(dependencies: List<BuildType>) : BuildType({
    name = "Run platform tests (Trigger)"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = """
                +:*
                -:pull/*
            """.trimIndent()
        }
    }

    dependencies {
        dependencies.forEach {
            snapshot(it) {
                onDependencyFailure = FailureAction.FAIL_TO_START
            }
        }
    }

    runOn(Agent.UbuntuAmd64)

    features {
        publishCommitStatus()
    }
})
