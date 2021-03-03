package retry

import cats.Id
import cats.instances.all._
import munit._
import retry.syntax.all._
import cats.catsInstancesForId

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class SyntaxSuite extends FunSuite {
  type StringOr[A] = Either[String, A]

  private val testContext =
    FunFixture[TestContext](_ => new TestContext, _ => ())

  testContext.test(
    "retryingOnFailures should retry until the action succeeds"
  ) { ctx =>
    val policy: RetryPolicy[Id]                       = RetryPolicies.constantDelay[Id](1.second)
    def onFailure: (String, RetryDetails) => Id[Unit] = ctx.onError
    def wasSuccessful(res: String): Id[Boolean]       = res.toInt > 3
    val sleeps                                        = ArrayBuffer.empty[FiniteDuration]

    implicit val dummySleep: Sleep[Id] =
      (delay: FiniteDuration) => sleeps.append(delay)

    def action: Id[String] = {
      ctx.attempts = ctx.attempts + 1
      ctx.attempts.toString
    }

    val finalResult: Id[String] =
      action.retryingOnFailures(wasSuccessful, policy, onFailure)

    assertEquals(finalResult, "4")
    assertEquals(ctx.attempts, 4)
    assertEquals(ctx.errors.toList, List("1", "2", "3"))
    assertEquals(ctx.delays.toList, List(1.second, 1.second, 1.second))
    assertEquals(sleeps.toList, ctx.delays.toList)
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailures should retry until the policy chooses to give up"
  ) { ctx =>
    val policy: RetryPolicy[Id]        = RetryPolicies.limitRetries[Id](2)
    implicit val dummySleep: Sleep[Id] = _ => ()

    def action: Id[String] = {
      ctx.attempts = ctx.attempts + 1
      ctx.attempts.toString
    }

    val finalResult: Id[String] =
      action.retryingOnFailures(_.toInt > 3, policy, ctx.onError)

    assertEquals(finalResult, "3")
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("1", "2", "3"))
    assertEquals(ctx.delays.toList, List(Duration.Zero, Duration.Zero))
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomeErrors should retry until the action succeeds"
  ) { ctx =>
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[String] = {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    val finalResult: StringOr[String] =
      action.retryingOnSomeErrors(
        _ == "one more time",
        policy,
        (err, rd) => ctx.onError(err, rd)
      )

    assertEquals(finalResult, Right("yay"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomeErrors should retry only if the error is worth retrying"
  ) { ctx =>
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[Nothing] = {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Left("nope")
    }

    val finalResult =
      action.retryingOnSomeErrors(
        _ == "one more time",
        policy,
        (err, rd) => ctx.onError(err, rd)
      )

    assertEquals(finalResult, Left("nope"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(
      !ctx.gaveUp
    ) // false because onError is only called when the error is worth retrying
  }

  testContext.test(
    "retryingOnSomeErrors should retry until the policy chooses to give up"
  ) { ctx =>
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.limitRetries[StringOr](2)

    def action: StringOr[Nothing] = {
      ctx.attempts = ctx.attempts + 1

      Left("one more time")
    }

    val finalResult: StringOr[Nothing] =
      action.retryingOnSomeErrors(
        _ == "one more time",
        policy,
        (err, rd) => ctx.onError(err, rd)
      )

    assertEquals(finalResult, Left("one more time"))
    assertEquals(ctx.attempts, 3)
    assert(
      ctx.errors.toList == List(
        "one more time",
        "one more time",
        "one more time"
      )
    )
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllErrors should retry until the action succeeds"
  ) { ctx =>
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[String] = {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    val finalResult: StringOr[String] =
      action.retryingOnAllErrors(policy, (err, rd) => ctx.onError(err, rd))

    assertEquals(finalResult, Right("yay"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllErrors should retry until the policy chooses to give up"
  ) { ctx =>
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.limitRetries[StringOr](2)

    def action: StringOr[Nothing] = {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

    val finalResult =
      action.retryingOnAllErrors(policy, (err, rd) => ctx.onError(err, rd))

    assertEquals(finalResult, Left("one more time"))
    assertEquals(ctx.attempts, 3)
    assert(
      ctx.errors.toList == List(
        "one more time",
        "one more time",
        "one more time"
      )
    )
    assert(ctx.gaveUp)
  }

  private class TestContext {
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: String, details: RetryDetails): Either[String, Unit] = {
      errors.append(error)
      details match {
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      }
      Right(())
    }
  }
}
