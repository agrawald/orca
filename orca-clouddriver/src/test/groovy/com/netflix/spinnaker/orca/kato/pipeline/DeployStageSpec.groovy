/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.kato.pipeline

import com.netflix.spectator.api.ExtendedRegistry
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.orca.batch.TaskTaskletAdapter
import com.netflix.spinnaker.orca.clouddriver.pipeline.DestroyServerGroupStage
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import com.netflix.spinnaker.orca.kato.pipeline.support.SourceResolver
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.model.PipelineStage
import com.netflix.spinnaker.orca.pipeline.persistence.jedis.JedisExecutionRepository
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory
import org.springframework.batch.core.repository.JobRepository
import org.springframework.context.ApplicationContext
import org.springframework.transaction.PlatformTransactionManager
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.util.Pool
import spock.lang.*

class DeployStageSpec extends Specification {

  @Shared @AutoCleanup("destroy") EmbeddedRedis embeddedRedis

  def setupSpec() {
    embeddedRedis = EmbeddedRedis.embed()
  }

  def cleanup() {
    embeddedRedis.jedis.flushDB()
  }

  Pool<Jedis> jedisPool = new JedisPool("localhost", embeddedRedis.@port)

  def configJson = """\
          {
            "type":"deploy",
            "cluster":{
                "strategy":"IMPLEMENTED_IN_SPEC",
                "application":"pond",
                "stack":"prestaging",
                "instanceType":"m3.medium",
                "securityGroups":[
                  "nf-infrastructure-vpc",
                  "nf-datacenter-vpc"
                ],
                "subnetType":"internal",
                "availabilityZones":{
                  "us-west-1":[

                  ]
                },
                "capacity":{
                  "min":1,
                  "max":1,
                  "desired":1
                },
                "loadBalancers":[
                  "pond-prestaging-frontend"
                ]
            },
            "account":"prod"
          }
  """.stripIndent()

  def mapper = new OrcaObjectMapper()
  def objectMapper = new OrcaObjectMapper()
  def executionRepository = new JedisExecutionRepository(new ExtendedRegistry(new NoopRegistry()), jedisPool, 1, 50)

  @Subject DeployStage deployStage

  @Shared OortService oortService
  @Shared SourceResolver sourceResolver
  @Shared DisableAsgStage disableAsgStage
  @Shared DestroyServerGroupStage destroyServerGroupStage
  @Shared ResizeServerGroupStage resizeServerGroupStage
  @Shared ModifyScalingProcessStage modifyScalingProcessStage

  def setup() {
    sourceResolver = Mock(SourceResolver)
    oortService = Mock(OortService)
    disableAsgStage = Mock(DisableAsgStage)
    destroyServerGroupStage = Mock(DestroyServerGroupStage)
    resizeServerGroupStage = Mock(ResizeServerGroupStage)
    modifyScalingProcessStage = Mock(ModifyScalingProcessStage)

    deployStage = new DeployStage(sourceResolver: sourceResolver, disableAsgStage: disableAsgStage,
                                  destroyServerGroupStage: destroyServerGroupStage,
                                  resizeServerGroupStage: resizeServerGroupStage,
                                  modifyScalingProcessStage: modifyScalingProcessStage, mapper: mapper)
    deployStage.steps = new StepBuilderFactory(Stub(JobRepository), Stub(PlatformTransactionManager))
    deployStage.taskTaskletAdapter = new TaskTaskletAdapter(executionRepository, [])
    deployStage.applicationContext = Stub(ApplicationContext) {
      getBean(_) >> { Class type -> type.newInstance() }
    }
  }

