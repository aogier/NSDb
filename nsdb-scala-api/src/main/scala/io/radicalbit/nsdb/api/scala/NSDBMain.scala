package io.radicalbit.nsdb.api.scala

import io.radicalbit.nsdb.rpc.response.RPCInsertResult

import scala.concurrent._
import scala.concurrent.duration._

object NSDBMain extends App {

  val nsdb = NSDB.connect(host = "127.0.0.1", port = 7817, db = "root")(ExecutionContext.global)

  val series = nsdb
    .namespace("registry")
    .bit("people")
    .value(10)
    .dimension("city", "Mouseton")
    .dimension("gender", "M")

  val query = nsdb
    .namespace("registry")
    .query("select * from people limit 1")

  val res: Future[RPCInsertResult] = nsdb.write(series)

  val readRes = nsdb.execute(query)

  println(Await.result(res, 10 seconds))
  println(Await.result(readRes, 10 seconds))
}
