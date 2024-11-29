package retry

import retry.syntax.all._

import scala.concurrent.duration._
import munit.CatsEffectSuite
import cats.effect.IO
import cats.effect.kernel.Ref

class SyntaxSuite extends CatsEffectSuite {

  private case class State(
      attempts: Int = 0,
      errors: Vector[String] = Vector.empty,
      delays: Vector[FiniteDuration] = Vector.empty,
      gaveUp: Boolean = false
  )

  private class Fixture(stateRef: Ref[IO, State]) {
    def incrementAttempts(): IO[Unit] =
      stateRef.update(state => state.copy(attempts = state.attempts + 1))

    def onError(error: String, details: RetryDetails): IO[Unit] =
      stateRef.update { state =>
        details match {
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
      }

    def getState: IO[State]  = stateRef.get
    def getAttempts: IO[Int] = getState.map(_.attempts)
  }

  private val mkFixture: IO[Fixture] = Ref.of[IO, State](State()).map(new Fixture(_))

  test("retryingOnFailures - retry until the action succeeds") {

    val policy: RetryPolicy[IO]                 = RetryPolicies.constantDelay[IO](1.second)
    def wasSuccessful(res: String): IO[Boolean] = IO.pure(res.toInt > 3)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >> fixture.getState.map(_.attempts.toString)

    for {
      fixture     <- mkFixture
      finalResult <- action(fixture).retryingOnFailures(wasSuccessful, policy, fixture.onError)
      state       <- fixture.getState
    } yield {
      assertEquals(finalResult, "4")
      assertEquals(state.attempts, 4)
      assertEquals(state.errors.toList, List("1", "2", "3"))
      assertEquals(state.delays.toList, List(1.second, 1.second, 1.second))
      assertEquals(state.gaveUp, false)
    }
  }

  test("retryingOnFailures - retry until the policy chooses to give up") {

    val policy: RetryPolicy[IO]                 = RetryPolicies.limitRetries[IO](2)
    def wasSuccessful(res: String): IO[Boolean] = IO.pure(res.toInt > 3)

    def action(fixture: Fixture): IO[String] =
      fixture.incrementAttempts() >> fixture.getState.map(_.attempts.toString)

    for {
      fixture     <- mkFixture
      finalResult <- action(fixture).retryingOnFailures(wasSuccessful, policy, fixture.onError)
      state       <- fixture.getState
    } yield {
      assertEquals(finalResult, "3")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("1", "2", "3"))
      assertEquals(state.delays.toList, List(Duration.Zero, Duration.Zero))
      assertEquals(state.gaveUp, true)
    }

  }

  test("retryingOnSomeErrors - retry until the action succeeds") {

    val policy: RetryPolicy[IO] = RetryPolicies.constantDelay[IO](1.second)

    def action(fixture: Fixture): IO[String] = {
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if (attempts < 3)
            IO.raiseError(new RuntimeException("one more time"))
          else
            IO.pure("yay")
        }
    }

    for {
      fixture <- mkFixture
      result <- action(fixture).retryingOnSomeErrors(
        e => IO.pure(e.getMessage == "one more time"),
        policy,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )
      state <- fixture.getState
    } yield {
      assertEquals(result, "yay")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, false)
    }
  }

  test("retryingOnSomeErrors - retry only if the error is worth retrying") {

    val policy: RetryPolicy[IO] = RetryPolicies.constantDelay[IO](1.second)

    def action(fixture: Fixture): IO[String] = {
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if (attempts < 3)
            IO.raiseError[String](new RuntimeException("one more time"))
          else
            IO.raiseError[String](new RuntimeException("nope"))
        }
    }

    for {
      fixture <- mkFixture
      result <- action(fixture)
        .retryingOnSomeErrors(
          e => IO.pure(e.getMessage == "one more time"),
          policy,
          (err, rd) => fixture.onError(err.getMessage, rd)
        )
        .recover { case e => e.getMessage }
      state <- fixture.getState
    } yield {
      assertEquals(result, "nope")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(
        state.gaveUp,
        false // false because onError is only called when the error is worth retrying
      )
    }
  }

  test("retryingOnSomeErrors - retry until the policy chooses to give up") {

    val policy: RetryPolicy[IO] = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] = {
      fixture.incrementAttempts() >>
        IO.raiseError[String](new RuntimeException("one more time"))
    }

    for {
      fixture <- mkFixture
      result <- action(fixture)
        .retryingOnSomeErrors(
          e => IO.pure(e.getMessage == "one more time"),
          policy,
          (err, rd) => fixture.onError(err.getMessage, rd)
        )
        .recover { case e => e.getMessage }
      state <- fixture.getState
    } yield {
      assertEquals(result, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time", "one more time"))
      assertEquals(state.gaveUp, true)
    }
  }

  test("retryingOnAllErrors - retry until the action succeeds") {
    val policy: RetryPolicy[IO] = RetryPolicies.constantDelay[IO](1.second)

    def action(fixture: Fixture): IO[String] = {
      fixture.incrementAttempts() >>
        fixture.getAttempts.flatMap { attempts =>
          if (attempts < 3)
            IO.raiseError(new RuntimeException("one more time"))
          else
            IO.pure("yay")
        }
    }

    for {
      fixture <- mkFixture
      result <- action(fixture).retryingOnAllErrors(
        policy,
        (err, rd) => fixture.onError(err.getMessage, rd)
      )
      state <- fixture.getState
    } yield {
      assertEquals(result, "yay")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time"))
      assertEquals(state.gaveUp, false)
    }
  }

  test("retryingOnAllErrors - retry until the policy chooses to give up") {
    val policy: RetryPolicy[IO] = RetryPolicies.limitRetries[IO](2)

    def action(fixture: Fixture): IO[String] = {
      fixture.incrementAttempts() >>
        IO.raiseError[String](new RuntimeException("one more time"))
    }

    for {
      fixture <- mkFixture
      result <- action(fixture)
        .retryingOnAllErrors(
          policy,
          (err, rd) => fixture.onError(err.getMessage, rd)
        )
        .recover { case e => e.getMessage }
      state <- fixture.getState
    } yield {
      assertEquals(result, "one more time")
      assertEquals(state.attempts, 3)
      assertEquals(state.errors.toList, List("one more time", "one more time", "one more time"))
      assertEquals(state.gaveUp, true)
    }
  }

}
