package retry

import munit.CatsEffectSuite

import cats.effect.{IO, Ref}
import retry.RetryDetails.NextStep.{GiveUp, DelayAndRetry}
import retry.HandlerDecision.{Stop, Continue, Adapt}

import scala.concurrent.duration.*

class PackageObjectSuite extends CatsEffectSuite:

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

  private val oneMoreTimeException      = new RuntimeException("one more time")
  private val notWorthRetryingException = new RuntimeException("nope")

  test("retryingOnFailures - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that does no adaptation and treats the 4th result as a success
    def mkHandler(fixture: Fixture[String]): ValueHandler[IO, String] =
      (result: String, retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as(if result.toInt > 3 then Stop else Continue)

    // AND an action that returns the attempt count as a string
    def action(fixture: Fixture[String]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.map(_.toString)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[String]
      finalResult <- retryingOnFailures(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "4")
      // AND it took 4 attempts
      assertEquals(state.attempts, 4)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector("1", "2", "3", "4"))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2, 3))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(4)(DelayAndRetry(1.milli)))
  }

  test("retryingOnFailures - retry until the policy chooses to give up") {
    // GIVEN a retry policy that will retry twice and then give up
    val policy = RetryPolicies.limitRetries[IO](2)

    // AND a result handler that does no adaptation and treats the 4th result as a success
    def mkHandler(fixture: Fixture[String]): ValueHandler[IO, String] =
      (result: String, retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as(if result.toInt > 3 then Stop else Continue)

    // AND an action that returns the attempt count as a string
    def action(fixture: Fixture[String]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.map(_.toString)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[String]
      finalResult <- retryingOnFailures(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the last unsuccessful result is returned
      assertEquals(finalResult, "3")
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector("1", "2", "3"))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector(DelayAndRetry(0.milli), DelayAndRetry(0.milli), GiveUp))
  }

  test("retryingOnFailures - retry with adaptation until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND an initial action that returns the negative of the attempt count as a string
    def action(fixture: Fixture[String]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.map(n => (n * -1).toString)

    // AND a result handler that adapts after 2 attempts and treats the 4th result as a success
    def mkHandler(fixture: Fixture[String]): ValueHandler[IO, String] =
      (result: String, retryDetails: RetryDetails) =>
        fixture.updateState(result, retryDetails).as {
          result.toInt match
            case -1 => // first attempt
              Continue
            case -2 => // second attempt
              val newAction =
                fixture.incrementAttempts() >> fixture.getAttempts.map(_.toString)
              Adapt(newAction)
            case 3 => // third attempt
              Continue
            case 4 => // fourth attempt
              Stop
            case _ => // for exhaustivity
              Continue
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[String]
      finalResult <- retryingOnFailures(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "4")
      // AND it took 4 attempts
      assertEquals(state.attempts, 4)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector("-1", "-2", "3", "4"))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2, 3))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(4)(DelayAndRetry(1.milli)))
  }

  test("retryingOnFailures - retry in a stack-safe way") {
    // GIVEN a retry policy that will retry 10k times
    val policy = RetryPolicies.limitRetries[IO](10_000)

    // AND a result handler that will retry > 10k times
    def mkHandler(fixture: Fixture[String]): ValueHandler[IO, String] =
      (result: String, retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as(if result.toInt > 20_000 then Stop else Continue)

    // AND an action that returns the attempt count as a string
    def action(fixture: Fixture[String]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.map(_.toString)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[String]
      finalResult <- retryingOnFailures(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the last unsuccessful result is returned
      assertEquals(finalResult, "10001")
      // AND it took 10,001 attempts
      assertEquals(state.attempts, 10001)
  }

  test("retryingOnErrors - retry until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that retries on all errors
    def mkHandler(fixture: Fixture[Throwable]): ErrorHandler[IO, String] =
      (error: Throwable, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
          .as(Continue)

    // AND an action that raises an error twice and then succeeds
    def action(fixture: Fixture[Throwable]): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(oneMoreTimeException)
          else IO.pure("yay")
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Throwable]
      finalResult <- retryingOnErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture))
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

  test("retryingOnErrors - retry until the policy chooses to give up") {
    // GIVEN a retry policy that will retry twice and then give up
    val policy = RetryPolicies.limitRetries[IO](2)

    // AND a result handler that retries on all errors
    def mkHandler(fixture: Fixture[Throwable]): ErrorHandler[IO, String] =
      (error: Throwable, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
          .as(Continue)

    // AND an action that always raises an error
    def action(fixture: Fixture[Throwable]): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(oneMoreTimeException)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Throwable]
      finalResult <- retryingOnErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture)).attempt
      state <- fixture.getState
    yield
      // THEN the final error is raised
      assertEquals(finalResult, Left(oneMoreTimeException))
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's error was passed to the handler each time
      assertEquals(state.results, Vector.fill(3)(oneMoreTimeException))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector(DelayAndRetry(0.milli), DelayAndRetry(0.milli), GiveUp))
  }

  test("retryingOnErrors - give up if the handler says the error is not worth retrying") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that retries on some errors but gives up on others
    def mkHandler(fixture: Fixture[Throwable]): ErrorHandler[IO, String] =
      (error: Throwable, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
          .as {
            error match
              case `oneMoreTimeException` => Continue
              case _                      => Stop
          }

    // AND an action that raises a retryable error followed by a non-retryable error
    def action(fixture: Fixture[Throwable]): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap {
          case 1 => IO.raiseError(oneMoreTimeException)
          case _ => IO.raiseError(notWorthRetryingException)
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Throwable]
      finalResult <- retryingOnErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture)).attempt
      state <- fixture.getState
    yield
      // THEN the non-retryable error is raised
      assertEquals(finalResult, Left(notWorthRetryingException))
      // AND it took 2 attempts
      assertEquals(state.attempts, 2)
      // AND the action's error was passed to the handler each time
      assertEquals(state.results, Vector(oneMoreTimeException, notWorthRetryingException))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnErrors - retry with adaptation until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND an initial action that always raises an error
    def action(fixture: Fixture[Throwable]): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(oneMoreTimeException)

    // AND a result handler that adapts after 2 attempts, to an action that succeeds
    def mkHandler(fixture: Fixture[Throwable]): ErrorHandler[IO, String] =
      (error: Throwable, retryDetails: RetryDetails) =>
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
      fixture <- mkFixture[Throwable]
      finalResult <- retryingOnErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "3")
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's error was passed to the handler each time
      assertEquals(state.results, Vector.fill(2)(oneMoreTimeException))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnErrors - retry in a stack-safe way") {
    // GIVEN a retry policy that will retry 10k times
    val policy = RetryPolicies.limitRetries[IO](10_000)

    // AND a result handler that will always retry
    def mkHandler(fixture: Fixture[Throwable]): ErrorHandler[IO, String] =
      (error: Throwable, retryDetails: RetryDetails) =>
        fixture
          .updateState(error, retryDetails)
          .as(Continue)

    // AND an action that always raises an error
    def action(fixture: Fixture[Throwable]): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(oneMoreTimeException)

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Throwable]
      finalResult <- retryingOnErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture)).attempt
      state <- fixture.getState
    yield
      // THEN the final error is raised
      assertEquals(finalResult, Left(oneMoreTimeException))
      // AND it took 10,001 attempts
      assertEquals(state.attempts, 10001)
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
    def action(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap {
        case 1 => IO.raiseError(oneMoreTimeException)
        case 2 => IO.raiseError(oneMoreTimeException)
        case 3 => IO.pure("boo")
        case _ => IO.pure("yay")
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      finalResult <- retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "yay")
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

  test("retryingOnFailuresAndErrors - retry until the policy chooses to give up - failure") {
    // GIVEN a retry policy that will retry twice and then give up
    val policy = RetryPolicies.limitRetries[IO](2)

    // AND a result handler that does no adaptation and treats "yay" as a success
    def mkHandler(fixture: Fixture[Either[Throwable, String]]): ErrorOrValueHandler[IO, String] =
      (result: Either[Throwable, String], retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as(if result == Right("yay") then Stop else Continue)

    // AND an action that raises an error twice, then returns an unsuccessful value, then a successful value
    def action(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap {
        case 1 => IO.raiseError(oneMoreTimeException)
        case 2 => IO.raiseError(oneMoreTimeException)
        case 3 => IO.pure("boo")
        case _ => IO.pure("yay")
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      finalResult <- retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the unsuccessful result is returned
      assertEquals(finalResult, "boo")
      // AND it took 3 attempts
      assertEquals(state.attempts, 3)
      // AND the action's result was passed to the handler each time
      assertEquals(
        state.results,
        Vector(Left(oneMoreTimeException), Left(oneMoreTimeException), Right("boo"))
      )
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1, 2))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector(DelayAndRetry(0.milli), DelayAndRetry(0.milli), GiveUp))
  }

  test("retryingOnFailuresAndErrors - retry until the policy chooses to give up - error") {
    // GIVEN a retry policy that will retry once and then give up
    val policy = RetryPolicies.limitRetries[IO](1)

    // AND a result handler that does no adaptation and treats "yay" as a success
    def mkHandler(fixture: Fixture[Either[Throwable, String]]): ErrorOrValueHandler[IO, String] =
      (result: Either[Throwable, String], retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as(if result == Right("yay") then Stop else Continue)

    // AND an action that raises an error twice, then returns a successful value
    def action(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >> fixture.getAttempts.flatMap {
        case 1 => IO.raiseError(oneMoreTimeException)
        case 2 => IO.raiseError(oneMoreTimeException)
        case _ => IO.pure("yay")
      }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      finalResult <- retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture)).attempt
      state <- fixture.getState
    yield
      // THEN the final error is raised
      assertEquals(finalResult, Left(oneMoreTimeException))
      // AND it took 2 attempts
      assertEquals(state.attempts, 2)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector(Left(oneMoreTimeException), Left(oneMoreTimeException)))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector(DelayAndRetry(0.milli), GiveUp))
  }

  test("retryingOnFailuresAndErrors - give up if the handler says the error is not worth retrying") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND a result handler that retries on some errors but gives up on others
    def mkHandler(fixture: Fixture[Either[Throwable, String]]): ErrorOrValueHandler[IO, String] =
      (result: Either[Throwable, String], retryDetails: RetryDetails) =>
        fixture
          .updateState(result, retryDetails)
          .as {
            result match
              case Left(`oneMoreTimeException`) => Continue
              case Left(_)                      => Stop // give up because error is not worth retrying
              case Right(_)                     => Stop // success
          }

    // AND an action that raises a retryable error followed by a non-retryable error
    def action(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap {
          case 1 => IO.raiseError(oneMoreTimeException)
          case _ => IO.raiseError(notWorthRetryingException)
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      finalResult <- retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture)).attempt
      state <- fixture.getState
    yield
      // THEN the non-retryable error is raised
      assertEquals(finalResult, Left(notWorthRetryingException))
      // AND it took 2 attempts
      assertEquals(state.attempts, 2)
      // AND the action's error was passed to the handler each time
      assertEquals(state.results, Vector(Left(oneMoreTimeException), Left(notWorthRetryingException)))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnFailuresAndErrors - retry with adaptation on failure until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND an initial action that always returns an unsuccessful value
    def action(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >> IO.pure("boo")

    // AND a result handler that adapts on failure, to an action that succeeds
    def mkHandler(fixture: Fixture[Either[Throwable, String]]): ErrorOrValueHandler[IO, String] =
      (result: Either[Throwable, String], retryDetails: RetryDetails) =>
        fixture.updateState(result, retryDetails).as {
          result match
            case Left(_)      => Continue // retry on all errors
            case Right("yay") => Stop     // success
            case Right(_) =>
              val newAction =
                fixture.incrementAttempts().as("yay")
              Adapt(newAction)
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      finalResult <- retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "yay")
      // AND it took 2 attempts
      assertEquals(state.attempts, 2)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector(Right("boo"), Right("yay")))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

  test("retryingOnFailuresAndErrors - retry with adaptation on error until the action succeeds") {
    // GIVEN a retry policy that always wants to retry
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    // AND an initial action that always returns an unsuccessful value
    def action(fixture: Fixture[Either[Throwable, String]]): IO[String] =
      fixture.incrementAttempts() >> IO.raiseError(oneMoreTimeException)

    // AND a result handler that adapts on failure, to an action that succeeds
    def mkHandler(fixture: Fixture[Either[Throwable, String]]): ErrorOrValueHandler[IO, String] =
      (result: Either[Throwable, String], retryDetails: RetryDetails) =>
        fixture.updateState(result, retryDetails).as {
          result match
            case Left(_) =>
              val newAction =
                fixture.incrementAttempts().as("yay")
              Adapt(newAction)
            case Right(_) => Stop // success
        }

    // WHEN the action is executed with retry
    for
      fixture <- mkFixture[Either[Throwable, String]]
      finalResult <- retryingOnFailuresAndErrors(
        policy,
        mkHandler(fixture)
      )(action(fixture))
      state <- fixture.getState
    yield
      // THEN the successful result is returned
      assertEquals(finalResult, "yay")
      // AND it took 2 attempts
      assertEquals(state.attempts, 2)
      // AND the action's result was passed to the handler each time
      assertEquals(state.results, Vector(Left(oneMoreTimeException), Right("yay")))
      // AND the correct retry count was passed to the handler each time
      assertEquals(state.retryCounts, Vector(0, 1))
      // AND the retry policy's chosen next step was passed to the handler each time
      assertEquals(state.nextSteps, Vector.fill(2)(DelayAndRetry(1.milli)))
  }

end PackageObjectSuite
