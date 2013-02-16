package org.eligosource.eventsourced.journal.dynamodb

import DynamoDBJournal._
import akka.actor._
import collection.JavaConverters._
import collection.immutable.TreeMap
import com.amazonaws.services.dynamodb.AmazonDynamoDB
import com.amazonaws.services.dynamodb.model._
import com.amazonaws.services.dynamodb.model.{Key => DynamoKey}
import com.sclasen.spray.dynamodb.DynamoDBClient
import concurrent.duration.FiniteDuration
import concurrent.{Future, Await}
import java.nio.ByteBuffer
import java.util.Collections
import java.util.{List => JList, Map => JMap, HashMap => JHMap}
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.core.Message
import org.eligosource.eventsourced.core.Serialization
import org.eligosource.eventsourced.journal.common.ConcurrentWriteJournal


class DynamoDBJournal(props: DynamoDBJournalProps) extends ConcurrentWriteJournal {


  val serialization = Serialization(context.system)

  implicit def msgToBytes(msg: Message): Array[Byte] = serialization.serializeMessage(msg)

  implicit def msgFromBytes(bytes: Array[Byte]): Message = serialization.deserializeMessage(bytes)

  val channelMarker = Array(1.toByte)
  val countMarker = Array(1.toByte)

  val log = context.system.log
  log.debug("new Journal")

  def counterAtt(cntr: Long) = S(props.eventSourcedApp + Counter + cntr)

  def counterKey(cntr: Long) =
    new DynamoKey()
      .withHashKeyElement(counterAtt(cntr))

  implicit val ctx = context.system.dispatcher

  val dynamo = new DynamoDBClient(props.clientProps)

  def asyncWriteTimeout: FiniteDuration = props.operationTimeout.duration

  def asyncWriterCount: Int = props.asyncWriterCount

  def writer(id: Int) = new DynamoWriter(id)

  def replayer = new DynamoReplayer

  def storedCounter: Long = {
    val start = Long.MaxValue
    val counter = Await.result(findStoredCounter(start), props.operationTimeout.duration)
    log.debug(s"found stored counter $counter")
    counter
  }

  private def findStoredCounter(max: Long): Future[Long] = {
    val candidates = candidateKeys(max)
    val ka = new KeysAndAttributes().withKeys(candidates.values.toSeq: _*).withConsistentRead(true)
    val tables = Collections.singletonMap(props.journalTable, ka)
    val get = new BatchGetItemRequest().withRequestItems(tables)
    dynamo.sendBatchGetItem(get).flatMap {
      res =>
        val batch = mapBatch(res.getResponses.get(props.journalTable))
        val counters: List[Long] = candidates.map {
          ///find the counters associated with any found keys
          case (cnt, key) => Option(batch.get(key.getHashKeyElement)).map(_ => cnt)
        }.flatten.toList
        if (counters.size == 0) Future(0) //no counters found
        else if (counters.size == 1 && counters(0) == 1) Future(1) //one counter found
        else if (endsSequentially(counters)) Future(counters.last) // last 2 counters found are sequential so last one is highest
        else findStoredCounter(counters.last)
    }
  }

  def endsSequentially(counters: List[Long]): Boolean = {
    val two = counters.takeRight(2)
    two match {
      case a :: b :: Nil if a + 1 == b => true
      case _ => false
    }
  }


  def candidateKeys(max: Long): TreeMap[Long, DynamoKey] = {
    val increment: Long = max / 100
    (Stream.iterate(1L, 100)(i => i + increment)).map {
      i =>
        i -> counterKey(i)
    }.foldLeft(TreeMap.empty[Long, DynamoKey]) {
      case (tm, (cnt, key)) => tm + (cnt -> key)
    }
  }

  ///todo all BatchGetItem need to chek and retry for unprocessed keys before mapBatch-ing
  private def replayOut(r: ReplayOutMsgs, replayTo: Long, p: (Message) => Unit) {
    val from = r.fromSequenceNr
    val msgs = (replayTo - r.fromSequenceNr).toInt + 1
    log.debug(s"replayingOut from ${from} for up to ${msgs}")
    Stream.iterate(r.fromSequenceNr, msgs)(_ + 1)
      .map(l => new DynamoKey().withHashKeyElement(outKey(r.channelId, l))).grouped(100).foreach {
      keys =>
        log.debug("replayingOut")
        val ka = new KeysAndAttributes().withKeys(keys.asJava).withConsistentRead(true)
        val get = new BatchGetItemRequest().withRequestItems(Collections.singletonMap(props.journalTable, ka))
        val resp = Await.result(dynamo.sendBatchGetItem(get), props.operationTimeout.duration)
        val batchMap = mapBatch(resp.getResponses.get(props.journalTable))
        keys.foreach {
          key =>
            Option(batchMap.get(key.getHashKeyElement)).foreach {
              item =>
                p(msgFromBytes(item.get(Data).getB.array()))
            }
        }
    }
  }

