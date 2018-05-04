package retry

import cats.Id
import cats.syntax.monoid._
import org.scalatest.FlatSpec

import scala.concurrent.duration._

class RetryPolicySpec extends FlatSpec {

  behavior of "Monoid append"

  it should "give up if either of the composed policies decides to give up" in {
    val alwaysGiveUp = RetryPolicy.lift[Id](_ => GiveUp)
    val alwaysRetry  = RetryPolicies.constantDelay[Id](1.second)

    assert(
      (alwaysGiveUp |+| alwaysRetry)
        .decideNextRetry(RetryStatus.NoRetriesYet) == GiveUp)
    assert(
      (alwaysRetry |+| alwaysGiveUp)
        .decideNextRetry(RetryStatus.NoRetriesYet) == GiveUp)
  }

  it should "choose the maximum of the delays if both of the composed policies decides to retry" in {
    val delayOneSecond = RetryPolicy.lift[Id](_ => DelayAndRetry(1.second))
    val delayTwoSeconds =
      RetryPolicy.lift[Id](_ => DelayAndRetry(2.seconds))

    assert(
      (delayOneSecond |+| delayTwoSeconds).decideNextRetry(
        RetryStatus.NoRetriesYet) == DelayAndRetry(2.seconds))
    assert(
      (delayTwoSeconds |+| delayOneSecond).decideNextRetry(
        RetryStatus.NoRetriesYet) == DelayAndRetry(2.seconds))
  }

}
