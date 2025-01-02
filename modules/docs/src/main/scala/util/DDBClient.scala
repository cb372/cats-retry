package util

import cats.effect.IO
import software.amazon.awssdk.services.dynamodb.model.*

class DDBClient():
  def batchWriteItem(req: BatchWriteItemRequest): IO[BatchWriteItemResponse] =
    IO.pure(BatchWriteItemResponse.builder().build())
