/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.netflix.spinnaker.orca.ExecutionStatus.RUNNING
import com.netflix.spinnaker.orca.StageResolver
import com.netflix.spinnaker.orca.TaskResolver
import com.netflix.spinnaker.orca.api.SimpleStage
import com.netflix.spinnaker.orca.events.TaskStarted
import com.netflix.spinnaker.orca.fixture.pipeline
import com.netflix.spinnaker.orca.fixture.stage
import com.netflix.spinnaker.orca.pipeline.DefaultStageDefinitionBuilderFactory
import com.netflix.spinnaker.orca.pipeline.model.Execution.ExecutionType.PIPELINE
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.orca.q.DummyTask
import com.netflix.spinnaker.orca.q.RunTask
import com.netflix.spinnaker.orca.q.StartTask
import com.netflix.spinnaker.orca.q.buildTasks
import com.netflix.spinnaker.orca.q.singleTaskStage
import com.netflix.spinnaker.q.Queue
import com.netflix.spinnaker.time.fixedClock
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.lifecycle.CachingMode.GROUP
import org.jetbrains.spek.subject.SubjectSpek
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.context.ApplicationEventPublisher

object StartTaskHandlerTest : SubjectSpek<StartTaskHandler>({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val taskResolver = TaskResolver(emptyList())
  val stageResolver = StageResolver(emptyList(), emptyList<SimpleStage<Object>>())
  val clock = fixedClock()

  subject(GROUP) {
    StartTaskHandler(queue, repository, ContextParameterProcessor(), DefaultStageDefinitionBuilderFactory(stageResolver), publisher, taskResolver, clock)
  }

  fun resetMocks() = reset(queue, repository, publisher)

  describe("when a task starts") {
    val pipeline = pipeline {
      stage {
        type = singleTaskStage.type
        singleTaskStage.buildTasks(this)
      }
    }
    val message = StartTask(pipeline.type, pipeline.id, "foo", pipeline.stages.first().id, "1")

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
    }

    afterGroup(::resetMocks)

    action("the handler receives a message") {
      subject.handle(message)
    }

    it("marks the task as running") {
      verify(repository).storeStage(check {
        it.tasks.first().apply {
          assertThat(status).isEqualTo(RUNNING)
          assertThat(startTime).isEqualTo(clock.millis())
        }
      })
    }

    it("runs the task") {
      verify(queue).push(RunTask(
        message.executionType,
        message.executionId,
        "foo",
        message.stageId,
        message.taskId,
        DummyTask::class.java
      ))
    }

    it("publishes an event") {
      argumentCaptor<TaskStarted>().apply {
        verify(publisher).publishEvent(capture())
        firstValue.apply {
          assertThat(executionType).isEqualTo(pipeline.type)
          assertThat(executionId).isEqualTo(pipeline.id)
          assertThat(stageId).isEqualTo(message.stageId)
          assertThat(taskId).isEqualTo(message.taskId)
        }
      }
    }
  }

  describe("when the execution repository has a problem") {
    val pipeline = pipeline {
      stage {
        type = singleTaskStage.type
        singleTaskStage.buildTasks(this)
      }
    }
    val message = StartTask(pipeline.type, pipeline.id, "foo", pipeline.stages.first().id, "1")

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message.executionId)) doThrow NullPointerException()
    }

    afterGroup(::resetMocks)

    it("propagates any exception") {
      assertThrows(NullPointerException::class.java) {
        subject.handle(message)
      }
    }
  }
})