  private def replayIn(r: ReplayInMsgs, replayTo: Long, processorId: Int, p: (Message) => Unit) {
    val from = r.fromSequenceNr
    val msgs = (replayTo - r.fromSequenceNr).toInt + 1
    log.debug(s"replayingIn from ${from} for up to ${msgs}")
    Stream.iterate(r.fromSequenceNr, msgs)(_ + 1)
      .map(l => new DynamoKey().withHashKeyElement(inKey(r.processorId, l))).grouped(100).foreach {
      keys =>
        log.debug("replayingIn")
        keys.foreach(k => log.debug(k.toString))
        val ka = new KeysAndAttributes().withKeys(keys.asJava).withConsistentRead(true)
        val get = new BatchGetItemRequest().withRequestItems(Collections.singletonMap(props.journalTable, ka))
        val resp = Await.result(dynamo.sendBatchGetItem(get), props.operationTimeout.duration)
        val batchMap = mapBatch(resp.getResponses.get(props.journalTable))
        val messages = keys.map {
          key =>
            Option(batchMap.get(key.getHashKeyElement)).map {
              item =>
                msgFromBytes(item.get(Data).getB.array())
            }
        }.flatten
        log.debug(s"found ${messages.size}")
        confirmingChannels(processorId, messages).foreach(p)
    }
  }

  def confirmingChannels(processorId: Int, messages: Stream[Message]): Stream[Message] = {
    if (messages.isEmpty) messages
    else {
      val keys = messages.map {
        message =>
          new DynamoKey()
            .withHashKeyElement(ackKey(processorId, message.sequenceNr))
      }

      val ka = new KeysAndAttributes().withKeys(keys.asJava).withConsistentRead(true)
      val get = new BatchGetItemRequest().withRequestItems(Collections.singletonMap(props.journalTable, ka))
      val response = Await.result(dynamo.sendBatchGetItem(get), props.operationTimeout.duration)
      val batchMap = mapBatch(response.getResponses.get(props.journalTable))
      val acks = keys.map {
        key =>
          Option(batchMap.get(key.getHashKeyElement)).map {
            _.keySet().asScala.filter(!DynamoKeys.contains(_)).map(_.toInt).toSeq
          }
      }

      messages.zip(acks).map {
        case (message, Some(chAcks)) => message.copy(acks = chAcks.filter(_ != -1))
        case (message, None) => message
      }

    }
  }

  def mapBatch(b: BatchResponse) = {
    val map = new JHMap[AttributeValue, JMap[String, AttributeValue]]
    b.getItems.iterator().asScala.foreach {
      item => map.put(item.get(Id), item)
    }
    map
  }


  def put(key: DynamoKey, message: Array[Byte]): PutRequest = {
    val item = new JHMap[String, AttributeValue]
    log.debug(s"put:  ${key.toString}")
    item.put(Id, key.getHashKeyElement)
    item.put(Data, B(message))
    new PutRequest().withItem(item)
  }

  def putAck(ack: WriteAck): PutRequest = {
    val item = new JHMap[String, AttributeValue]
    item.put(Id, ack.getHashKeyElement)
    item.put(ack.channelId.toString, B(channelMarker))
    new PutRequest().withItem(item)
  }


  def S(value: String): AttributeValue = new AttributeValue().withS(value)

  def N(value: Long): AttributeValue = new AttributeValue().withN(value.toString)

  def NS(value: Long): AttributeValue = new AttributeValue().withNS(value.toString)

  def B(value: Array[Byte]): AttributeValue = new AttributeValue().withB(ByteBuffer.wrap(value))

  def UB(value: Array[Byte]): AttributeValueUpdate = new AttributeValueUpdate().withAction(AttributeAction.PUT).withValue(B(value))

  def inKey(procesorId: Int, sequence: Long) = S(str(props.eventSourcedApp, "IN-", procesorId, "-", sequence)) //dont remove those dashes or else keys will be funky

  def outKey(channelId: Int, sequence: Long) = S(str(props.eventSourcedApp, "OUT-", channelId, "-", sequence))

  def ackKey(processorId: Int, sequence: Long) = S(str(props.eventSourcedApp, "ACK-", processorId, "-", sequence))

  def str(ss: Any*): String = ss.foldLeft(new StringBuilder)(_.append(_)).toString()

  implicit def inToDynamoKey(cmd: WriteInMsg): DynamoKey =
    new DynamoKey()
      .withHashKeyElement(inKey(cmd.processorId, cmd.message.sequenceNr))

  implicit def outToDynamoKey(cmd: WriteOutMsg): DynamoKey =
    new DynamoKey()
      .withHashKeyElement(outKey(cmd.channelId, cmd.message.sequenceNr))

