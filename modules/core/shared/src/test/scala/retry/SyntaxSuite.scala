package retry

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import retry.RetryDetails.NextStep.DelayAndRetry
import retry.HandlerDecision.{Stop, Continue}
import retry.syntax.*

import scala.concurrent.duration.*

class SyntaxSuite extends CatsEffectSuite:

  private case class State[Res](
      attempts: Int = 0,
      results: Vector[Res] = Vector.empty,
      retryCounts: Vector[Int] = Vector.empty,
      nextSteps: Vector[RetryDetails.NextStep] = Vector.empty
  )

  private class Fixture[Res](stateRef: Ref[IO, State[Res]]):
    def incrementAttempts(): IO[Unit] =
      stateRef.update(state => state.copy(attempts = state.attempts + 1))

    def updateState(result: Res, details: RetryDetails): IO[Unit] =
      stateRef.update { state =>
        state.copy(
          results = state.results :+ result,
          retryCounts = state.retryCounts :+ details.retriesSoFar,
          nextSteps = state.nextSteps :+ details.nextStepIfUnsuccessful
        )
      }

    def getState: IO[State[Res]] = stateRef.get
    def getAttempts: IO[Int]     = getState.map(_.attempts)

  private def mkFixture[Res]: IO[Fixture[Res]] = Ref.of[IO, State[Res]](State()).map(new Fixture(_))

  private val oneMoreTimeException = new RuntimeException("one more time")

  test("retryingOnFailures - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that does no adaptation and treats the 4th result as a success
    def mkHandler(fixture: Fixture[String]): ValueHandler[IO, String] =
      ResultHandler.retryUntilSuccessful(_.toInt > 3, log = fixture.updateState)

    // AND an action that returns the attempt count as a string
    def mkAction(fixture: Fixture[String]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.map(_.toString)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[String]
      action = mkAction(fixture)
      finalResult <- action.retryingOnFailures(
        policy,
        mkHandler(fixture)
      )
      state <- fixture.getState
    yield
      // THEN the successful result is returned, wrapped in a Right
      assertEquals(finalResult, Right("4"))
      // AND it took 4 attempts
      assertEquals(state.attempts, 4)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector("1", "2", "3", "4"))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2, 3))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(4)(DelayAndRetry(1.milli)))
  }

  test("retryingOnErrors - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that retries on all errors
    def mkHandler(fixture: Fixture[Throwable]): ErrorHandler[IO, String] =
      ResultHandler.retryOnAllErrors(log = fixture.updateState)

    // AND an action that raises an error twice and then succeeds
    def mkAction(fixture: Fixture[Throwable]): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(oneMoreTimeException)
          else IO.pure("yay")
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Throwable]
      action = mkAction(fixture)
      finalResult <- action.retryingOnErrors(
        policy,
        mkHandler(fixture)
      )
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "yay")
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's error was passed to the handler each time
      assertEquals(state.results, Vector.fill(2)(oneMoreTimeException))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnFailuresAndErrors - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that does no adaptation and treats "yay" as a success
    def mkHandler(fixture: Fixture[Either[Throwable, String]]): ErrorOrValueHandler[IO, String] =
      (result: Either[Throwable, String], retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as(if result == Right("yay") then Stop else Continue)

    // AND an action that raises an error twice, then returns an unsuccessful value, then a successful value
    def mkAction(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap {
        case 1 => IO.raiseError(oneMoreTimeException)
        case 2 => IO.raiseError(oneMoreTimeException)
        case 3 => IO.pure("boo")
        case _ => IO.pure("yay")
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      action = mkAction(fixture)
      finalResult <- action.retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )
      state <- fixture.getState
    yield
      // THEN the successful result is returned, wrapped in a Right
      assertEquals(finalResult, Right("yay"))
      // AND it took 4 attempts
      assertEquals(state.attempts, 4)
      // AND the action's result was passed to the handler each time
      assertEquals(
        state.results,
        Vector(Left(oneMoreTimeException), Left(oneMoreTimeException), Right("boo"), Right("yay"))
      )
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2, 3))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(4)(DelayAndRetry(1.milli)))
  }

end SyntaxSuite
