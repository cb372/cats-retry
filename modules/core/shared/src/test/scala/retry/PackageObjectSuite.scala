package retry

import munit.FunSuite

import scala.concurrent.duration.*
import cats.effect.kernel.Ref
import cats.effect.IO

class PackageObjectSuite extends FunSuite:

  private case class State(
      attempts: Int = 0,
      errors: Vector[String] = Vector.empty,
      delays: Vector[FiniteDuration] = Vector.empty,
      gaveUp: Boolean = false
  )

  private class Fixture(stateRef: Ref[IO, State]):
    def incrementAttempts(): IO[Unit] =
      stateRef.update(state => state.copy(attempts = state.attempts + 1))

    def onError(error: String, details: RetryDetails): IO[Unit] =
      stateRef.update { state =>
        details match
          case RetryDetails.WillDelayAndRetry(delay, _, _) =>
            state.copy(
              errors = state.errors :+ error,
              delays = state.delays :+ delay
            )
          case RetryDetails.GivingUp(_, _) =>
            state.copy(
              errors = state.errors :+ error,
              gaveUp = true
            )
      }

    def getState: IO[State]  = stateRef.get
    def getAttempts: IO[Int] = getState.map(_.attempts)

  private val mkFixture: IO[Fixture] = Ref.of[IO, State](State()).map(new Fixture(_))

  test("retryingOnFailures - retry until the action succeeds") {

    val policy = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >> fixture.getState.map(_.attempts.toString)

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailures[String][IO](
        policy,
        (attempts: String) => IO.pure(attempts.toInt > 3),
        fixture.onError
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(finalResult, "4")
      assertEquals(state.attempts, 4)
      assertEquals(state.errors.toList, List("1", "2", "3"))
      assertEquals(state.delays.toList, List(1.milli, 1.milli, 1.milli))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnFailures - retry until the policy chooses to give up") {

    val policy = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >> fixture.getState.map(_.attempts.toString)

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailures[String][IO](
        policy,
        (attempts: String) => IO.pure(attempts.toInt > 3),
        fixture.onError
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(finalResult, "3")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("1", "2", "3"))
      assertEquals(state.delays.toList, List(Duration.Zero, Duration.Zero, Duration.Zero))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnFailures - retry in a stack-safe way") {

    val policy = RetryPolicies.limitRetries[IO](10_000)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >> fixture.getState.map(_.attempts.toString)

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailures[String][IO](
        policy,
        (attempts: String) => IO.pure(attempts.toInt > 20_000),
        fixture.onError
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(finalResult, "10001")
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnSomeErrors - retry until the action succeeds") {
    val policy: RetryPolicy[IO] = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(new RuntimeException("one more time"))
          else IO.pure("yay")
        }

    for
      fixture <- mkFixture
      result <- retryingOnSomeErrors[String][IO](
        policy,
        e => IO.pure(e.getMessage == "one more time"),
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(result, "yay")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnSomeErrors - retry only if the error is worth retrying") {
    val policy: RetryPolicy[IO] = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError[String](new RuntimeException("one more time"))
          else IO.raiseError[String](new RuntimeException("nope"))
        }

    for
      fixture <- mkFixture
      result <- retryingOnSomeErrors(
        policy,
        e => IO.pure(e.getMessage == "one more time"),
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(result, "nope")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(
        state.gaveUp,
        false // false because onError is only called when the error is worth retrying
      )
  }

  test("retryingOnSomeErrors - retry until the policy chooses to give up") {
    val policy: RetryPolicy[IO] = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError[String](new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      result <- retryingOnSomeErrors[String][IO](
        policy,
        e => IO.pure(e.getMessage == "one more time"),
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(result, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time", "one more time"))
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnSomeErrors - retry in a stack-safe way") {
    val policy: RetryPolicy[IO] = RetryPolicies.limitRetries[IO](10_000)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError[String](new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      result <- retryingOnSomeErrors[String][IO](
        policy,
        e => IO.pure(e.getMessage == "one more time"),
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(result, "one more time")
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnAllErrors - retry until the action succeeds") {
    val policy: RetryPolicy[IO] = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(new RuntimeException("one more time"))
          else IO.pure("yay")
        }

    for
      fixture <- mkFixture
      result <- retryingOnAllErrors[String][IO](
        policy,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(result, "yay")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnAllErrors - retry until the policy chooses to give up") {
    val policy: RetryPolicy[IO] = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError[String](new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      result <- retryingOnAllErrors[String][IO](
        policy,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(result, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnAllErrors - retry in a stack-safe way") {
    val policy: RetryPolicy[IO] = RetryPolicies.limitRetries[IO](10_000)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError[String](new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      result <- retryingOnAllErrors[String][IO](
        policy,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(result, "one more time")
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnFailuresAndSomeErrors - retry until the action succeeds") {

    val policy = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(new RuntimeException("one more time"))
          else IO.pure("yay")
        }
    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndSomeErrors[String](
        policy,
        s => IO.pure(s == "yay"),
        e => IO.pure(e.getMessage == "one more time"),
        fixture.onError,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
      state <- fixture.getState
    yield
      assertEquals(finalResult, "yay")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, false)

  }

  test("retryingOnFailuresAndSomeErrors - retry only if the error is worth retrying") {
    val policy = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(new RuntimeException("one more time"))
          else IO.raiseError(new RuntimeException("nope"))
        }
    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndSomeErrors[String](
        policy,
        s => IO.pure(s == "will never happen"),
        e => IO.pure(e.getMessage == "one more time"),
        fixture.onError,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(
        state.gaveUp,
        false // false because onError is only called when the error is worth retrying
      )
  }

  test("retryingOnFailuresAndSomeErrors - retry until the policy chooses to give up due to errors") {
    val policy = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(new RuntimeException("one more time"))
    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndSomeErrors[String](
        policy,
        s => IO.pure(s == "will never happen"),
        e => IO.pure(e.getMessage == "one more time"),
        fixture.onError,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time", "one more time"))
      assertEquals(state.gaveUp, true)
  }

  test(
    "retryingOnFailuresAndSomeErrors - retry until the policy chooses to give up due to failures"
  ) {
    val policy = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts().as("boo")
    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndSomeErrors[String](
        policy,
        s => IO.pure(s == "yay"),
        e => IO.pure(e.getMessage == "one more time"),
        fixture.onError,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "boo")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time", "one more time"))
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnFailuresAndSomeErrors - retry in a stack-safe way") {

    val policy = RetryPolicies.limitRetries[IO](10_000)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndSomeErrors[String](
        policy,
        s => IO.pure(s == "yay"),
        e => IO.pure(e.getMessage == "one more time"),
        fixture.onError,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "boo")
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnFailuresAndSomeErrors - should fail fast if isWorthRetrying's effect fails") {
    val policy = RetryPolicies.limitRetries[IO](10_000)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndSomeErrors[String](
        policy,
        s => IO.pure(s == "does not matter"),
        e => IO.raiseError(new RuntimeException("an error was raised!")),
        fixture.onError,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "an error was raised!")
      assertEquals(state.attempts, 1)
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnFailuresAndAllErrors - retry until the action succeeds") {

    val policy = RetryPolicies.constantDelay[IO](1.milli)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if attempts < 3 then IO.raiseError(new RuntimeException("one more time"))
          else IO.pure("yay")
        }

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndAllErrors[String](
        policy,
        s => IO.pure(s == "yay"),
        fixture.onError,
        (e, rd) => fixture.onError(e.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "yay")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, false)
  }

  test("retryingOnFailuresAndAllErrors - retry until the policy chooses to give up due to errors") {

    val policy = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndAllErrors[String](
        policy,
        s => IO.pure(s == "will never happen"),
        fixture.onError,
        (e, rd) => fixture.onError(e.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time", "one more time"))
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnFailuresAndAllErrors - retry until the policy chooses to give up due to failures") {

    val policy = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts().as("boo")

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndAllErrors[String](
        policy,
        s => IO.pure(s == "yay"),
        fixture.onError,
        (e, rd) => fixture.onError(e.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "boo")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("boo", "boo", "boo"))
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnFailuresAndAllErrors - retry in a stack-safe way") {

    val policy = RetryPolicies.limitRetries[IO](10_000)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndAllErrors[String](
        policy,
        s => IO.pure(s == "will never happen"),
        fixture.onError,
        (e, rd) => fixture.onError(e.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "one more time")
      assertEquals(state.attempts, 10_001)
      assertEquals(state.gaveUp, true)
  }

  test("retryingOnFailuresAndAllErrors - should fail fast if wasSuccessful's effect fails") {

    val policy = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >>
        IO.raiseError(new RuntimeException("one more time"))

    for
      fixture <- mkFixture
      finalResult <- retryingOnFailuresAndAllErrors[String](
        policy,
        _ => IO.raiseError(new RuntimeException("an error was raised!")),
        fixture.onError,
        (e, rd) => fixture.onError(e.getMessage, rd)
      )(action(fixture))
        .recover { case e => e.getMessage }
      state <- fixture.getState
    yield
      assertEquals(finalResult, "an error was raised!")
      assertEquals(state.attempts, 1)
      assertEquals(state.gaveUp, false)
  }
end PackageObjectSuite