  implicit def delToDynamoKey(cmd: DeleteOutMsg): DynamoKey =
    new DynamoKey().
      withHashKeyElement(outKey(cmd.channelId, cmd.msgSequenceNr))

  implicit def ackToDynamoKey(cmd: WriteAck): DynamoKey =
    new DynamoKey()
      .withHashKeyElement(ackKey(cmd.processorId, cmd.ackSequenceNr))

  implicit def replayInToDynamoKey(cmd: ReplayInMsgs): DynamoKey =
    new DynamoKey().withHashKeyElement(inKey(cmd.processorId, cmd.fromSequenceNr))

  implicit def replayOutToDynamoKey(cmd: ReplayOutMsgs): DynamoKey =
    new DynamoKey().withHashKeyElement(outKey(cmd.channelId, cmd.fromSequenceNr))


  class DynamoWriter(idx: Int) extends Writer {

    val dynamoWriter = new DynamoDBClient(props.clientProps)

    def executeDeleteOutMsg(cmd: DeleteOutMsg) = {
      val del: DeleteItemRequest = new DeleteItemRequest().withTableName(props.journalTable).withKey(cmd)
      dynamoWriter.sendDeleteItem(del)
    }

    def executeWriteOutMsg(cmd: WriteOutMsg) = {
      val ack = {
        if (cmd.ackSequenceNr != SkipAck)
          putAck(WriteAck(cmd.ackProcessorId, cmd.channelId, cmd.ackSequenceNr))
        else
        //write a -1 to acks so we can be assured of non-nulls on the batch get in replay    //TODO DONT NEED
          putAck(WriteAck(cmd.ackProcessorId, -1, cmd.ackSequenceNr))
      }

      batchWrite(cmd,
        put(cmd, cmd.message.clearConfirmationSettings),
        put(counterKey(cmd.message.sequenceNr), countMarker),
        ack
      )
    }

    def executeWriteInMsg(cmd: WriteInMsg) = {
      log.debug(s"batch in with counter ${cmd.message.sequenceNr}")
      batchWrite(cmd,
        put(cmd, cmd.message.clearConfirmationSettings),
        put(counterKey(cmd.message.sequenceNr), countMarker)
      )
    }

    def executeWriteAck(cmd: WriteAck) = {
      batchWrite(cmd, putAck(cmd))
    }

    def batchWrite(cmd: Any, puts: PutRequest*) = {
      log.debug("batchWrite")
      val write = new JHMap[String, JList[WriteRequest]]
      val writes = puts.map(new WriteRequest().withPutRequest(_)).asJava
      write.put(props.journalTable, writes)
      val batch = new BatchWriteItemRequest().withRequestItems(write)
      dynamoWriter.sendBatchWriteItem(batch).map(sendUnprocessedItems).map(_ => ())
    }

    def sendUnprocessedItems(result: BatchWriteItemResult): Future[(BatchWriteItemResult, List[PutItemResult])] = {
      Future.sequence {
        result.getUnprocessedItems.get(props.journalTable).asScala.map {
          w =>
            val p = new PutItemRequest().withTableName(props.journalTable).withItem(w.getPutRequest.getItem)
            dynamoWriter.sendPutItem(p)
        }
      }.map(puts => result -> puts.toList)
    }

  }

  class DynamoReplayer extends Replayer {
    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sender: ActorRef, replayTo: Long) {
      cmds.foreach(cmd => replayIn(cmd, replayTo, cmd.processorId, p(_, cmd.target)))
      sender ! ReplayDone
    }

    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sender: ActorRef, replayTo: Long) {
      replayIn(cmd, replayTo, cmd.processorId, p)
      sender ! ReplayDone
    }

    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sender: ActorRef, replayTo: Long) {
      replayOut(cmd, replayTo, p)
    }
  }


}

object DynamoDBJournal {

  val Id = "key"
  val Data = "data"
  val Counter = "COUNTER"
  val DynamoKeys = Set(Id)

  val hashKey = new KeySchemaElement().withAttributeName("key").withAttributeType("S")
  val schema = new KeySchema().withHashKeyElement(hashKey)


  def createJournal(table: String)(implicit dynamo: AmazonDynamoDB) {
    if (!dynamo.listTables(new ListTablesRequest()).getTableNames.contains(table)) {
      dynamo.createTable(new CreateTableRequest(table, schema).withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(128).withWriteCapacityUnits(128)))
      waitForActiveTable(table)
    }
  }

  def waitForActiveTable(table: String, retries: Int = 100)(implicit dynamo: AmazonDynamoDB) {
    if (retries == 0) throw new RuntimeException("Timed out waiting for creation of:" + table)
    val desc = dynamo.describeTable(new DescribeTableRequest().withTableName(table))
    if (desc.getTable.getTableStatus != "ACTIVE") {
      Thread.sleep(1000)
      println("waiting to create table")
      waitForActiveTable(table, retries - 1)
    }
  }


}



