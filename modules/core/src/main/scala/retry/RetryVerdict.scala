package retry

import scala.concurrent.duration.FiniteDuration

sealed trait RetryVerdict

case object GiveUp                              extends RetryVerdict
case class DelayAndRetry(delay: FiniteDuration) extends RetryVerdict
