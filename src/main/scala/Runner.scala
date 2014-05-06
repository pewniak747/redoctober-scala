package redoctober

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Runner {
  def main(args: Array[String]) = {
    println("Starting RED October Simulation...")

    if (args.size < 2) {
      println("USAGE: run [process_count], [limit1, limit2, ...]")
      System.exit(0)
    }

    val arguments = args.map(_.toInt)

    val processesCount = arguments(0)
    val limits = arguments.drop(1)

    val actorSystem = ActorSystem()

    val broadcast = actorSystem.actorOf(Props[Broadcast])

    val processes = for { processID <- 0 to processesCount - 1 }
      yield actorSystem.actorOf(Props(new Process(processID, processesCount, limits, broadcast)))

    implicit val timeout = Timeout(5 seconds)

    broadcast.ask(Refs(processes.toSet)).onSuccess {
      case _ => processes.foreach(_ ! Start)
    }
  }
}
