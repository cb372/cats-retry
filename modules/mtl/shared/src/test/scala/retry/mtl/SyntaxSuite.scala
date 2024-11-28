package retry.mtl

import cats.data.EitherT
import cats.data.EitherT.catsDataMonadErrorFForEitherT
import munit.FunSuite
import retry.syntax.all.*
import retry.mtl.syntax.all.*
import retry.{RetryDetails, RetryPolicies, RetryPolicy, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class SyntaxSuite extends FunSuite:
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  private class TestContext:
    var attempts = 0
    val errors   = ArrayBuffer.empty[Either[Throwable, String]]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: Throwable, details: RetryDetails): F[Unit] =
      onErrorInternal(Left(error), details)

    def onMtlError(error: String, details: RetryDetails): F[Unit] =
      onErrorInternal(Right(error), details)

    private def onErrorInternal(
        error: Either[Throwable, String],
        details: RetryDetails
    ): F[Unit] =
      errors.append(error)
      details match
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      EitherT.pure(())

  private val fixture = FunFixture[TestContext](
    setup = _ => new TestContext,
    teardown = _ => ()
  )

  fixture.test("retryingOnSomeMtlErrors - retry until the action succeeds") { context =>
    import context.*

    given Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] =
      attempts = attempts + 1

      attempts match
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")

    val finalResult: F[String] = action
      .retryingOnSomeErrors(s => EitherT.pure(s == error), policy, onError)
      .retryingOnSomeMtlErrors[String](
        s => EitherT.pure(s == "one more time"),
        policy,
        onMtlError
      )

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List(Right("one more time"), Left(error)))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnSomeMtlErrors - retry only if the error is worth retrying") { context =>
    import context.*

    given Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] =
      attempts = attempts + 1

      attempts match
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("nope")

    val finalResult: F[String] = action
      .retryingOnSomeErrors(s => EitherT.pure(s == error), policy, onError)
      .retryingOnSomeMtlErrors[String](
        s => EitherT.pure(s == "one more time"),
        policy,
        onMtlError
      )

    assertEquals(finalResult.value, Right(Left("nope")))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List(Right("one more time"), Left(error)))
    assertEquals(
      gaveUp,
      false // false because onError is only called when the error is worth retrying
    )
  }

  fixture.test("retryingOnSomeMtlErrors - retry until the policy chooses to give up") { context =>
    import context.*

    given Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.limitRetries[F](2)

    def action: F[String] =
      attempts = attempts + 1

      attempts match
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")

    val finalResult: F[String] = action
      .retryingOnSomeErrors(s => EitherT.pure(s == error), policy, onError)
      .retryingOnSomeMtlErrors[String](
        s => EitherT.pure(s == "one more time"),
        policy,
        onMtlError
      )

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(attempts, 4)
    assertEquals(
      errors.toList,
      List(
        Right("one more time"),
        Left(error),
        Right("one more time"),
        Right("one more time")
      )
    )
    assertEquals(gaveUp, true)
  }

  fixture.test("retryingOnAllMtlErrors - retry until the action succeeds") { context =>
    import context.*

    given Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.constantDelay[F](1.second)

    def action: F[String] =
      attempts = attempts + 1

      attempts match
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")

    val finalResult: F[String] = action
      .retryingOnAllErrors(policy, onError)
      .retryingOnAllMtlErrors[String](policy, onMtlError)

    assertEquals(finalResult.value, Right(Right("yay")))
    assertEquals(attempts, 3)
    assertEquals(errors.toList, List(Right("one more time"), Left(error)))
    assertEquals(gaveUp, false)
  }

  fixture.test("retryingOnAllMtlErrors - retry until the policy chooses to give up") { context =>
    import context.*

    given Sleep[F] = _ => EitherT.pure(())

    val error                  = new RuntimeException("Boom!")
    val policy: RetryPolicy[F] = RetryPolicies.limitRetries[F](2)

    def action: F[String] =
      attempts = attempts + 1

      attempts match
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")

    val finalResult: F[String] = action
      .retryingOnAllErrors(policy, onError)
      .retryingOnAllMtlErrors[String](policy, onMtlError)

    assertEquals(finalResult.value, Right(Left("one more time")))
    assertEquals(attempts, 4)
    assertEquals(
      errors.toList,
      List(
        Right("one more time"),
        Left(error),
        Right("one more time"),
        Right("one more time")
      )
    )
    assertEquals(gaveUp, true)
  }
end SyntaxSuite
