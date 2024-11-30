package retry.mtl

import cats.data.EitherT
import munit.CatsEffectSuite
import retry.syntax.all.*
import retry.mtl.syntax.all.*
import retry.{RetryDetails, RetryPolicies, RetryPolicy}

import scala.concurrent.duration.*
import cats.effect.IO
import cats.effect.kernel.Ref

class SyntaxSuite extends CatsEffectSuite:
  case class AppError(msg: String)
  type Effect[A] = EitherT[IO, AppError, A]

  private case class State(
      attempts: Int = 0,
      errors: Vector[String] = Vector.empty,
      appErrors: Vector[AppError] = Vector.empty,
      delays: Vector[FiniteDuration] = Vector.empty,
      gaveUp: Boolean = false
  )

  private class Fixture(stateRef: Ref[IO, State]):
    def incrementAttempts(): IO[Unit] =
      stateRef.update(state => state.copy(attempts = state.attempts + 1))

    def onError(error: Throwable, details: RetryDetails): Effect[Unit] =
      EitherT.liftF {
        stateRef.update { state =>
          details match
            case RetryDetails.WillDelayAndRetry(delay, _, _) =>
              state.copy(
                errors = state.errors :+ error.getMessage,
                delays = state.delays :+ delay
              )
            case RetryDetails.GivingUp(_, _) =>
              state.copy(
                errors = state.errors :+ error.getMessage,
                gaveUp = true
              )
        }
      }

    def onMtlError(
        error: AppError,
        details: RetryDetails
    ): Effect[Unit] = EitherT.liftF {
      stateRef.update { state =>
        details match
          case RetryDetails.WillDelayAndRetry(delay, _, _) =>
            state.copy(
              appErrors = state.appErrors :+ error,
              delays = state.delays :+ delay
            )
          case RetryDetails.GivingUp(_, _) =>
            state.copy(
              appErrors = state.appErrors :+ error,
              gaveUp = true
            )
      }
    }

    def getState: IO[State]  = stateRef.get
    def getAttempts: IO[Int] = getState.map(_.attempts)
  end Fixture

  private val mkFixture: IO[Fixture] = Ref.of[IO, State](State()).map(new Fixture(_))

  test("retryingOnSomeMtlErrors - retry until the action succeeds") {

    val policy: RetryPolicy[Effect] = RetryPolicies.constantDelay[Effect](1.milli)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap {
        case 1 => EitherT.liftF(IO.raiseError(new RuntimeException("one more time")))
        case 2 => EitherT.leftT(AppError("Boom!"))
        case _ => EitherT.pure("yay")
      }

    def isWorthRetrying(e: Throwable): Effect[Boolean]        = EitherT.pure(e.getMessage == "one more time")
    def isAppErrorWorthRetrying(s: AppError): Effect[Boolean] = EitherT.pure(s == AppError("Boom!"))

    for
      fixture <- mkFixture
      finalResult <- action(fixture)
        .retryingOnSomeErrors(isWorthRetrying, policy, fixture.onError)
        .retryingOnSomeMtlErrors[AppError](
          isAppErrorWorthRetrying,
          policy,
          fixture.onMtlError
        )
        .value
      state <- fixture.getState
    yield
      assertEquals(finalResult, Right("yay"))
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time"))
      assertEquals(state.appErrors.toList, List(AppError("Boom!")))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnSomeMtlErrors - retry only if the error is worth retrying") {

    val policy: RetryPolicy[Effect] = RetryPolicies.constantDelay[Effect](1.milli)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap {
        case 1 => EitherT.liftF(IO.raiseError(new RuntimeException("one more time")))
        case 2 => EitherT.leftT(AppError("Boom!"))
        case _ => EitherT.leftT(AppError("nope"))
      }

    def isWorthRetrying(e: Throwable): Effect[Boolean]        = EitherT.pure(e.getMessage == "one more time")
    def isAppErrorWorthRetrying(s: AppError): Effect[Boolean] = EitherT.pure(s == AppError("Boom!"))

    for
      fixture <- mkFixture
      finalResult <- action(fixture)
        .retryingOnSomeErrors(isWorthRetrying, policy, fixture.onError)
        .retryingOnSomeMtlErrors[AppError](
          isAppErrorWorthRetrying,
          policy,
          fixture.onMtlError
        )
        .value
      state <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("nope")))
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time"))
      assertEquals(state.appErrors.toList, List(AppError("Boom!")))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnSomeMtlErrors - retry until the policy chooses to give up") {
    val policy: RetryPolicy[Effect] = RetryPolicies.limitRetries[Effect](2)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap {
        case 1 => EitherT.leftT(AppError("Boom!"))
        case 2 => EitherT.liftF(IO.raiseError(new RuntimeException("one more time")))
        case _ => EitherT.leftT(AppError("Boom!"))
      }

    def isWorthRetrying(e: Throwable): Effect[Boolean]        = EitherT.pure(e.getMessage == "one more time")
    def isAppErrorWorthRetrying(s: AppError): Effect[Boolean] = EitherT.pure(s == AppError("Boom!"))

    for
      fixture <- mkFixture
      finalResult <- action(fixture)
        .retryingOnSomeErrors(isWorthRetrying, policy, fixture.onError)
        .retryingOnSomeMtlErrors[AppError](
          isAppErrorWorthRetrying,
          policy,
          fixture.onMtlError
        )
        .value
      state <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("Boom!")))
      assertEquals(state.attempts, 4)
      assertEquals(state.errors.toList, List("one more time"))
      assertEquals(state.appErrors.toList, List(AppError("Boom!"), AppError("Boom!"), AppError("Boom!")))
      assertEquals(state.gaveUp, true)

  }

  test("retryingOnAllMtlErrors - retry until the action succeeds") {

    val policy: RetryPolicy[Effect] = RetryPolicies.constantDelay[Effect](1.milli)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap {
        case 1 => EitherT.liftF(IO.raiseError(new RuntimeException("one more time")))
        case 2 => EitherT.leftT(AppError("Boom!"))
        case _ => EitherT.pure("yay")
      }

    for
      fixture <- mkFixture
      finalResult <- action(fixture)
        .retryingOnAllErrors(policy, fixture.onError)
        .retryingOnAllMtlErrors(policy, (e, rd) => fixture.onMtlError(e, rd))
        .value
      state <- fixture.getState
    yield
      assertEquals(finalResult, Right("yay"))
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time"))
      assertEquals(state.appErrors.toList, List(AppError("Boom!")))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnAllMtlErrors - retry until the policy chooses to give up") {
    val policy: RetryPolicy[Effect] = RetryPolicies.limitRetries[Effect](2)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap {
        case 1 => EitherT.leftT(AppError("Boom!"))
        case 2 => EitherT.liftF(IO.raiseError(new RuntimeException("one more time")))
        case _ => EitherT.leftT(AppError("Boom!"))
      }
    for
      fixture <- mkFixture
      finalResult <- action(fixture)
        .retryingOnAllErrors(policy, fixture.onError)
        .retryingOnAllMtlErrors[AppError](
          policy,
          fixture.onMtlError
        )
        .value
      state <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("Boom!")))
      assertEquals(state.attempts, 4)
      assertEquals(state.errors.toList, List("one more time"))
      assertEquals(state.appErrors.toList, List(AppError("Boom!"), AppError("Boom!"), AppError("Boom!")))
      assertEquals(state.gaveUp, true)
  }
end SyntaxSuite
