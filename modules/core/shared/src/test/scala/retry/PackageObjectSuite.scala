package retry

import cats.Id
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class PackageObjectSuite extends FunSuite:
  type StringOr[A] = Either[String, A]

  implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

  private class TestContext:
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

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

    val policy = RetryPolicies.constantDelay[Id](1.second)

    val sleeps = ArrayBuffer.empty[FiniteDuration]

    implicit val dummySleep: Sleep[Id] =
      (delay: FiniteDuration) => sleeps.append(delay)

    val finalResult = retryingOnFailures[String][Id](
      policy,
      _.toInt > 3,
      onErrorId
    ) {
      attempts = attempts + 1
      attempts.toString
    }

    assertEquals(finalResult, "4")
    assertEquals(attempts, 4)
    assertEquals(errors.toList, List("1", "2", "3"))
    assertEquals(delays.toList, List(1.second, 1.second, 1.second))
    assertEquals(sleeps.toList, delays.toList)
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnFailures - retry until the policy chooses to give up") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[Id](2)

    implicit val dummySleep: Sleep[Id] = _ => ()

    val finalResult = retryingOnFailures[String][Id](
      policy,
      _.toInt > 3,
      onErrorId
    ) {
      attempts = attempts + 1
      attempts.toString
    }

    assertEquals(finalResult, "3")
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("1", "2", "3"))
    assertEquals(delays.toList, List(Duration.Zero, Duration.Zero))
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailures - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[Id](10000)

    implicit val dummySleep: Sleep[Id] = _ => ()

    val finalResult = retryingOnFailures[String][Id](
      policy,
      _.toInt > 20000,
      onErrorId
    ) {
      attempts = attempts + 1
      attempts.toString
    }

    assertEquals(finalResult, "10001")
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnSomeErrors - retry until the action succeeds") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnSomeErrors(
      policy,
      (s: String) => Right(s == "one more time"),
      onError
    ) {
      attempts = attempts + 1
      if attempts < 3 then Left("one more time")
      else Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnSomeErrors - retry only if the error is worth retrying") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnSomeErrors(
      policy,
      (s: String) => Right(s == "one more time"),
      onError
    ) {
      attempts = attempts + 1
      if attempts < 3 then Left("one more time")
      else Left("nope")
    }

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

    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnSomeErrors(
      policy,
      (s: String) => Right(s == "one more time"),
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 3)
    assertEquals(
      errors.toList,
      List("one more time", "one more time", "one more time")
    )
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnSomeErrors - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[StringOr](10000)

    val finalResult = retryingOnSomeErrors(
      policy,
      (s: String) => Right(s == "one more time"),
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnAllErrors - retry until the action succeeds") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnAllErrors(
      policy,
      onError
    ) {
      attempts = attempts + 1
      if attempts < 3 then Left("one more time")
      else Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnAllErrors - retry until the policy chooses to give up") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnAllErrors(
      policy,
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 3)
    assertEquals(
      errors.toList,
      List("one more time", "one more time", "one more time")
    )
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnAllErrors - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[StringOr](10000)

    val finalResult = retryingOnAllErrors(
      policy,
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailuresAndSomeErrors - retry until the action succeeds") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      s => Right(s == "yay"),
      (s: String) => Right(s == "one more time"),
      onError,
      onError
    ) {
      attempts = attempts + 1
      if attempts < 3 then Left("one more time")
      else Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnFailuresAndSomeErrors - retry only if the error is worth retrying") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      s => Right(s == "will never happen"),
      (s: String) => Right(s == "one more time"),
      onError,
      onError
    ) {
      attempts = attempts + 1
      if attempts < 3 then Left("one more time")
      else Left("nope")
    }

    assertEquals(finalResult, Left("nope"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(
      gaveUp,
      false // false because onError is only called when the error is worth retrying
    )
  }

  fixture.test("retryingOnFailuresAndSomeErrors - retry until the policy chooses to give up due to errors") {
    context =>
      import context.*

      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        s => Right(s == "will never happen"),
        (s: String) => Right(s == "one more time"),
        onError,
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assertEquals(finalResult, Left("one more time"))
      assertEquals(attempts, 3)
      assertEquals(
        errors.toList,
        List("one more time", "one more time", "one more time")
      )
      assertEquals(gaveUp, true)
  }

  fixture.test(
    "retryingOnFailuresAndSomeErrors - retry until the policy chooses to give up due to failures"
  ) { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      s => Right(s == "yay"),
      (s: String) => Right(s == "one more time"),
      onError,
      onError
    ) {
      attempts = attempts + 1
      Right("boo")
    }

    assertEquals(finalResult, Right("boo"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("boo", "boo", "boo"))
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailuresAndSomeErrors - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[StringOr](10000)

    val finalResult = retryingOnFailuresAndSomeErrors[String](
      policy,
      s => Right(s == "yay"),
      (s: String) => Right(s == "one more time"),
      onError,
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailuresAndSomeErrors - should fail fast if isWorthRetrying's effect fails") {
    context =>
      import context.*

      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        s => Right(s == "yay, but it doesn't matter"),
        (_: String) => Left("isWorthRetrying failed"): StringOr[Boolean],
        onError,
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assertEquals(finalResult, Left("isWorthRetrying failed"))
      assertEquals(attempts, 1)
      assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnFailuresAndAllErrors - retry until the action succeeds") { context =>
    import context.*

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnFailuresAndAllErrors[String](
      policy,
      s => Right(s == "yay"),
      onError,
      onError
    ) {
      attempts = attempts + 1
      if attempts < 3 then Left("one more time")
      else Right("yay")
    }

    assertEquals(finalResult, Right("yay"))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List("one more time", "one more time"))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnFailuresAndAllErrors - retry until the policy chooses to give up due to errors") {
    context =>
      import context.*

      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        s => Right(s == "will never happen"),
        onError,
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assertEquals(finalResult, Left("one more time"))
      assertEquals(attempts, 3)
      assertEquals(
        errors.toList,
        List("one more time", "one more time", "one more time")
      )
      assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailuresAndAllErrors - retry until the policy chooses to give up due to failures") {
    context =>
      import context.*

      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        s => Right(s == "yay"),
        onError,
        onError
      ) {
        attempts = attempts + 1
        Right("boo")
      }

      assertEquals(finalResult, Right("boo"))
      assertEquals(attempts, 3)
      assertEquals(errors.toList, List("boo", "boo", "boo"))
      assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailuresAndAllErrors - retry in a stack-safe way") { context =>
    import context.*

    val policy = RetryPolicies.limitRetries[StringOr](10000)

    val finalResult = retryingOnFailuresAndAllErrors[String](
      policy,
      s => Right(s == "will never happen"),
      onError,
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assertEquals(finalResult, Left("one more time"))
    assertEquals(attempts, 10001)
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnFailuresAndAllErrors - should fail fast if wasSuccessful's effect fails") {
    context =>
      import context.*

      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        _ => Left("an error was raised!"): StringOr[Boolean],
        onError,
        onError
      ) {
        attempts = attempts + 1
        Right("one more time")
      }

      assertEquals(finalResult, Left("an error was raised!"))
      assertEquals(attempts, 1)
      assertEquals(gaveUp, false)
  }
end PackageObjectSuite
