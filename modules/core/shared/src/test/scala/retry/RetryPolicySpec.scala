package retry

import cats.{Id, catsInstancesForId}
import cats.syntax.semigroup._
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration._

class RetryPolicySpec extends AnyFlatSpec {
  behavior of "BoundedSemilattice append"

  it should "give up if either of the composed policies decides to give up" in {
    val alwaysGiveUp = RetryPolicy.lift[Id](_ => PolicyDecision.GiveUp)
    val alwaysRetry  = RetryPolicies.constantDelay[Id](1.second)

    assert(
      (alwaysGiveUp |+| alwaysRetry)
        .decideNextRetry(RetryStatus.NoRetriesYet) == PolicyDecision.GiveUp
    )
    assert(
      (alwaysRetry |+| alwaysGiveUp)
        .decideNextRetry(RetryStatus.NoRetriesYet) == PolicyDecision.GiveUp
    )
  }

  it should "choose the maximum of the delays if both of the composed policies decides to retry" in {
    val delayOneSecond =
      RetryPolicy.lift[Id](_ => PolicyDecision.DelayAndRetry(1.second))
    val delayTwoSeconds =
      RetryPolicy.lift[Id](_ => PolicyDecision.DelayAndRetry(2.seconds))

    assert(
      (delayOneSecond |+| delayTwoSeconds).decideNextRetry(
        RetryStatus.NoRetriesYet
      ) == PolicyDecision.DelayAndRetry(2.seconds)
    )
    assert(
      (delayTwoSeconds |+| delayOneSecond).decideNextRetry(
        RetryStatus.NoRetriesYet
      ) == PolicyDecision.DelayAndRetry(2.seconds)
    )
  }
}
