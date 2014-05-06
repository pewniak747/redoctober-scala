package redoctober

import akka.actor._

case class Refs(val processes: Set[ActorRef])
case class RefsAck

class Broadcast extends Actor {
  var refs: Set[ActorRef] = Set()

  def receive = {
    case Refs(processes) => {
      refs = processes
      sender ! RefsAck
    }
    case msg@_ => (refs - sender).foreach(_.tell(msg, sender))
  }
}
