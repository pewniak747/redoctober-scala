package redoctober

import scala.util.Random
import scala.collection.mutable
import akka.actor._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

trait Direction
object East extends Direction { override def toString = "east" }
object West extends Direction { override def toString = "west" }

trait State
object Idle extends State
object Waiting extends State
object Busy extends State

case class Timestamp(val num: Int) {
  def increment = Timestamp(num + 1)

  def merge(other: Timestamp) = Timestamp(Math.max(num, other.num)).increment
}


trait Message {
  val timestamp: Timestamp
}
case class Request(val processID: Int, val timestamp: Timestamp) extends Message
case class Reply(val timestamp: Timestamp) extends Message
case class Enter(val processID: Int, val direction: Direction, val resourceID: Int, val timestamp: Timestamp) extends Message
case class Release(val processID: Int, val resourceID: Int, timestamp: Timestamp)

case object Start
case object Stop

class Process(val processID: Int, val processesCount: Int, val limits: Array[Int], val broadcast: ActorRef) extends Actor {

  var state: State = Idle
  var direction: Direction = West
  var timestamp = Timestamp(processID + 1)
  var resourceID: Option[Int] = None
  var busy: mutable.Map[Int, (Direction, Int)] = mutable.Map()
  var queue: mutable.ArrayBuffer[(Int, Timestamp)] = new mutable.ArrayBuffer[(Int, Timestamp)]()
  var repliesCount = 0

  def receive = {
    case Start => {
      incrementTimestamp
      start
    }
    case Stop => {
      incrementTimestamp
      stop
    }
    case Request(requestingID, requestingTimestamp) => {
      mergeTimestamp(requestingTimestamp)
      insertRequest(requestingID, requestingTimestamp)
    }
    case Reply(otherTimestamp) => {
      mergeTimestamp(otherTimestamp)
      repliesCount += 1
      attemptEnter
    }
    case Enter(enteringID, enteredDirection, enteredResource, otherTimestamp) => {
      mergeTimestamp(otherTimestamp)
      processEntered(enteringID, enteredDirection, enteredResource)
      attemptEnter
    }
    case Release(releasingID, releasedResource, otherTimestamp) => {
      mergeTimestamp(otherTimestamp)
      resourceReleased(releasedResource)
      attemptEnter
    }
  }

  def start = {
    state = Waiting
    direction = if(direction == West) East else West
    repliesCount = 0
    enqueue(processID, timestamp)
    broadcast ! Request(processID, timestamp)
  }

  def stop = {
    state = Idle
    val id = resourceID.get
    resourceID = None
    resourceReleased(id)
    println(s"Process $processID exited channel $id")
    broadcast ! Release(processID, id, timestamp)
    context.system.scheduler.scheduleOnce(randomInterval, self, Start)
  }

  def insertRequest(requestingID: Int, requestingTimestamp: Timestamp) = {
    enqueue(requestingID, requestingTimestamp)
    sender ! Reply(timestamp)
  }

  def processEntered(enteringID: Int, enteredDirection: Direction, enteredResource: Int) = {
    removeFromQueue(enteringID)
    busy.get(enteredResource) match {
      case Some((direction, count)) => busy.put(enteredResource, ((direction, count + 1)))
      case _ => busy.put(enteredResource, (enteredDirection, 1))
    }
  }


  def resourceReleased(resourceID: Int) = {
    busy.get(resourceID) match {
      case Some((direction, count)) if count > 1 => busy.put(resourceID, ((direction, count - 1)))
      case _ => busy.remove(resourceID)
    }
  }

  def attemptEnter = {
    if (repliesCount == processesCount - 1 && firstInQueue) {
      availableResource.map { (requestedID) => {
        processEntered(processID, direction, requestedID)
        state = Busy
        resourceID = Some(requestedID)
        incrementTimestamp
        broadcast ! Enter(processID, direction, requestedID, timestamp)
        context.system.scheduler.scheduleOnce(randomInterval, self, Stop)
        println(s"Process $processID entered channel $requestedID going $direction")
      } }
    }
  }

  private

  def availableResource: Option[Int] = {
    val empty = allResources diff busy.keys.toSet
    empty.headOption.orElse {
      busy.find { case (id, (dir, count)) => dir == direction && count < limits(id) }
          .map { _._1 }
    }
  }

  def incrementTimestamp = {
    timestamp = timestamp.increment
  }

  def mergeTimestamp(other: Timestamp) = {
    timestamp = timestamp.merge(other)
  }

  def enqueue(request: (Int, Timestamp)) = {
    removeFromQueue(request._1)
    val queued = queue.map { _._1 }.mkString(", ")
    queue = (queue :+ request).sorted
  }

  def removeFromQueue(removedID: Int) = {
    queue = queue.filter { case (id, _) => id != removedID  }
  }

  def firstInQueue: Boolean = {
    queue.headOption match {
      case Some((id, _)) if id == processID => true
      case _ => false
    }
  }

  def randomInterval = (Random.nextDouble() * 3).seconds

  implicit val queueOrdering = Ordering[Int].on[(Int, Timestamp)](_._2.num)

  lazy val allResources: Set[Int] = (for { i <- 0 to limits.size-1 } yield i).toSet
}
