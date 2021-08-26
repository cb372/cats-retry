// When the user clicks on the search box, we want to toggle the search dropdown
function displayToggleSearch(e) {
  e.preventDefault();
  e.stopPropagation();

  closeDropdownSearch(e);
  
  if (idx === null) {
    console.log("Building search index...");
    prepareIdxAndDocMap();
    console.log("Search index built.");
  }
  const dropdown = document.querySelector("#search-dropdown-content");
  if (dropdown) {
    if (!dropdown.classList.contains("show")) {
      dropdown.classList.add("show");
    }
    document.addEventListener("click", closeDropdownSearch);
    document.addEventListener("keydown", searchOnKeyDown);
    document.addEventListener("keyup", searchOnKeyUp);
  }
}

//We want to prepare the index only after clicking the search bar
var idx = null
const docMap = new Map()

function prepareIdxAndDocMap() {
  const docs = [  
    {
      "title": "Combinators",
      "url": "/cats-retry/docs/combinators.html",
      "content": "Combinators The library offers a few slightly different ways to wrap your operations with retries. Cheat sheet Combinator Context bound Handles retryingOnFailures Monad Failures retryingOnSomeErrors MonadError Errors retryingOnAllErrors MonadError Errors retryingOnFailuresAndSomeErrors MonadError Failures and errors retryingOnFailuresAndAllErrors MonadError Failures and errors More information on each combinator is provided below. retryingOnFailures To use retryingOnFailures, you pass in a predicate that decides whether you are happy with the result or you want to retry. It is useful when you are working in an arbitrary Monad that is not a MonadError. Your operation doesn’t throw errors, but you want to retry until it returns a value that you are happy with. The API (modulo some type-inference trickery) looks like this: def retryingOnFailures[M[_]: Monad: Sleep, A](policy: RetryPolicy[M], wasSuccessful: A =&gt; M[Boolean], onFailure: (A, RetryDetails) =&gt; M[Unit]) (action: =&gt; M[A]): M[A] You need to pass in: a retry policy a predicate that decides whether the operation was successful a failure handler, often used for logging the operation that you want to wrap with retries For example, let’s keep rolling a die until we get a six, using IO. import cats.effect.IO import cats.effect.unsafe.implicits.global import retry._ import scala.concurrent.duration._ val policy = RetryPolicies.constantDelay[IO](10.milliseconds) // policy: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$$Lambda$11987/1347236281@5137ae40 // ) def onFailure(failedValue: Int, details: RetryDetails): IO[Unit] = { IO(println(s\"Rolled a $failedValue, retrying ...\")) } val loadedDie = util.LoadedDie(2, 5, 4, 1, 3, 2, 6) // loadedDie: util.LoadedDie = LoadedDie(rolls = ArraySeq(2, 5, 4, 1, 3, 2, 6)) val io = retryingOnFailures(policy, (i: Int) =&gt; IO.pure(i == 6), onFailure){ IO(loadedDie.roll()) } // io: IO[Int] = FlatMap( // ioe = FlatMap( // ioe = Delay(thunk = &lt;function0&gt;), // f = retry.package$RetryingOnFailuresPartiallyApplied$$Lambda$11990/356075305@5f60302c // ), // f = cats.StackSafeMonad$$Lambda$11991/1077262642@1561b23c // ) io.unsafeRunSync() // Rolled a 2, retrying ... // Rolled a 5, retrying ... // Rolled a 4, retrying ... // Rolled a 1, retrying ... // Rolled a 3, retrying ... // Rolled a 2, retrying ... // res0: Int = 6 retryingOnSomeErrors This is useful when you are working with a MonadError[M, E] but you only want to retry on some errors. To use retryingOnSomeErrors, you need to pass in a predicate that decides whether a given error is worth retrying. The API (modulo some type-inference trickery) looks like this: def retryingOnSomeErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M], isWorthRetrying: E =&gt; M[Boolean], onError: (E, RetryDetails) =&gt; M[Unit]) (action: =&gt; M[A]) (implicit ME: MonadError[M, E]): M[A] You need to pass in: a retry policy a predicate that decides whether a given error is worth retrying an error handler, often used for logging the operation that you want to wrap with retries For example, let’s make a request for a cat gif using our flaky HTTP client, retrying only if we get an IOException. import java.io.IOException val httpClient = util.FlakyHttpClient() // httpClient: util.FlakyHttpClient = FlakyHttpClient() val flakyRequest: IO[String] = IO(httpClient.getCatGif()) // flakyRequest: IO[String] = Delay(thunk = &lt;function0&gt;) def isIOException(e: Throwable): IO[Boolean] = e match { case _: IOException =&gt; IO.pure(true) case _ =&gt; IO.pure(false) } val io = retryingOnSomeErrors( isWorthRetrying = isIOException, policy = RetryPolicies.limitRetries[IO](5), onError = retry.noop[IO, Throwable] )(flakyRequest) // io: IO[String] = FlatMap( // ioe = FlatMap( // ioe = Attempt(ioa = Delay(thunk = &lt;function0&gt;)), // f = retry.package$RetryingOnSomeErrorsPartiallyApplied$$Lambda$12035/340735956@6d2fbfac // ), // f = cats.StackSafeMonad$$Lambda$11991/1077262642@a494893 // ) io.unsafeRunSync() // res1: String = \"cute cat gets sleepy and falls asleep\" retryingOnAllErrors This is useful when you are working with a MonadError[M, E] and you want to retry on all errors. The API (modulo some type-inference trickery) looks like this: def retryingOnAllErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M], onError: (E, RetryDetails) =&gt; M[Unit]) (action: =&gt; M[A]) (implicit ME: MonadError[M, E]): M[A] You need to pass in: a retry policy an error handler, often used for logging the operation that you want to wrap with retries For example, let’s make the same request for a cat gif, this time retrying on all errors. import java.io.IOException val httpClient = util.FlakyHttpClient() // httpClient: util.FlakyHttpClient = FlakyHttpClient() val flakyRequest: IO[String] = IO(httpClient.getCatGif()) // flakyRequest: IO[String] = Delay(thunk = &lt;function0&gt;) val io = retryingOnAllErrors( policy = RetryPolicies.limitRetries[IO](5), onError = retry.noop[IO, Throwable] )(flakyRequest) // io: IO[String] = FlatMap( // ioe = FlatMap( // ioe = Attempt(ioa = Delay(thunk = &lt;function0&gt;)), // f = retry.package$RetryingOnSomeErrorsPartiallyApplied$$Lambda$12035/340735956@42ecd1e7 // ), // f = cats.StackSafeMonad$$Lambda$11991/1077262642@52aed4c9 // ) io.unsafeRunSync() // res2: String = \"cute cat gets sleepy and falls asleep\" retryingOnFailuresAndSomeErrors This is a combination of retryingOnFailures and retryingOnSomeErrors. It allows you to specify failure conditions for both the results and errors that can occur. To use retryingOnFailuresAndSomeErrors, you need to pass in predicates that decide whether a given error or result is worth retrying. The API (modulo some type-inference trickery) looks like this: def retryingOnFailuresAndSomeErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M], wasSuccessful: A =&gt; M[Boolean], isWorthRetrying: E =&gt; M[Boolean], onFailure: (A, RetryDetails) =&gt; M[Unit], onError: (E, RetryDetails) =&gt; M[Unit]) (action: =&gt; M[A]) (implicit ME: MonadError[M, E]): M[A] You need to pass in: a retry policy a predicate that decides whether the operation was successful a predicate that decides whether a given error is worth retrying a failure handler, often used for logging an error handler, often used for logging the operation that you want to wrap with retries For example, let’s make a request to an API to retrieve details for a record, which we will only retry if: A timeout exception occurs The record’s details are incomplete pending future operations import java.util.concurrent.TimeoutException val httpClient = util.FlakyHttpClient() // httpClient: util.FlakyHttpClient = FlakyHttpClient() val flakyRequest: IO[String] = IO(httpClient.getRecordDetails(\"foo\")) // flakyRequest: IO[String] = Delay(thunk = &lt;function0&gt;) def isTimeoutException(e: Throwable): IO[Boolean] = e match { case _: TimeoutException =&gt; IO.pure(true) case _ =&gt; IO.pure(false) } val io = retryingOnFailuresAndSomeErrors( wasSuccessful = (s: String) =&gt; IO.pure(s != \"pending\"), isWorthRetrying = isTimeoutException, policy = RetryPolicies.limitRetries[IO](5), onFailure = retry.noop[IO, String], onError = retry.noop[IO, Throwable] )(flakyRequest) // io: IO[String] = FlatMap( // ioe = FlatMap( // ioe = Attempt(ioa = Delay(thunk = &lt;function0&gt;)), // f = retry.package$RetryingOnFailuresAndSomeErrorsPartiallyApplied$$Lambda$12043/1215302332@12e313df // ), // f = cats.StackSafeMonad$$Lambda$11991/1077262642@4270e427 // ) io.unsafeRunSync() // res3: String = \"got some sweet details\" retryingOnFailuresAndAllErrors This is a combination of retryingOnFailures and retryingOnAllErrors. It allows you to specify failure conditions for your results as well as retry an error that occurs To use retryingOnFailuresAndAllErrors, you need to pass in a predicate that decides whether a given result is worth retrying. The API (modulo some type-inference trickery) looks like this: def retryingOnFailuresAndAllErrors[M[_]: Sleep, A, E](policy: RetryPolicy[M], wasSuccessful: A =&gt; M[Boolean], onFailure: (A, RetryDetails) =&gt; M[Unit], onError: (E, RetryDetails) =&gt; M[Unit]) (action: =&gt; M[A]) (implicit ME: MonadError[M, E]): M[A] You need to pass in: a retry policy a predicate that decides whether the operation was successful a failure handler, often used for logging an error handler, often used for logging the operation that you want to wrap with retries For example, let’s make a request to an API to retrieve details for a record, which we will only retry if: Any exception occurs The record’s details are incomplete pending future operations import java.util.concurrent.TimeoutException val httpClient = util.FlakyHttpClient() // httpClient: util.FlakyHttpClient = FlakyHttpClient() val flakyRequest: IO[String] = IO(httpClient.getRecordDetails(\"foo\")) // flakyRequest: IO[String] = Delay(thunk = &lt;function0&gt;) val io = retryingOnFailuresAndAllErrors( wasSuccessful = (s: String) =&gt; IO.pure(s != \"pending\"), policy = RetryPolicies.limitRetries[IO](5), onFailure = retry.noop[IO, String], onError = retry.noop[IO, Throwable] )(flakyRequest) // io: IO[String] = FlatMap( // ioe = FlatMap( // ioe = Attempt(ioa = Delay(thunk = &lt;function0&gt;)), // f = retry.package$RetryingOnFailuresAndSomeErrorsPartiallyApplied$$Lambda$12043/1215302332@66377667 // ), // f = cats.StackSafeMonad$$Lambda$11991/1077262642@429bfef4 // ) io.unsafeRunSync() // res4: String = \"got some sweet details\" Syntactic sugar Cats-retry includes some syntactic sugar in order to reduce boilerplate. Instead of calling the combinators and passing in your action, you can call them as extension methods. import retry.syntax.all._ // To retry until you get a value you like IO(loadedDie.roll()).retryingOnFailures( policy = RetryPolicies.limitRetries[IO](2), wasSuccessful = (i: Int) =&gt; IO.pure(i == 6), onFailure = retry.noop[IO, Int] ) val httpClient = util.FlakyHttpClient() // To retry only on errors that are worth retrying IO(httpClient.getCatGif()).retryingOnSomeErrors( isWorthRetrying = isIOException, policy = RetryPolicies.limitRetries[IO](2), onError = retry.noop[IO, Throwable] ) // To retry on all errors IO(httpClient.getCatGif()).retryingOnAllErrors( policy = RetryPolicies.limitRetries[IO](2), onError = retry.noop[IO, Throwable] ) // To retry only on errors and results that are worth retrying IO(httpClient.getRecordDetails(\"foo\")).retryingOnFailuresAndSomeErrors( wasSuccessful = (s: String) =&gt; IO.pure(s != \"pending\"), isWorthRetrying = isTimeoutException, policy = RetryPolicies.limitRetries[IO](2), onFailure = retry.noop[IO, String], onError = retry.noop[IO, Throwable] ) // To retry all errors and results that are worth retrying IO(httpClient.getRecordDetails(\"foo\")).retryingOnFailuresAndAllErrors( wasSuccessful = (s: String) =&gt; IO.pure(s != \"pending\"), policy = RetryPolicies.limitRetries[IO](2), onFailure = retry.noop[IO, String], onError = retry.noop[IO, Throwable] )"
    } ,    
    {
      "title": "Getting started",
      "url": "/cats-retry/docs/",
      "content": "Getting started Let’s start with a realistic example. In order to provide business value to our stakeholders, we need to download a textual description of a cat gif. Unfortunately we have to do this over a flaky network connection, so there’s a high probability it will fail. We’ll be working with the cats-effect IO monad, but any monad will do. import cats.effect.IO val httpClient = util.FlakyHttpClient() // httpClient: util.FlakyHttpClient = FlakyHttpClient() val flakyRequest: IO[String] = IO { httpClient.getCatGif() } // flakyRequest: IO[String] = Delay(thunk = &lt;function0&gt;) To improve the chance of successfully downloading the file, let’s wrap this with some retry logic. We’ll add dependencies on the core and cats-effect modules: val catsRetryVersion = \"3.1.0\" libraryDependencies += \"com.github.cb372\" %% \"cats-retry\" % catsRetryVersion, (Note: if you’re using Scala.js, you’ll need a %%% instead of %%.) First we’ll need a retry policy. We’ll keep it simple: retry up to 5 times, with no delay between attempts. (See the retry policies page for information on more powerful policies). import retry._ val retryFiveTimes = RetryPolicies.limitRetries[IO](5) // retryFiveTimes: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$$Lambda$11987/1347236281@5dcb8c0b // ) We’ll also provide an error handler that does some logging before every retry. Note how this also happens within whatever monad you’re working in, in this case the IO monad. import cats.effect.IO import scala.concurrent.duration.FiniteDuration import retry._ import retry.RetryDetails._ val httpClient = util.FlakyHttpClient() // httpClient: util.FlakyHttpClient = FlakyHttpClient() val flakyRequest: IO[String] = IO { httpClient.getCatGif() } // flakyRequest: IO[String] = Delay(thunk = &lt;function0&gt;) val logMessages = collection.mutable.ArrayBuffer.empty[String] // logMessages: collection.mutable.ArrayBuffer[String] = ArrayBuffer( // \"Failed to download. So far we have retried 0 times.\", // \"Failed to download. So far we have retried 1 times.\", // \"Failed to download. So far we have retried 2 times.\", // \"Failed to download. So far we have retried 3 times.\" // ) def logError(err: Throwable, details: RetryDetails): IO[Unit] = details match { case WillDelayAndRetry(nextDelay: FiniteDuration, retriesSoFar: Int, cumulativeDelay: FiniteDuration) =&gt; IO { logMessages.append( s\"Failed to download. So far we have retried $retriesSoFar times.\") } case GivingUp(totalRetries: Int, totalDelay: FiniteDuration) =&gt; IO { logMessages.append(s\"Giving up after $totalRetries retries\") } } // Now we have a retry policy and an error handler, we can wrap our `IO` inretries. import cats.effect.unsafe.implicits.global val flakyRequestWithRetry: IO[String] = retryingOnAllErrors[String]( policy = RetryPolicies.limitRetries[IO](5), onError = logError )(flakyRequest) // flakyRequestWithRetry: IO[String] = FlatMap( // ioe = FlatMap( // ioe = Attempt(ioa = Delay(thunk = &lt;function0&gt;)), // f = retry.package$RetryingOnSomeErrorsPartiallyApplied$$Lambda$12035/340735956@59d52751 // ), // f = cats.StackSafeMonad$$Lambda$11991/1077262642@58ff43b4 // ) // Let's see it in action. flakyRequestWithRetry.unsafeRunSync() // res2: String = \"cute cat gets sleepy and falls asleep\" logMessages.foreach(println) // Failed to download. So far we have retried 0 times. // Failed to download. So far we have retried 1 times. // Failed to download. So far we have retried 2 times. // Failed to download. So far we have retried 3 times. Next steps: Learn about the other available combinators Learn about the MTL combinators Learn more about retry policies Learn about the Sleep type class"
    } ,    
    {
      "title": "cats-retry",
      "url": "/cats-retry/",
      "content": "A library for retrying actions that can fail. Designed to work with cats and cats-effect or Monix. Inspired by the retry Haskell package. Get started with Getting started!"
    } ,      
    {
      "title": "MTL Combinators",
      "url": "/cats-retry/docs/mtl-combinators.html",
      "content": "MTL Combinators The cats-retry-mtl module provides two additional retry methods that operating with errors produced by Handle from cats-mtl. Installation To use cats-retry-mtl, add the following dependency to your build.sbt: val catsRetryVersion = \"3.1.0\" libraryDependencies += \"com.github.cb372\" %% \"cats-retry-mtl\" % catsRetryVersion Interaction with MonadError retry MTL retry works independently from retry.retryingOnSomeErrors. The operations retry.mtl.retryingOnAllErrors and retry.mtl.retryingOnSomeErrors evaluating retry exclusively on errors produced by Handle. Thus errors produced by MonadError are not being taken into account and retry is not triggered. If you want to retry in case of any error, you can chain the methods: fa .retryingOnAllErrors(policy, onError = retry.noop[F, Throwable]) .retryingOnAllMtlErrors[AppError](policy, onError = retry.noop[F, AppError]) retryingOnSomeErrors This is useful when you are working with an Handle[M, E] but you only want to retry on some errors. To use retryingOnSomeErrors, you need to pass in a predicate that decides whether a given error is worth retrying. The API (modulo some type-inference trickery) looks like this: def retryingOnSomeErrors[M[_]: Monad: Sleep, A, E: Handle[M, *]]( policy: RetryPolicy[M], isWorthRetrying: E =&gt; M[Boolean], onError: (E, RetryDetails) =&gt; M[Unit] )(action: =&gt; M[A]): M[A] You need to pass in: a retry policy a predicate that decides whether a given error is worth retrying an error handler, often used for logging the operation that you want to wrap with retries Example: import retry.{RetryDetails, RetryPolicies} import cats.data.EitherT import cats.effect.{Sync, IO} import cats.mtl.Handle import scala.concurrent.duration._ import cats.effect.unsafe.implicits.global type Effect[A] = EitherT[IO, AppError, A] case class AppError(reason: String) def failingOperation[F[_]: Handle[*[_], AppError]]: F[Unit] = Handle[F, AppError].raise(AppError(\"Boom!\")) def isWorthRetrying(error: AppError): Effect[Boolean] = EitherT.pure(error.reason.contains(\"Boom!\")) def logError[F[_]: Sync](error: AppError, details: RetryDetails): F[Unit] = Sync[F].delay(println(s\"Raised error $error. Details $details\")) val policy = RetryPolicies.limitRetries[Effect](2) // policy: retry.RetryPolicy[Effect] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$$Lambda$11987/1347236281@2f6e8ee8 // ) retry.mtl .retryingOnSomeErrors(policy, isWorthRetrying, logError[Effect])(failingOperation[Effect]) .value .unsafeRunTimed(1.second) // Raised error AppError(Boom!). Details WillDelayAndRetry(0 days,0,0 days) // Raised error AppError(Boom!). Details WillDelayAndRetry(0 days,1,0 days) // Raised error AppError(Boom!). Details GivingUp(2,0 days) // res1: Option[Either[AppError, Unit]] = Some( // value = Left(value = AppError(reason = \"Boom!\")) // ) retryingOnAllErrors This is useful when you are working with a Handle[M, E] and you want to retry on all errors. The API (modulo some type-inference trickery) looks like this: def retryingOnSomeErrors[M[_]: Monad: Sleep, A, E: Handle[M, *]]( policy: RetryPolicy[M], onError: (E, RetryDetails) =&gt; M[Unit] )(action: =&gt; M[A]): M[A] You need to pass in: a retry policy an error handler, often used for logging the operation that you want to wrap with retries Example: import retry.{RetryDetails, RetryPolicies} import cats.data.EitherT import cats.effect.{Sync, IO} import cats.mtl.Handle import scala.concurrent.duration._ import cats.effect.unsafe.implicits.global type Effect[A] = EitherT[IO, AppError, A] case class AppError(reason: String) def failingOperation[F[_]: Handle[*[_], AppError]]: F[Unit] = Handle[F, AppError].raise(AppError(\"Boom!\")) def logError[F[_]: Sync](error: AppError, details: RetryDetails): F[Unit] = Sync[F].delay(println(s\"Raised error $error. Details $details\")) val policy = RetryPolicies.limitRetries[Effect](2) // policy: retry.RetryPolicy[Effect] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$$Lambda$11987/1347236281@44272bd6 // ) retry.mtl .retryingOnAllErrors(policy, logError[Effect])(failingOperation[Effect]) .value .unsafeRunTimed(1.second) // Raised error AppError(Boom!). Details WillDelayAndRetry(0 days,0,0 days) // Raised error AppError(Boom!). Details WillDelayAndRetry(0 days,1,0 days) // Raised error AppError(Boom!). Details GivingUp(2,0 days) // res3: Option[Either[AppError, Unit]] = Some( // value = Left(value = AppError(reason = \"Boom!\")) // ) Syntactic sugar Cats-retry-mtl include some syntactic sugar in order to reduce boilerplate. import retry._ import cats.data.EitherT import cats.effect.{Sync, IO} import cats.syntax.functor._ import cats.syntax.flatMap._ import cats.mtl.Handle import retry.mtl.syntax.all._ import retry.syntax.all._ import scala.concurrent.duration._ import cats.effect.unsafe.implicits.global case class AppError(reason: String) class Service[F[_]: Sleep](client: util.FlakyHttpClient)(implicit F: Sync[F], AH: Handle[F, AppError]) { // evaluates retry exclusively on errors produced by Handle. def findCoolCatGifRetryMtl(policy: RetryPolicy[F]): F[String] = findCoolCatGif.retryingOnAllMtlErrors[AppError](policy, logMtlError) // evaluates retry on errors produced by MonadError and Handle def findCoolCatGifRetryAll(policy: RetryPolicy[F]): F[String] = findCoolCatGif .retryingOnAllErrors(policy, logError) .retryingOnAllMtlErrors[AppError](policy, logMtlError) private def findCoolCatGif: F[String] = for { gif &lt;- findCatGif _ &lt;- isCoolGif(gif) } yield gif private def findCatGif: F[String] = F.delay(client.getCatGif()) private def isCoolGif(string: String): F[Unit] = if (string.contains(\"cool\")) F.unit else AH.raise(AppError(\"Gif is not cool\")) private def logError(error: Throwable, details: RetryDetails): F[Unit] = F.delay(println(s\"Raised error $error. Details $details\")) private def logMtlError(error: AppError, details: RetryDetails): F[Unit] = F.delay(println(s\"Raised MTL error $error. Details $details\")) } type Effect[A] = EitherT[IO, AppError, A] val policy = RetryPolicies.limitRetries[Effect](5) // policy: RetryPolicy[Effect] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$$Lambda$11987/1347236281@dbb3162 // ) val service = new Service[Effect](util.FlakyHttpClient()) // service: Service[Effect] = repl.MdocSession$App4$Service@6c54de73 service.findCoolCatGifRetryMtl(policy).value.attempt.unsafeRunTimed(1.second) // res5: Option[Either[Throwable, Either[AppError, String]]] = Some( // value = Left(value = java.io.IOException: Failed to download) // ) service.findCoolCatGifRetryAll(policy).value.attempt.unsafeRunTimed(1.second) // Raised error java.io.IOException: Failed to download. Details WillDelayAndRetry(0 days,0,0 days) // Raised error java.io.IOException: Failed to download. Details WillDelayAndRetry(0 days,1,0 days) // Raised error java.io.IOException: Failed to download. Details WillDelayAndRetry(0 days,2,0 days) // Raised MTL error AppError(Gif is not cool). Details WillDelayAndRetry(0 days,0,0 days) // Raised MTL error AppError(Gif is not cool). Details WillDelayAndRetry(0 days,1,0 days) // Raised MTL error AppError(Gif is not cool). Details WillDelayAndRetry(0 days,2,0 days) // Raised MTL error AppError(Gif is not cool). Details WillDelayAndRetry(0 days,3,0 days) // Raised MTL error AppError(Gif is not cool). Details WillDelayAndRetry(0 days,4,0 days) // Raised MTL error AppError(Gif is not cool). Details GivingUp(5,0 days) // res6: Option[Either[Throwable, Either[AppError, String]]] = Some( // value = Right(value = Left(value = AppError(reason = \"Gif is not cool\"))) // )"
    } ,    
    {
      "title": "Retry policies",
      "url": "/cats-retry/docs/policies.html",
      "content": "Retry policies A retry policy is a function that takes in a RetryStatus and returns a PolicyDecision in a monoidal context: case class RetryPolicy[M[_]](decideNextRetry: RetryStatus =&gt; M[PolicyDecision]) The policy decision can be one of two things: we should delay for some amount of time, possibly zero, and then retry we should stop retrying and give up Built-in policies There are a number of policies available in retry.RetryPolicies, including: constantDelay (retry forever, with a fixed delay between retries) limitRetries (retry up to N times, with no delay between retries) exponentialBackoff (double the delay after each retry) fibonacciBackoff (delay(n) = (delay(n - 2) + delay(n - 1)) fullJitter (randomised exponential backoff) Policy transformers There are also a few combinators to transform policies, including: capDelay (set an upper bound on the delay between retries) limitRetriesByDelay (give up when the delay between retries reaches a certain limit) limitRetriesByCumulativeDelay (give up when the total delay reaches a certain limit) Composing policies cats-retry offers several ways of composing policies together. join First up is the join operation, it has the following semantics: If either of the policies wants to give up, the combined policy gives up. If both policies want to delay and retry, the longer of the two delays is chosen. This way of combining policies implies: That combining two identical policies result in this same policy. That the order you combine policies doesn’t affect the resulted policy. That is to say, join is associative, commutative and idempotent, which makes it a Semilattice. Furthermore, it also forms a BoundedSemilattice, as there is also a neutral element for combining with join, which is a simple policy that retries with no delay and never gives up. This makes it very useful for combining two policies with a lower bounded delay. For an example of composing policies like this, we can use join to create a policy that retries up to 5 times, starting with a 10 ms delay and increasing exponentially: import cats._ import cats.effect.IO import cats.implicits._ import scala.concurrent.duration._ import retry.RetryPolicy import retry.RetryPolicies._ val policy = limitRetries[IO](5) join exponentialBackoff[IO](10.milliseconds) meet The next operation is meet, it is the dual of join and has the following semantics: If both of the policies wants to give up, the combined policy gives up. If both policies want to delay and retry, the shorter of the two delays is chosen. Just like join, meet is also associative, commutative and idempotent, which implies: That combining two identical policies result in this same policy. That the order you combine policies doesn’t affect the resulted policy. You can use meet to compose policies where you want an upper bound on the delay. As an example the capDelay combinator is implemented using meet: def capDelay[M[_]: Applicative](cap: FiniteDuration, policy: RetryPolicy[M]): RetryPolicy[M] = policy meet constantDelay[M](cap) val neverAbove5Minutes = capDelay(5.minutes, exponentialBackoff[IO](10.milliseconds)) Retry policies form a distributive lattice, as meet and join both distribute over each other. As we feel that the join operation is more common, we use it as the canonical BoundedSemilattice instance found in the companion object. This means you can use it with the standard Cats semigroup syntax like this: limitRetries[IO](5) |+| constantDelay[IO](100.milliseconds) followedBy There is also an operator followedBy to sequentially compose policies, i.e. if the first one wants to give up, use the second one. As an example, we can retry with a 100ms delay 5 times and then retry every minute: val retry5times100millis = constantDelay[IO](100.millis) |+| limitRetries[IO](5) // retry5times100millis: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$Lambda$12416/1489436889@366ce56 // ) retry5times100millis.followedBy(constantDelay[IO](1.minute)) // res1: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$Lambda$12420/143800397@5ad38446 // ) followedBy is an associative operation and forms a Monoid with a policy that always gives up as its identity: // This is equal to just calling constantDelay[IO](200.millis) constantDelay[IO](200.millis).followedBy(alwaysGiveUp) Currently we don’t provide such an instance, as it would clash with the BoundedSemilattice instance described earlier. mapDelay, flatMapDelay The mapDelay and flatMapDelay operators work just like map and flatMap, but allow you to map on the FiniteDuration that represents the retry delay. As a simple example, it allows us to add a specific delay of 10ms on top of an existing policy: fibonacciBackoff[IO](200.millis).mapDelay(_ + 10.millis) // res3: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$Lambda$12426/736433689@6056ad4a // ) Furthermore, flatMapDelay also allows us to depend on certain effects to evaluate how to modify the delay: def determineDelay: IO[FiniteDuration] = ??? fibonacciBackoff[IO](200.millis).flatMapDelay { currentDelay =&gt; if (currentDelay &lt; 500.millis) currentDelay.pure[IO] else determineDelay } // res4: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$Lambda$12428/1788193976@2bfdb154 // ) mapK If you’ve defined a RetryPolicy[F], but you need a RetryPolicy for another effect type G[_], you can use mapK to convert from one to the other. For example, you might have defined a custom RetryPolicy[cats.effect.IO] and for another part of the app you might need a RetryPolicy[Kleisli[IO]]: import cats.effect.LiftIO import cats.data.Kleisli val customPolicy: RetryPolicy[IO] = limitRetries[IO](5).join(constantDelay[IO](100.milliseconds)) // customPolicy: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$Lambda$12416/1489436889@1e5cce03 // ) customPolicy.mapK[Kleisli[IO, String, *]](LiftIO.liftK[Kleisli[IO, String, *]]) // res5: RetryPolicy[Kleisli[IO, String, γ$0$]] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$Lambda$12430/214300656@4bb33575 // ) Writing your own policy The easiest way to define a custom retry policy is to use RetryPolicy.lift, specifying the monad you need to work in: import retry.{RetryPolicy, PolicyDecision} import java.time.{LocalDate, DayOfWeek} val onlyRetryOnTuesdays = RetryPolicy.lift[IO] { _ =&gt; if (LocalDate.now().getDayOfWeek() == DayOfWeek.TUESDAY) { PolicyDecision.DelayAndRetry(delay = 100.milliseconds) } else { PolicyDecision.GiveUp } } // onlyRetryOnTuesdays: RetryPolicy[IO] = RetryPolicy( // decideNextRetry = retry.RetryPolicy$$$Lambda$12432/1484634964@3367c6d6 // )"
    } ,      
    {
      "title": "Sleep",
      "url": "/cats-retry/docs/sleep.html",
      "content": "Sleep You can configure how the delays between retries are implemented. This is done using the Sleep type class: trait Sleep[M[_]] { def sleep(delay: FiniteDuration): M[Unit] } Out of the box, the core module provides instances for any type with an implicit cats-effect Temporal in scope. For example using cats.effect.IO: import retry.Sleep import cats.effect.IO import scala.concurrent.duration._ import cats.effect.unsafe.implicits.global Sleep[IO].sleep(10.milliseconds) Or if you’re using an abstract F[_]: import retry.Sleep import cats.effect.Temporal import scala.concurrent.duration._ def sleepWell[F[_]: Temporal] = Sleep[F].sleep(10.milliseconds) Being able to inject your own Sleep instance can be handy in tests, as you can mock it out to avoid slowing down your unit tests. alleycats-retry The alleycats-retry module provides instances for cats.Id, cats.Eval and Future that simply do a Thread.sleep(...). You can add it to your build.sbt as follows: libraryDependencies += \"com.github.cb372\" %% \"alleycats-retry\" % \"3.1.0\" To use them, simply import retry.alleycats.instances._: import retry.Sleep import retry.alleycats.instances._ import scala.concurrent.Future import scala.concurrent.duration._ import scala.concurrent.ExecutionContext.Implicits.global Sleep[Future].sleep(10.milliseconds) import retry.Sleep import retry.alleycats.instances._ import cats.Id import scala.concurrent.duration._ Sleep[Id].sleep(10.milliseconds) Note: these instances are not provided if you are using Scala.js, as Thread.sleep doesn’t make any sense in JavaScript."
    }    
  ];

  idx = lunr(function () {
    this.ref("title");
    this.field("content");

    docs.forEach(function (doc) {
      this.add(doc);
    }, this);
  });

  docs.forEach(function (doc) {
    docMap.set(doc.title, doc.url);
  });
}

