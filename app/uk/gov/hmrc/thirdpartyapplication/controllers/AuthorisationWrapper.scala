/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers

import java.util.UUID

import javax.inject.Inject


import cats.data.OptionT
import cats.implicits._
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.retrieve.EmptyRetrieval
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.APPLICATION_NOT_FOUND
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.{AccessType, PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import play.api.http.HeaderNames.AUTHORIZATION

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.concurrent.Future.{successful, failed}

trait AuthorisationWrapper {
  self: BaseController =>

  implicit def ec: ExecutionContext

  val authConnector: AuthConnector
  val applicationService: ApplicationService
  val authConfig: AuthConfig

  def requiresAuthentication(): ActionBuilder[Request, AnyContent] = Action andThen authenticationAction

  case class OptionalStrideAuthRequest[A](isStrideAuth: Boolean, request: Request[A]) extends WrappedRequest[A](request)

  def strideAuthRefiner(implicit ec: ExecutionContext): ActionRefiner[Request, OptionalStrideAuthRequest] = new ActionRefiner[Request, OptionalStrideAuthRequest] {
    def refine[A](request: Request[A]): Future[Either[Result, OptionalStrideAuthRequest[A]]] = {
      val strideAuthSuccess =
        if (authConfig.enabled && request.headers.get(AUTHORIZATION).isDefined) {
          implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
          val hasAnyGatekeeperEnrolment = Enrolment(authConfig.userRole) or Enrolment(authConfig.superUserRole) or Enrolment(authConfig.adminRole)
          authConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval).map { _ => true }
        } else {
          Future.successful(false)
        }

      strideAuthSuccess.flatMap(isStrideAuthenticated => {
        Future.successful(Right[Result, OptionalStrideAuthRequest[A]](OptionalStrideAuthRequest[A](isStrideAuth = isStrideAuthenticated, request)))
      })
    }

    override protected def executionContext: ExecutionContext = ec
  }

  def requiresAuthenticationFor(accessTypes: AccessType*): ActionBuilder[Request, AnyContent] =
    Action andThen PayloadBasedApplicationTypeFilter(accessTypes.toList)

  def requiresAuthenticationFor(uuid: UUID, accessTypes: AccessType*): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, accessTypes.toList, false)

  def requiresAuthenticationForStandardApplications(uuid: UUID): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, List(STANDARD), true)

  def requiresAuthenticationForPrivilegedOrRopcApplications(uuid: UUID): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, List(PRIVILEGED, ROPC), false)


  private def authenticate[A](input: Request[A]): Future[Option[Result]] = {
    if (authConfig.enabled) {
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(input.headers, None)
      val hasAnyGatekeeperEnrolment = Enrolment(authConfig.userRole) or Enrolment(authConfig.superUserRole) or Enrolment(authConfig.adminRole)
      authConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval).map { _ => None }
    } else {
      Future.successful(None)
    }
  }

  private def authenticationAction(implicit ec: ExecutionContext) = new ActionFilter[Request] {
    def executionContext = ec

    def filter[A](input: Request[A]): Future[Option[Result]] = authenticate(input)
  }

  private case class PayloadBasedApplicationTypeFilter(accessTypes: List[AccessType]) extends ApplicationTypeFilter(accessTypes, false) {
    final protected def deriveAccessType[A](request: Request[A]) =
      Future((Json.parse(request.body.toString) \ "access" \ "accessType").asOpt[AccessType])
  }

  private case class RepositoryBasedApplicationTypeFilter(applicationId: UUID, toMatchAccessTypes: List[AccessType], failOnAccessTypeMismatch: Boolean)
                                                              extends ApplicationTypeFilter(toMatchAccessTypes, failOnAccessTypeMismatch) {
    private def error[A](e: Exception): OptionT[Future,A] = {
      OptionT.liftF(Future.failed(e))
    }

    final protected def deriveAccessType[A](request: Request[A]): Future[Option[AccessType]] =
      applicationService.fetch(applicationId)
        .map(app => app.access.accessType)
        .orElse(error(new NotFoundException(s"application $applicationId doesn't exist")))
        .value
  }

  private abstract class ApplicationTypeFilter(toMatchAccessTypes: List[AccessType], failOnAccessTypeMismatch: Boolean)(implicit ec: ExecutionContext) extends ActionFilter[Request] {
    def executionContext = ec

    lazy val FAILED_ACCESS_TYPE = successful(Some(Results.Forbidden(JsErrorResponse(APPLICATION_NOT_FOUND, "application access type mismatch"))))

    def failedError(e: Throwable) = Some(Results.InternalServerError(JsErrorResponse(APPLICATION_NOT_FOUND, e.getMessage)))

    def filter[A](request: Request[A]): Future[Option[Result]] =
      deriveAccessType(request) flatMap {
        case Some(accessType) if toMatchAccessTypes.contains(accessType) => authenticate(request)
        case Some(_)          if failOnAccessTypeMismatch                => FAILED_ACCESS_TYPE
        case _                                                           => successful(None)
      } recover {
        case NonFatal(e) => failedError(e)
      }

    protected def deriveAccessType[A](request: Request[A]): Future[Option[AccessType]]
  }
}
