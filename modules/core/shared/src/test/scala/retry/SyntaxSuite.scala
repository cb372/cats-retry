package retry

import cats.Id
import munit.FunSuite
import retry.syntax.all.*

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class SyntaxSuite extends FunSuite:
  type StringOr[A] = Either[String, A]

  private class TestContext:
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def incrementAttempts(): Unit =
      attempts = attempts + 1

    def onErrorId(error: String, details: RetryDetails): Id[Unit] =
      errors.append(error)
      details match
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true

    def onError(error: String, details: RetryDetails): Either[String, Unit] =
      onErrorId(error, details)
      Right(())

  private val fixture = FunFixture[TestContext](
    setup = _ => new TestContext,
    teardown = _ => ()
  )

  fixture.test("retryingOnFailures - retry until the action succeeds") { context =>
    import context.*

    val policy: RetryPolicy[Id]                       = RetryPolicies.constantDelay[Id](1.second)
    def onFailure: (String, RetryDetails) => Id[Unit] = onErrorId
    def wasSuccessful(res: String): Id[Boolean]       = res.toInt > 3
    val sleeps                                        = ArrayBuffer.empty[FiniteDuration]

    implicit val dummySleep: Sleep[Id] =
      (delay: FiniteDuration) => sleeps.append(delay)

    def action: Id[String] =
      incrementAttempts()
      attempts.toString

    val finalResult: Id[String] =
      action.retryingOnFailures(wasSuccessful, policy, onFailure)

    assertEquals(finalResult, "4")
    assertEquals(attempts, 4)
    assertEquals(errors.toList, List("1", "2", "3"))
    assertEquals(delays.toList, List(1.second, 1.second, 1.second))
    assertEquals(sleeps.toList, delays.toList)
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnFailures - retry until the policy chooses to give up") { context =>
    import context.*

    val policy: RetryPolicy[Id]        = RetryPolicies.limitRetries[Id](2)
    implicit val dummySleep: Sleep[Id] = _ => ()

    def action: Id[String] =
      incrementAttempts()
      attempts.toString

    val finalResult: Id[String] =
      action.retryingOnFailures(_.toInt > 3, policy, onErrorId)

    assertEquals(finalResult, "3")
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("1", "2", "3"))
    assertEquals(delays.toList, List(Duration.Zero, Duration.Zero))
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnSomeErrors - retry until the action succeeds") { context =>
    import context.*

    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[String] =
      incrementAttempts()
      if attempts < 3 then Left("one more time")
      else Right("yay")

    val finalResult: StringOr[String] =
      action.retryingOnSomeErrors(
        s => Right(s == "one more time"),
        policy,
        (err, rd) => onError(err, rd)
      )

    assertEquals(finalResult, Right("yay"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnSomeErrors - retry only if the error is worth retrying") { context =>
    import context.*

    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[Nothing] =
      incrementAttempts()
      if attempts < 3 then Left("one more time")
      else Left("nope")

    val finalResult =
      action.retryingOnSomeErrors(
        s => Right(s == "one more time"),
        policy,
        (err, rd) => onError(err, rd)
      )

    assertEquals(finalResult, Left("nope"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(
      gaveUp,
      false // false because onError is only called when the error is worth retrying
    )
  }

  fixture.test("retryingOnSomeErrors - retry until the policy chooses to give up") { context =>
    import context.*

    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.limitRetries[StringOr](2)

    def action: StringOr[Nothing] =
      incrementAttempts()
      Left("one more time")

    val finalResult: StringOr[Nothing] =
      action.retryingOnSomeErrors(
        s => Right(s == "one more time"),
        policy,
        (err, rd) => onError(err, rd)
      )

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 3)
    assertEquals(
      errors.toList,
      List("one more time", "one more time", "one more time")
    )
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnAllErrors - retry until the action succeeds") { context =>
    import context.*

    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[String] =
      incrementAttempts()
      if attempts < 3 then Left("one more time")
      else Right("yay")

    val finalResult: StringOr[String] =
      action.retryingOnAllErrors(policy, (err, rd) => onError(err, rd))

    assertEquals(finalResult, Right("yay"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnAllErrors - retry until the policy chooses to give up") { context =>
    import context.*

    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.limitRetries[StringOr](2)

    def action: StringOr[Nothing] =
      incrementAttempts()
      Left("one more time")

    val finalResult =
      action.retryingOnAllErrors(policy, (err, rd) => onError(err, rd))

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 3)
    assertEquals(
      errors.toList,
      List("one more time", "one more time", "one more time")
    )
    assertEquals(gaveUp, true)
  }
end SyntaxSuite