// The onkeypress handler for search functionality
function searchOnKeyDown(e) {
  const keyCode = e.keyCode;
  const parent = e.target.parentElement;
  const isSearchBar = e.target.id === "search-bar";
  const isSearchResult = parent ? parent.id.startsWith("result-") : false;
  const isSearchBarOrResult = isSearchBar || isSearchResult;

  if (keyCode === 40 && isSearchBarOrResult) {
    // On 'down', try to navigate down the search results
    e.preventDefault();
    e.stopPropagation();
    selectDown(e);
  } else if (keyCode === 38 && isSearchBarOrResult) {
    // On 'up', try to navigate up the search results
    e.preventDefault();
    e.stopPropagation();
    selectUp(e);
  } else if (keyCode === 27 && isSearchBarOrResult) {
    // On 'ESC', close the search dropdown
    e.preventDefault();
    e.stopPropagation();
    closeDropdownSearch(e);
  }
}

// Search is only done on key-up so that the search terms are properly propagated
function searchOnKeyUp(e) {
  // Filter out up, down, esc keys
  const keyCode = e.keyCode;
  const cannotBe = [40, 38, 27];
  const isSearchBar = e.target.id === "search-bar";
  const keyIsNotWrong = !cannotBe.includes(keyCode);
  if (isSearchBar && keyIsNotWrong) {
    // Try to run a search
    runSearch(e);
  }
}

