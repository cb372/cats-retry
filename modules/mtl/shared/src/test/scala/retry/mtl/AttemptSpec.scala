package retry.mtl

import cats.data.EitherT
import cats.data.EitherT.catsDataMonadErrorFForEitherT
import cats.mtl.instances.handle._
import cats.instances.either._
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec

class AttemptSpec extends AnyFlatSpec {
  type ErrOr[A] = Either[Throwable, A]
  type F[A]     = EitherT[ErrOr, String, A]

  behavior of "Attempt.attempt"

  it should "return MonadError exception" in {
    val error    = new RuntimeException("Boom!")
    val raised   = Left(error): ErrOr[Either[String, Int]]
    val attempt  = Attempt.attempt(EitherT(raised)).value
    val expected = Attempt.Result.MonadErrorRaised(error)

    assert(attempt.right.value == Right(expected))
  }

  it should "return ApplicativeHandle error" in {
    val attempt  = Attempt.attempt(EitherT.leftT[ErrOr, Int]("My Error")).value
    val expected = Attempt.Result.ApplicativeHandleRaised("My Error")

    assert(attempt.right.value == Right(expected))
  }

  it should "return a result" in {
    val attempt  = Attempt.attempt(EitherT.rightT[ErrOr, String](42)).value
    val expected = Attempt.Result.Success(42)

    assert(attempt.right.value == Right(expected))
  }

  behavior of "Attempt.rethrow"

  it should "rethrow MonadErrorRaised error" in {
    val error = new RuntimeException("Boom!")
    val rethrow = Attempt.rethrow[F, Throwable, String, Int](
      Attempt.Result.MonadErrorRaised(error)
    )

    assert(rethrow.value.left.value == error)
  }

  it should "rethrow ApplicativeHandleRaised error" in {
    val rethrow = Attempt.rethrow[F, Throwable, String, Int](
      Attempt.Result.ApplicativeHandleRaised("My Error")
    )

    assert(rethrow.value.right.value == Left("My Error"))
  }

  it should "evaluate Success" in {
    val rethrow = Attempt.rethrow[F, Throwable, String, Int](
      Attempt.Result.Success(42)
    )

    assert(rethrow.value.right.value == Right(42))
  }
}
