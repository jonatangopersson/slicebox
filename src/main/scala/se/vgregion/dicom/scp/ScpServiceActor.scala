package se.vgregion.dicom.scp

import java.nio.file.Path
import java.util.concurrent.Executors

import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive

import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomProtocol._

class ScpServiceActor(dbProps: DbProps, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScpDataDAO(dbProps.driver)

  setupDb()
  setupScps()

  val executor = Executors.newCachedThreadPool()

  override def postStop() {
    executor.shutdown()
  }

  def receive = LoggingReceive {

    case msg: ScpRequest => msg match {

      case AddScp(scpData) =>
        val id = scpDataToId(scpData)
        context.child(id) match {
          case Some(actor) =>
            sender ! ScpAdded(scpData)
          case None =>

            addScp(scpData)

            context.actorOf(ScpActor.props(scpData, executor), id)

            sender ! ScpAdded(scpData)

        }

      case RemoveScp(scpData) =>
        val id = scpDataToId(scpData)
        context.child(id) match {
          case Some(actor) =>

            removeScp(scpData)

            actor ! PoisonPill

            sender ! ScpRemoved(scpData)

          case None =>
            sender ! ScpRemoved(scpData)
        }

      case GetScpDataCollection =>
        val scps = getScps()
        sender ! ScpDataCollection(scps)

    }

    case msg: DatasetReceivedByScp =>
      context.parent ! msg

  }

  def scpDataToId(scpData: ScpData) = scpData.name

  def addScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.insert(scpData)
    }

  def removeScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.removeByName(scpData.name)
    }

  def getScps() =
    db.withSession { implicit session =>
      dao.list
    }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def setupScps() =
    db.withTransaction { implicit session =>
      val scps = dao.list
      scps foreach (scpData => context.actorOf(ScpActor.props(scpData, executor), scpDataToId(scpData)))
    }

}

object ScpServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new ScpServiceActor(dbProps, storage))
}