// Move the cursor up the search list
function selectUp(e) {
  if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index) && (index > 0)) {
      const nextIndexStr = "result-" + (index - 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Move the cursor down the search list
function selectDown(e) {
  if (e.target.id === "search-bar") {
    const firstResult = document.querySelector("li[id$='result-0']");
    if (firstResult) {
      firstResult.firstChild.focus();
    }
  } else if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index)) {
      const nextIndexStr = "result-" + (index + 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Search for whatever the user has typed so far
function runSearch(e) {
  if (e.target.value === "") {
    // On empty string, remove all search results
    // Otherwise this may show all results as everything is a "match"
    applySearchResults([]);
  } else {
    const tokens = e.target.value.split(" ");
    const moddedTokens = tokens.map(function (token) {
      // "*" + token + "*"
      return token;
    })
    const searchTerm = moddedTokens.join(" ");
    const searchResults = idx.search(searchTerm);
    const mapResults = searchResults.map(function (result) {
      const resultUrl = docMap.get(result.ref);
      return { name: result.ref, url: resultUrl };
    })

    applySearchResults(mapResults);
  }

}

// After a search, modify the search dropdown to contain the search results
function applySearchResults(results) {
  const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
  if (dropdown) {
    //Remove each child
    while (dropdown.firstChild) {
      dropdown.removeChild(dropdown.firstChild);
    }

    //Add each result as an element in the list
    results.forEach(function (result, i) {
      const elem = document.createElement("li");
      elem.setAttribute("class", "dropdown-item");
      elem.setAttribute("id", "result-" + i);

      const elemLink = document.createElement("a");
      elemLink.setAttribute("title", result.name);
      elemLink.setAttribute("href", result.url);
      elemLink.setAttribute("class", "dropdown-item-link");

      const elemLinkText = document.createElement("span");
      elemLinkText.setAttribute("class", "dropdown-item-link-text");
      elemLinkText.innerHTML = result.name;

      elemLink.appendChild(elemLinkText);
      elem.appendChild(elemLink);
      dropdown.appendChild(elem);
    });
  }
}

// Close the dropdown if the user clicks (only) outside of it
function closeDropdownSearch(e) {
  // Check if where we're clicking is the search dropdown
  if (e.target.id !== "search-bar") {
    const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
    if (dropdown) {
      dropdown.classList.remove("show");
      document.documentElement.removeEventListener("click", closeDropdownSearch);
    }
  }
}
