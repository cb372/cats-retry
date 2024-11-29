package retry.mtl

import cats.data.EitherT
import munit.CatsEffectSuite
import retry.{RetryDetails, RetryPolicies}

import scala.concurrent.duration.*
import cats.effect.IO
import cats.effect.kernel.Ref

class PackageObjectSuite extends CatsEffectSuite:

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

  test("retryingOnSomeErrors - retry until the action succeeds") {

    val policy = RetryPolicies.constantDelay[Effect](1.second)

    val isWorthRetrying: AppError => Effect[Boolean] =
      s => EitherT.pure(s == AppError("one more time"))

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap { attempts =>
        if attempts < 3 then EitherT.leftT(AppError("one more time"))
        else EitherT.pure("yay")
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnSomeErrors(policy, isWorthRetrying, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Right("yay"))
      assertEquals(state.attempts, 3)
      assertEquals(state.appErrors.toList, List(AppError("one more time"), AppError("one more time")))
      assertEquals(state.delays.toList, List(1.second, 1.second))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnSomeErrors - retry only if the error is worth retrying") {
    val policy = RetryPolicies.limitRetries[Effect](2)

    val isWorthRetrying: AppError => Effect[Boolean] =
      s => EitherT.pure(s == AppError("one more time"))

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap { attempts =>
        if attempts < 3 then EitherT.leftT(AppError("one more time"))
        else EitherT.leftT(AppError("nope"))
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnSomeErrors(policy, isWorthRetrying, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("nope")))
      assertEquals(state.attempts, 3)
      assertEquals(state.appErrors.toList, List(AppError("one more time"), AppError("one more time")))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnSomeErrors - retry until the policy chooses to give up") {

    val policy = RetryPolicies.limitRetries[Effect](2)

    val isWorthRetrying: AppError => Effect[Boolean] =
      s => EitherT.pure(s == AppError("one more time"))

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts()).flatMap { _ =>
        EitherT.leftT(AppError("one more time"))
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnSomeErrors(policy, isWorthRetrying, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("one more time")))
      assertEquals(state.attempts, 3)
      assertEquals(
        state.appErrors.toList,
        List(AppError("one more time"), AppError("one more time"), AppError("one more time"))
      )
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnSomeErrors - retry in a stack-safe way") {

    val policy = RetryPolicies.limitRetries[Effect](10_000)

    val isWorthRetrying: AppError => Effect[Boolean] =
      s => EitherT.pure(s == AppError("one more time"))

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts()).flatMap { _ =>
        EitherT.leftT(AppError("one more time"))
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnSomeErrors(policy, isWorthRetrying, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("one more time")))
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnAllErrors - retry until the action succeeds") {

    val policy = RetryPolicies.constantDelay[Effect](1.second)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts() >> fixture.getAttempts).flatMap { attempts =>
        if attempts < 3 then EitherT.leftT(AppError("one more time"))
        else EitherT.pure("yay")
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnAllErrors(policy, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Right("yay"))
      assertEquals(state.attempts, 3)
      assertEquals(state.appErrors.toList, List(AppError("one more time"), AppError("one more time")))
      assertEquals(state.delays.toList, List(1.second, 1.second))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnAllErrors - retry until the policy chooses to give up") {

    val policy = RetryPolicies.limitRetries[Effect](2)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts()).flatMap { _ =>
        EitherT.leftT(AppError("one more time"))
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnAllErrors(policy, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("one more time")))
      assertEquals(state.attempts, 3)
      assertEquals(
        state.appErrors.toList,
        List(AppError("one more time"), AppError("one more time"), AppError("one more time"))
      )
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnAllErrors - retry in a stack-safe way") {
    val policy = RetryPolicies.limitRetries[Effect](10_000)

    def action(fixture: Fixture): Effect[String] =
      EitherT.liftF(fixture.incrementAttempts()).flatMap { _ =>
        EitherT.leftT(AppError("one more time"))
      }

    for
      fixture     <- mkFixture
      finalResult <- retryingOnAllErrors(policy, fixture.onMtlError)(action(fixture)).value
      state       <- fixture.getState
    yield
      assertEquals(finalResult, Left(AppError("one more time")))
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, true)
  }
end PackageObjectSuite
