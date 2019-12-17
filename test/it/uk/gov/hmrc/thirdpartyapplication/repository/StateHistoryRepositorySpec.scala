/*
 * Copyright 2019 HM Revenue & Customs
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
 */

package it.uk.gov.hmrc.thirdpartyapplication.repository

import java.util.UUID

import org.scalatest.concurrent.Eventually
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.thirdpartyapplication.models.{Actor, ActorType, State, StateHistory}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global

class StateHistoryRepositorySpec extends UnitSpec with MongoSpecSupport with IndexVerification
  with BeforeAndAfterEach with BeforeAndAfterAll with MockitoSugar with ArgumentMatchersSugar with Eventually {

  private val reactiveMongoComponent = new ReactiveMongoComponent { override def mongoConnector: MongoConnector = mongoConnectorForTest }

  private val repository = new StateHistoryRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(repository.drop)
    await(repository.ensureIndexes)
  }

  override protected def afterEach() {
    await(repository.drop)
  }

  val actor = Actor("admin@example.com", ActorType.COLLABORATOR)

  "insert" should {

    "Save a state history" in {

      val stateHistory = StateHistory(UUID.randomUUID(), State.TESTING, actor)

      val result = await(repository.insert(stateHistory))

      result shouldBe stateHistory
      val savedStateHistories = await(repository.findAll())
      savedStateHistories shouldBe Seq(stateHistory)
    }
  }

  "fetchByApplicationId" should {

    "Return the state history of the application" in {

      val applicationId = UUID.randomUUID()
      val stateHistory = StateHistory(applicationId, State.TESTING, actor)
      val anotherAppStateHistory = StateHistory(UUID.randomUUID(), State.TESTING, actor)
      await(repository.insert(stateHistory))
      await(repository.insert(anotherAppStateHistory))

      val result = await(repository.fetchByApplicationId(applicationId))

      result shouldBe Seq(stateHistory)
    }
  }

  "fetchByState" should {

    "Return the state history of the application" in {

      val applicationId = UUID.randomUUID()
      val pendingHistory1 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = DateTimeUtils.now.minusDays(5))
      val approvedHistory = StateHistory(applicationId, State.PENDING_REQUESTER_VERIFICATION, actor)
      val pendingHistory2 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor)
      val pendingHistory3 = StateHistory(UUID.randomUUID(), State.PENDING_GATEKEEPER_APPROVAL, actor)

      await(repository.insert(pendingHistory1))
      await(repository.insert(approvedHistory))
      await(repository.insert(pendingHistory2))
      await(repository.insert(pendingHistory3))

      val result = await(repository.fetchByState(State.PENDING_GATEKEEPER_APPROVAL))

      result shouldBe Seq(pendingHistory1, pendingHistory2, pendingHistory3)
    }
  }

  "fetchLatestByApplicationIdAndState" should {

    "Return the state history of the application" in {

      val applicationId = UUID.randomUUID()
      val pendingHistory1 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor, changedAt = DateTimeUtils.now.minusDays(5))
      val approvedHistory = StateHistory(applicationId, State.PENDING_REQUESTER_VERIFICATION, actor)
      val pendingHistory2 = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, actor)
      val pendingHistory3 = StateHistory(UUID.randomUUID(), State.PENDING_GATEKEEPER_APPROVAL, actor)

      await(repository.insert(pendingHistory1))
      await(repository.insert(approvedHistory))
      await(repository.insert(pendingHistory2))
      await(repository.insert(pendingHistory3))

      val result = await(repository.fetchLatestByStateForApplication(applicationId, State.PENDING_GATEKEEPER_APPROVAL))

      result shouldBe Some(pendingHistory2)
    }
  }

  "deleteByApplicationId" should {

    "Delete the state histories of the application" in {

      val applicationId = UUID.randomUUID()
      val stateHistory = StateHistory(applicationId, State.TESTING, actor)
      val anotherAppStateHistory = StateHistory(UUID.randomUUID(), State.TESTING, actor)
      await(repository.insert(stateHistory))
      await(repository.insert(anotherAppStateHistory))

      await(repository.deleteByApplicationId(applicationId))

      await(repository.findAll()) shouldBe Seq(anotherAppStateHistory)
    }
  }

  "The 'stateHistory' collection" should {
    "have all the indexes" in {
      val expectedIndexes = Set(
        Index(key = Seq("state" -> Ascending), name = Some("state"), unique = false, background = true),
        Index(key = Seq("applicationId" -> Ascending), name = Some("applicationId"), unique = false, background = true),
        Index(key = Seq("applicationId" -> Ascending, "state" -> Ascending), name = Some("applicationId_state"), unique = false, background = true),
        Index(key = Seq("_id" -> Ascending), name = Some("_id_"), unique = false, background = false))

      verifyIndexesVersionAgnostic(repository, expectedIndexes)
    }
  }

}
