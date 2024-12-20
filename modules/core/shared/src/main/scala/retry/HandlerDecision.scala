package retry

/** The result of inspecting a result or error and deciding what to do next
  */
enum HandlerDecision[+FA]:
  /** We are finished, either because the action returned a successful value, or because it raised an error so
    * heinous we don't want to retry.
    */
  case Stop

  /** Try the same action again, as long as the retry policy says it's OK to continue.
    */
  case Continue

  /** Switch to a new action for subsequent retries, as long as the retry policy says it's OK to continue.
    */
  case Adapt(newAction: FA)
