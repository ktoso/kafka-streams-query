/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafka.scala.iq
package services

import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.state.{QueryableStoreType, QueryableStoreTypes, ReadOnlyKeyValueStore, ReadOnlyWindowStore}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import com.typesafe.scalalogging.LazyLogging
import akka.actor.{ActorSystem, Scheduler}

/**
 * Abstraction that supports query from a local state store. The query supports retry semantics if 
 * invoked during Kafka Streams' rebalancing act when states may migrate across stores.
 */ 
class LocalStateStoreQuery[K, V] extends LazyLogging {

  final val MaxRetryCount = 10
  final val DelayBetweenRetries = 1.second

  /**
   * For all the following query methods, we need to implement a retry semantics when we invoke
   * `streams.store()`. This is because if the application is run in a distributed mode (multiple
   * instances), this function call can throw `InvalidStateStoreException` if state stores are being
   * migrated when the call is made. And migration is done when new instances of the application come up
   * or Kafka Streams does a rebalancing.
   *
   * In such cases we need to retry till the rebalancing is complete or we run out of retry count.
   */
  private def _retry[T](op: => T )(implicit ec: ExecutionContext, as: ActorSystem): Future[T] = {
    retry(op, DelayBetweenRetries, MaxRetryCount)(ec, as.scheduler)
  }

  def queryStateStore(streams: KafkaStreams, store: String, key: K)(implicit ex: ExecutionContext, as: ActorSystem): Future[V] = {

    val q: QueryableStoreType[ReadOnlyKeyValueStore[K, V]] = QueryableStoreTypes.keyValueStore()
    _retry(streams.store(store, q)).map(_.get(key))
  }

  def queryWindowedStateStore(streams: KafkaStreams, store: String, key: K, fromTime: Long, toTime: Long)
                             (implicit ex: ExecutionContext, as: ActorSystem): Future[List[(Long, V)]] = {

    val q: QueryableStoreType[ReadOnlyWindowStore[K, V]] = QueryableStoreTypes.windowStore()

    _retry(streams.store(store, q)).map(
      _.fetch(key, fromTime, toTime)
       .asScala
       .toList
       .map(kv => (Long2long(kv.key), kv.value)))
  }


}
