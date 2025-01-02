---
layout: docs
title: Adaptation
---

# Adaptation

When faced with a failure or an error, stubbornly retrying the same action is
not always useful. Sometimes you want to _adapt_ to the situation and try a
different action.

cats-retry supports this kind of adaptation.

When using any of the combinators, you pass in a result handler. This handler
can inspect the result of the action (or the error that the action raised,
depending on the combinator), perform any necessary logging, then decide what to
do next:
* `Stop`, either because the result is a successful one, so there is no need to
  retry, or because the error was so bad it's not worth retrying
* `Continue`, meaning to keep retrying, assuming the retry policy agrees to
* `Adapt` to a new action. This action will be used on subsequent retries.

## Example: DynamoDB batch write

DynamoDB provides an API operation called
[`BatchWriteItem`](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html)
for efficiently inserting/updating multiple DB records in bulk, across one or
more DB tables.

The input to this operation is a map with the table names as keys, and lists of
write requests as values:

```scala
Map(
  "TableA" -> List(
    <write item 1>,
    <write item 2>,
    ...
  ),
  "TableB" -> List(
    <write item 3>,
    <write item 4>,
    ...
  )
)
```

Executing a `BatchWriteItem` operation can result in *partial* failure: you
attempted to write 10 records, 7 succeeded and 3 failed. In this case, you
usually want to retry *only* the 3 items that failed. Writing the whole batch of
10 again would be wasteful.

Of course, the second attempt to write the 3 failed items could also partially
fail: perhaps 2 succceeded this time, but 1 item failed again.

The DynamoDB API response helpfully contains a field called `UnprocessedItems`,
which is a collection of all the failed write requests, in just the right format
for sending in a subsequent `BatchWriteItem` request.

So we want to iterate as follows:

```plaintext
remaining items = the whole batch
while size(remaining items) > 0:
  1. attempt to write all remaining items
  2. remaining items = UnprocessedItems in the API response
```

AWS strongly recommends using an exponential backoff algorithm to insert delays
between the iterations of this loop, and we probably want to limit the total
number of retries, so we don't get stuck retrying forever in case of AWS issues.
This sounds like a perfect use case for cats-retry!

Let's assume we have a Scala DynamoDB client that simply wraps the AWS Java SDK
in Cats Effect `IO`:

```scala mdoc:silent
val ddbClient = util.DDBClient()
```

and we have a helper method that turns a Java collection of write requests into
a `BatchWriteItemRequest`:

```scala mdoc:silent
import software.amazon.awssdk.services.dynamodb.model.*
import java.util.List as JList
import java.util.Map as JMap

def buildRequest(writeRequests: JMap[String, JList[WriteRequest]]): BatchWriteItemRequest = 
  BatchWriteItemRequest.builder()
    .requestItems(writeRequests)
    .build()
```

and a batch of write requests that we want to perform:

```scala mdoc:silent
// In a real application, this batch would not be empty!
val writeRequests: JMap[String, JList[WriteRequest]] = JMap.of()
```

Let's define a result handler to encode the retry logic we wrote in pseudocode above:

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import retry.*

val handler: ValueHandler[IO, BatchWriteItemResponse] =
  (response: BatchWriteItemResponse, details: RetryDetails) =>
    if response.hasUnprocessedItems() then
      // build a new BatchWriteItemRequest to retry only the requests that failed
      val updatedRequest = buildRequest(response.unprocessedItems())

      // adapt our action to execute the updated request next time
      val updatedAction = ddbClient.batchWriteItem(updatedRequest)
      IO.pure(HandlerDecision.Adapt(updatedAction))
    else
      // all the write requests succeeded, so we are done
      IO.pure(HandlerDecision.Stop)
```

We also need a retry policy. We'll use exponential backoff, as recommended by
AWS, and limit it to 5 retries:

```scala mdoc:silent
import RetryPolicies.*
import scala.concurrent.duration.*

val policy = limitRetries[IO](5) join exponentialBackoff[IO](10.milliseconds)
```

Our initial action will attempt to write the entire batch:

```scala mdoc:silent
val initialRequest = buildRequest(writeRequests)
val action = ddbClient.batchWriteItem(initialRequest)
```

Now we have all the pieces we need to call `retryingOnFailures`:

```scala mdoc:silent
val io: IO[BatchWriteItemResponse] =
  retryingOnFailures(policy, handler)(action)
```

To recap, executing this `IO` will:
* first attempt to execute the entire batch of write requests using `BatchWriteItem`
* retry on partial failure, adapting the action to retry only the failed write requests
* iterate until there are no more failed write requests
* retry up to 5 times, with an exponentially increasing delay between retries

```scala mdoc
io.unsafeRunSync()
```

