package retry

import cats.Id
import cats.catsInstancesForId
import munit._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PackageObjectSuite extends FunSuite {
  type StringOr[A] = Either[String, A]

  implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

  private val testContext =
    FunFixture[TestContext](_ => new TestContext, _ => ())

  testContext.test(
    "retryingOnFailures should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[Id](1.second)

    val sleeps = ArrayBuffer.empty[FiniteDuration]

    implicit val dummySleep: Sleep[Id] =
      (delay: FiniteDuration) => sleeps.append(delay)

    val finalResult = retryingOnFailures[String][Id](
      policy,
      _.toInt > 3,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      ctx.attempts.toString
    }

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
    val policy = RetryPolicies.limitRetries[Id](2)

    implicit val dummySleep: Sleep[Id] = _ => ()

    val finalResult = retryingOnFailures[String][Id](
      policy,
      _.toInt > 3,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      ctx.attempts.toString
    }

    assertEquals(finalResult, "3")
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("1", "2", "3"))
    assertEquals(ctx.delays.toList, List(Duration.Zero, Duration.Zero))
    assert(ctx.gaveUp)
  }

  testContext.test("retryingOnFailures should retry in a stack-safe way") {
    ctx =>
      val policy = RetryPolicies.limitRetries[Id](10000)

      implicit val dummySleep: Sleep[Id] = _ => ()

      val finalResult = retryingOnFailures[String][Id](
        policy,
        _.toInt > 20000,
        ctx.onError
      ) {
        ctx.attempts = ctx.attempts + 1
        ctx.attempts.toString
      }

      assertEquals(finalResult, "10001")
      assertEquals(ctx.attempts, 10001)
      assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomeErrors should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnSomeErrors(
      policy,
      (_: String) == "one more time",
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnSomeErrors should retry only if the error is worth retrying"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnSomeErrors(
      policy,
      (_: String) == "one more time",
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Left("nope")
    }

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
    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnSomeErrors(
      policy,
      (_: String) == "one more time",
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

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

  testContext.test("retryingOnSomeErrors should retry in a stack-safe way") {
    ctx =>
      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnSomeErrors(
        policy,
        (_: String) == "one more time",
        ctx.onError
      ) {
        ctx.attempts = ctx.attempts + 1
        Left("one more time")
      }

      assertEquals(finalResult, Left("one more time"))
      assertEquals(ctx.attempts, 10001)
      assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllErrors should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnAllErrors(
      policy,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnAllErrors should retry until the policy chooses to give up"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnAllErrors(
      policy,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

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

  testContext.test("retryingOnAllErrors should retry in a stack-safe way") {
    ctx =>
      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnAllErrors(
        policy,
        ctx.onError
      ) {
        ctx.attempts = ctx.attempts + 1
        Left("one more time")
      }

      assertEquals(finalResult, Left("one more time"))
      assertEquals(ctx.attempts, 10001)
      assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailuresAndSomeErrors should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      _ == "yay",
      (_: String) == "one more time",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailuresAndSomeErrors should retry only if the error is worth retrying"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      _ == "will never happen",
      (_: String) == "one more time",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Left("nope")
    }

    assertEquals(finalResult, Left("nope"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(
      !ctx.gaveUp
    ) // false because onError is only called when the error is worth retrying
  }

  testContext.test(
    "retryingOnFailuresAndSomeErrors should retry until the policy chooses to give up due to errors"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      _ == "will never happen",
      (_: String) == "one more time",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

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
    "retryingOnFailuresAndSomeErrors should retry until the policy chooses to give up due to failures"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      _ == "yay",
      (_: String) == "one more time",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Right("boo")
    }

    assertEquals(finalResult, Right("boo"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("boo", "boo", "boo"))
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailuresAndSomeErrors should retry in a stack-safe way"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](10000)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      _ == "yay",
      (_: String) == "one more time",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(ctx.attempts, 10001)
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailuresAndAllErrors should retry until the action succeeds"
  ) { ctx =>
    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnFailuresAndAllErrors[String](
      policy,
      _ == "yay",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      if (ctx.attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("one more time", "one more time"))
    assert(!ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailuresAndAllErrors should retry until the policy chooses to give up due to errors"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnFailuresAndAllErrors[String](
      policy,
      _ == "will never happen",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

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
    "retryingOnFailuresAndAllErrors should retry until the policy chooses to give up due to failures"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnFailuresAndAllErrors[String](
      policy,
      _ == "yay",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Right("boo")
    }

    assertEquals(finalResult, Right("boo"))
    assertEquals(ctx.attempts, 3)
    assertEquals(ctx.errors.toList, List("boo", "boo", "boo"))
    assert(ctx.gaveUp)
  }

  testContext.test(
    "retryingOnFailuresAndAllErrors should retry in a stack-safe way"
  ) { ctx =>
    val policy = RetryPolicies.limitRetries[StringOr](10000)

    val finalResult = retryingOnFailuresAndAllErrors[String](
      policy,
      _ == "will never happen",
      ctx.onError,
      ctx.onError
    ) {
      ctx.attempts = ctx.attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(ctx.attempts, 10001)
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
