package retry.mtl

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.mtl.Raise
import cats.syntax.all.*
import munit.CatsEffectSuite
import retry.{RetryDetails, ResultHandler, RetryPolicies}
import retry.RetryDetails.NextStep.{GiveUp, DelayAndRetry}
import retry.HandlerDecision.{Stop, Continue, Adapt}

import scala.concurrent.duration.*

class CombinatorsSuite extends CatsEffectSuite:

  private case class AppError(msg: String)

  private type Effect[A] = EitherT[IO, AppError, A]

  private val R: Raise[Effect, AppError] = summon

  private val oneMoreTimeError      = AppError("one more time")
  private val notWorthRetryingError = AppError("not worth retrying")

  private case class State(
      attempts: Int = 0,
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

    def updateState(
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

  private val mkFixture: IO[Fixture] = Ref.of[IO, State](State()).map(new Fixture(_))

  test("retryingOnErrors - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[Effect](1.milli)

    // AND an error handler that retries on all errors
    def mkHandler(fixture: Fixture): ResultHandler[Effect, AppError, String] =
      (error: AppError, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
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
      finalResult <- retryingOnErrors(action)(
        policy,
        mkHandler(fixture)
      ).value
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

  test("retryingOnErrors - retry until the policy chooses to give up") {
    // GIVEN a retry policy that will retry twice and then give up
    val policy = RetryPolicies.limitRetries[Effect](2)

    // AND a result handler that retries on all errors
    def mkHandler(fixture: Fixture): ResultHandler[Effect, AppError, String] =
      (error: AppError, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
          .as(Continue)

    // AND an action that always raises an error
    def mkAction(fixture: Fixture): Effect[String] =
      fixture.incrementAttempts() >> R.raise(oneMoreTimeError)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture
      action = mkAction(fixture)
      finalResult <- retryingOnErrors(action)(
        policy,
        mkHandler(fixture)
      ).value
      state <- fixture.getState
    yield
      // THEN the final error is raised
      assertEquals(finalResult, Left(oneMoreTimeError))
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's error was passed to the handler each time
      assertEquals(state.appErrors, Vector.fill(3)(oneMoreTimeError))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector(DelayAndRetry(0.milli), DelayAndRetry(0.milli), GiveUp))
  }

  test("retryingOnErrors - give up if the handler says the error is not worth retrying") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[Effect](1.milli)

    // AND a result handler that retries on some errors but gives up on others
    def mkHandler(fixture: Fixture): ResultHandler[Effect, AppError, String] =
      (error: AppError, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
          .as {
            error match
              case `oneMoreTimeError` => Continue
              case _                  => Stop
          }

    // AND an action that raises a retryable error followed by a non-retryable error
    def mkAction(fixture: Fixture): Effect[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap {
        case 1 => R.raise(oneMoreTimeError)
        case _ => R.raise(notWorthRetryingError)
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture
      action = mkAction(fixture)
      finalResult <- retryingOnErrors(action)(
        policy,
        mkHandler(fixture)
      ).value
      state <- fixture.getState
    yield
      // THEN the non-retryable error is raised
      assertEquals(finalResult, Left(notWorthRetryingError))
      // AND it took 2 attempts
      assertEquals(state.attempts, 2)
      // AND the action's error was passed to the handler each time
      assertEquals(state.appErrors, Vector(oneMoreTimeError, notWorthRetryingError))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnErrors - retry with adaptation until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[Effect](1.milli)

    // AND an initial action that always raises an error
    def mkAction(fixture: Fixture): Effect[String] =
      fixture.incrementAttempts() >> R.raise(oneMoreTimeError)

    // AND a result handler that adapts after 2 attempts, to an action that succeeds
    def mkHandler(fixture: Fixture): ResultHandler[Effect, AppError, String] =
      (error: AppError, retryDetails: RetryDetails) =>
        fixture.updateState(error, retryDetails) *> fixture.getAttempts.map {
          case 1 => // first attempt
            Continue
          case 2 => // second attempt
            val newAction =
              fixture.incrementAttempts() >> fixture.getAttempts.map(_.toString)
            Adapt(newAction)
          case _ => // for exhaustivity
            Continue
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture
      action = mkAction(fixture)
      finalResult <- retryingOnErrors(action)(
        policy,
        mkHandler(fixture)
      ).value
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, Right("3"))
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's error was passed to the handler each time
      assertEquals(state.appErrors, Vector.fill(2)(oneMoreTimeError))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

end CombinatorsSuite
