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

TODO