  void "should create stages for deploy and disableAsg when strategy is redblack"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    config.cluster.strategy = "redblack"
    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)
    stage.beforeStages = new NeverClearedArrayList()
    stage.afterStages = new NeverClearedArrayList()

    when:
    deployStage.buildSteps(stage)

    then:
    "should call to oort to get the last ASG so that we know what to disable"
    1 * sourceResolver.getExistingAsgs(config.cluster.application, config.account, "pond-prestaging", "aws") >> {
      [[name: "pond-prestaging-v000", region: "us-west-1"]]
    }
    1 == stage.afterStages.size()
    stage.afterStages[0].stageBuilder == disableAsgStage
  }

  void "should choose the ancestor asg from the same region when redblack is selected"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    config.cluster.strategy = "redblack"
    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)
    stage.beforeStages = new NeverClearedArrayList()
    stage.afterStages = new NeverClearedArrayList()

    when:
    deployStage.buildSteps(stage)

    then:
    "should call to oort to get the last ASG so that we know what to disable"
    1 * sourceResolver.getExistingAsgs(config.cluster.application, config.account, "pond-prestaging", "aws") >> {
      [[name: "pond-prestaging-v000", region: "us-east-1"], [name: "pond-prestaging-v000", region: "us-west-1"]]
    }
    stage.afterStages[0].context.regions == config.availabilityZones.keySet().toList()
  }

  void "should create stages of deploy, resizeAsg and disableAsg when strategy is redblack and scaleDown is true"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    config.cluster.scaleDown = true
    config.cluster.strategy = "redblack"
    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)
    stage.beforeStages = new NeverClearedArrayList()
    stage.afterStages = new NeverClearedArrayList()

    when:
    deployStage.buildSteps(stage)

    then:
    "should call to oort to get the last ASG so that we know what to disable"
    1 * sourceResolver.getExistingAsgs(config.cluster.application, config.account, "pond-prestaging", "aws") >> {
      [[name: "pond-prestaging-v000", region: "us-west-1"]]
    }
    2 == stage.afterStages.size()
    stage.afterStages*.stageBuilder == [resizeServerGroupStage, disableAsgStage]
  }

  void "should create stages of deploy, resizeAsg, disableAsg stages when strategy is redblack and scaleDown is true"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    config.cluster.scaleDown = true
    config.cluster.strategy = "redblack"
    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)
    stage.beforeStages = new NeverClearedArrayList()
    stage.afterStages = new NeverClearedArrayList()

    when:
    deployStage.buildSteps(stage)

    then:
    "should call to oort to get the last ASG so that we know what to disable"
    1 * sourceResolver.getExistingAsgs(config.cluster.application, config.account, "pond-prestaging", "aws") >> {
      [[name: "pond-prestaging-v000", region: "us-west-1"]]
    }
    2 == stage.afterStages.size()
    stage.afterStages*.stageBuilder == [resizeServerGroupStage, disableAsgStage]
  }

  @Unroll
  void "should create stages of deploy, disableAsg, and destroyServerGroup stages when strategy is redblack and maxRemainingAsgs is defined"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    config.cluster.strategy = "redblack"
    config.cluster.maxRemainingAsgs = maxRemainingAsgs

    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)
    stage.beforeStages = new NeverClearedArrayList()
    stage.afterStages = new NeverClearedArrayList()

    when:
    deployStage.buildSteps(stage)

    then:
    "should call to oort to get the last ASG so that we know what to disable"
    1 * sourceResolver.getExistingAsgs(config.cluster.application, config.account, "pond-prestaging", "aws") >> asgs

    and:
    stage.afterStages[0].stageBuilder == disableAsgStage
    1 + calledDestroyAsgNumTimes == stage.afterStages.size()

    and:
    if (calledDestroyAsgNumTimes > 0) {
      def index = 0
      stage.afterStages[1..calledDestroyAsgNumTimes].context.every { it ->
        it == [asgName: asgs.get(index++).name, regions: ["us-west-1"], credentials: config.account]
      }
      stage.afterStages[1..calledDestroyAsgNumTimes].stageBuilder.every { it ->
        it == destroyServerGroupStage
      }
    }

    where:
    asgs                                                                                                                                                            | maxRemainingAsgs | calledDestroyAsgNumTimes
    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | 3                | 1
    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | 2                | 2
    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | 1                | 3
    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | 0                | 0
    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | -1               | 0

    [[name: "pond-prestaging-v300", region: "us-west-1"]]                                                                                                           | 0                | 0
    [[name: "pond-prestaging-v300", region: "us-west-1"]]                                                                                                           | 1                | 1
    [[name: "pond-prestaging-v300", region: "us-west-1"]]                                                                                                           | 2                | 0

    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | 4                | 0
    [[name: "pond-prestaging-v300", region: "us-west-1"], [name: "pond-prestaging-v303", region: "us-west-1"], [name: "pond-prestaging-v304", region: "us-west-1"]] | 5                | 0
  }

  void "should create stages of deploy and destroyServerGroup when strategy is highlander"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    config.cluster.strategy = "highlander"
    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)
    stage.beforeStages = new NeverClearedArrayList()
    stage.afterStages = new NeverClearedArrayList()

    when:
    deployStage.buildSteps(stage)

    then:
    "should call to oort to get the last ASG so that we know what to disable"
    1 * sourceResolver.getExistingAsgs(config.cluster.application, config.account, "pond-prestaging", "aws") >> {
      [[name: "pond-prestaging-v000", region: "us-west-1"]]
    }
    1 == stage.afterStages.size()
    stage.afterStages[0].stageBuilder == destroyServerGroupStage
  }

  void "should create basicDeploy tasks when no strategy is chosen"() {
    setup:
    def pipeline = new Pipeline()
    def config = mapper.readValue(configJson, Map)
    def stage = new PipelineStage(pipeline, config.remove("type") as String, config)

    when:
    def steps = deployStage.buildSteps(stage)

    then:
    steps.size() > 1
    steps[0].name.tokenize('.')[2] == 'determineSourceServerGroup'
    steps.subList(1, steps.size())*.name.collect {
      it.tokenize('.')[1]
    } == deployStage.basicSteps(stage)*.name.collect { it.tokenize('.')[1] }

  }
}
