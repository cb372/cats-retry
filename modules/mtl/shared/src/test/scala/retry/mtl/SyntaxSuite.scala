package retry.mtl

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.mtl.Raise
import cats.syntax.all.*
import munit.CatsEffectSuite
import retry.{ErrorHandler, RetryDetails, ResultHandler, RetryPolicies}
import retry.RetryDetails.NextStep.DelayAndRetry
import retry.HandlerDecision.Continue
import retry.mtl.syntax.*
import retry.syntax.*

import scala.concurrent.duration.*

class SyntaxSuite extends CatsEffectSuite:
  private case class AppError(msg: String)

  private type Effect[A] = EitherT[IO, AppError, A]

  private val R: Raise[Effect, AppError] = summon

  private val oneMoreTimeException = RuntimeException("one more time")
  private val oneMoreTimeError     = AppError("one more time")

  private case class State(
      attempts: Int = 0,
      errors: Vector[Throwable] = Vector.empty,
      appErrors: Vector[AppError] = Vector.empty,
      retryCounts: Vector[Int] = Vector.empty,
      nextSteps: Vector[RetryDetails.NextStep] = Vector.empty
  )

  private class Fixture(stateRef: Ref[IO, State]):
    def incrementAttempts(): Effect[Unit] = EitherT.liftF(
      stateRef.update(state => state.copy(attempts = state.attempts + 1))
    )

    def getAttempts: Effect[Int] = EitherT.liftF(
      getState.map(_.attempts)
    )

    def onError(
        error: Throwable,
        details: RetryDetails
    ): Effect[Unit] = EitherT.liftF {
      stateRef.update { state =>
        state.copy(
          errors = state.errors :+ error,
          retryCounts = state.retryCounts :+ details.retriesSoFar,
          nextSteps = state.nextSteps :+ details.nextStepIfUnsuccessful
        )
      }
    }

    def onAppError(
        error: AppError,
        details: RetryDetails
    ): Effect[Unit] = EitherT.liftF {
      stateRef.update { state =>
        state.copy(
          appErrors = state.appErrors :+ error,
          retryCounts = state.retryCounts :+ details.retriesSoFar,
          nextSteps = state.nextSteps :+ details.nextStepIfUnsuccessful
        )
      }
    }

    def getState: IO[State] = stateRef.get
  end Fixture

  private val mkFixture: IO[Fixture] = Ref.of[IO, State](State()).map(new Fixture(_))

  test("retryingOnMtlErrors - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[Effect](1.milli)

    // AND an error handler that retries on all errors
    def mkHandler(fixture: Fixture): ResultHandler[Effect, AppError, String] =
      (error: AppError, retryDetails: RetryDetails) =>
        fixture
          .onAppError(error, retryDetails)
          .as(Continue)

    // AND an action that raises an error twice and then succeeds
    def mkAction(fixture: Fixture): Effect[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap { attempts =>
        if attempts < 3 then R.raise(oneMoreTimeError)
        else EitherT.pure("yay")
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture
      action = mkAction(fixture)
      finalResult <- action
        .retryingOnMtlErrors(
          policy,
          mkHandler(fixture)
        )
        .value
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, Right("yay"))
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the error was passed to the handler each time
      assertEquals(state.appErrors, Vector.fill(2)(oneMoreTimeError))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnMtlErrors and retryingOnErrors syntax can be chained") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[Effect](1.milli)

    // AND an MTL error handler that retries on all errors
    def mkMtlHandler(fixture: Fixture): ResultHandler[Effect, AppError, String] =
      (error: AppError, retryDetails: RetryDetails) =>
        fixture
          .onAppError(error, retryDetails)
          .as(Continue)

    // AND an exception handler that retries on all errors
    def mkErrorHandler(fixture: Fixture): ErrorHandler[Effect, String] =
      (error: Throwable, retryDetails: RetryDetails) =>
        fixture
          .onError(error, retryDetails)
          .as(Continue)

    // AND an action that first raises an exception, then raises an AppError, and then succeeds
    def mkAction(fixture: Fixture): Effect[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap {
        case 1 => EitherT.liftF(IO.raiseError(oneMoreTimeException))
        case 2 => R.raise(AppError("Boom!"))
        case _ => EitherT.pure("yay")
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture
      action = mkAction(fixture)
      finalResult <- action
        .retryingOnErrors(policy, mkErrorHandler(fixture))
        .retryingOnMtlErrors(policy, mkMtlHandler(fixture))
        .value
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, Right("yay"))
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the exception was passed to the exception handler
      assertEquals(state.errors, Vector(oneMoreTimeException))
      // AND the AppError was passed to the MTL error handler
      assertEquals(state.appErrors, Vector(AppError("Boom!")))
      // AND the retry count passed to the handler is zero both times,
      // because the two retry flows are not aware of each other
      assertEquals(state.retryCounts, Vector(0, 0))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }
end SyntaxSuite
