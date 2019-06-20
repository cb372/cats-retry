package retry

import cats.{Eq, Id}
import cats.kernel.laws.discipline.BoundedSemilatticeTests
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.discipline.scalatest.Discipline

import scala.concurrent.duration.Duration

class RetryPolicyLawsSpec extends AnyFunSuite with Discipline {

  implicit val arbRetryPolicy: Arbitrary[RetryPolicy[Id]] = Arbitrary(
    for {
      delay <- Gen.choose(0, Long.MaxValue).map(Duration.fromNanos)
      decision <- Gen.oneOf(PolicyDecision.GiveUp,
                            PolicyDecision.DelayAndRetry(delay))
    } yield RetryPolicy[Id](_ => decision)
  )

  implicit val eqForRetryPolicy: Eq[RetryPolicy[Id]] = Eq.instance {
    case (a, b) =>
      // this Eq instance is pretty dodgy, but it matches the behaviour of the Arbitrary instance above:
      // the generated policies return the same decision for any RetryStatus value
      // so we can use any arbitrary value when testing them for equality.
      a.decideNextRetry(RetryStatus.NoRetriesYet) == b.decideNextRetry(
        RetryStatus.NoRetriesYet)
  }

  checkAll("BoundedSemilattice[RetryPolicy]",
           BoundedSemilatticeTests[RetryPolicy[Id]].boundedSemilattice)

}
