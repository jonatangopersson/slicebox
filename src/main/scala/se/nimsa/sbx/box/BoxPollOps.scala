package se.nimsa.sbx.box

import akka.actor.Scheduler
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.util.ByteString
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.dcm4che3.io.DicomStreamException
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol.{IncomingUpdated, OutgoingTransactionImage}
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.MetaDataAdded

import scala.collection.immutable.Seq
import scala.concurrent.Future

trait BoxPollOps extends BoxStreamOps with BoxJsonFormats with PlayJsonSupport {

  implicit lazy val scheduler: Scheduler = system.scheduler

  override val transferType: String = "poll"

  def storeDicomData(bytesSource: scaladsl.Source[ByteString, _], source: Source): Future[MetaDataAdded]
  def updateIncomingTransaction(transactionImage: OutgoingTransactionImage, imageId: Long, overwrite: Boolean): Future[IncomingUpdated]
  def updateBoxOnlineStatus(online: Boolean): Future[Unit]

  lazy val pullSink: Sink[Seq[OutgoingTransactionImage], Future[Seq[OutgoingTransactionImage]]] = {
    val pullPool = pool[OutgoingTransactionImage]

    Flow[Seq[OutgoingTransactionImage]]
      .mapAsync(1)(boxStatusToOnline)
      .mapConcat(identity)
      .map(createGetRequest)
      .via(pullPool)
      .map(checkResponse)
      .mapAsyncUnordered(parallelism)(storeData)
      .statefulMapConcat(boxStatusToOnline)
      .mapAsyncUnordered(parallelism)(identity)
      .statefulMapConcat(indexInTransaction)
      .mapAsyncUnordered(parallelism)(createDoneRequest)
      .via(pullPool)
      .map(checkResponse).map(_._2)
      .toMat(Sink.seq)(Keep.right)
  }

  def pullBatch(): Future[Seq[OutgoingTransactionImage]] = scaladsl.Source.fromFuture(poll(batchSize)).runWith(pullSink)

  def poll(n: Int): Future[Seq[OutgoingTransactionImage]] =
    singleRequest(pollRequest(n))
      .flatMap {
        case response if response.status == NotFound =>
          response.discardEntityBytes()
          Future.successful(Seq.empty)
        case response =>
          Unmarshal(response).to[OutgoingTransactionImage]
            .map(transactionImage => Seq(transactionImage))
            .recoverWith {
              case _: Throwable => Unmarshal(response).to[Seq[OutgoingTransactionImage]]
            }
      }

  def boxStatusToOnline(transactionImages: Seq[OutgoingTransactionImage]): Future[Seq[OutgoingTransactionImage]] =
    updateBoxOnlineStatus(online = true).map(_ => transactionImages)

  def boxStatusToOnline: () => OutgoingTransactionImage => List[Future[OutgoingTransactionImage]] = () => {
    var lastUpdate: Long = 0

    (transactionImage: OutgoingTransactionImage) => {
      val now = System.currentTimeMillis
      val out = if ((now - lastUpdate) > (retryInterval.toMillis / 3)) {
        lastUpdate = now
        updateBoxOnlineStatus(online = true).map(_ => transactionImage)
      } else
        Future.successful(transactionImage)
      out :: Nil
    }
  }

  def storeData(responseImage: (HttpResponse, OutgoingTransactionImage)): Future[OutgoingTransactionImage] =
    responseImage match {
      case (response, transactionImage) =>
        val source = Source(SourceType.BOX, box.name, box.id)
        storeDicomData(response.entity.dataBytes, source)
          .flatMap(metaData => updateTransaction(transactionImage, metaData))
          .recover {
            case _: DicomStreamException =>
              // assume exception is due to unsupported presentation context
              SbxLog.warn("Box", s"Ignoring rejected image: ${transactionImage.image.imageId}, box: ${transactionImage.transaction.boxName}")
              transactionImage
          }
    }

  def updateTransaction(transactionImage: OutgoingTransactionImage, metaData: MetaDataAdded): Future[OutgoingTransactionImage] = {
    val overwrite = !metaData.imageAdded
    updateIncomingTransaction(transactionImage, metaData.image.id, overwrite)
      .map { _ =>
        system.eventStream.publish(ImageAdded(metaData.image.id, metaData.source, overwrite))
        transactionImage
      }
  }

  def pollRequest(n: Int): HttpRequest = {
    val uri = s"${box.baseUrl}/outgoing/poll?n=$n"
    HttpRequest(method = HttpMethods.GET, uri = uri, entity = HttpEntity.Empty)
  }

  def createGetRequest(transactionImage: OutgoingTransactionImage): (HttpRequest, OutgoingTransactionImage) = {
    val uri = s"${box.baseUrl}/outgoing?transactionid=${transactionImage.transaction.id}&imageid=${transactionImage.image.id}"
    HttpRequest(method = HttpMethods.GET, uri = uri, entity = HttpEntity.Empty) -> transactionImage
  }

  def createDoneRequest(imageIndex: (OutgoingTransactionImage, Long)): Future[RequestImage] =
    imageIndex match {
      case (transactionImage, index) =>
        val updatedTransactionImage = transactionImage.update(index)
        Marshal(updatedTransactionImage).to[MessageEntity].map { entity =>
          val uri = s"${box.baseUrl}/outgoing/done"
          HttpRequest(method = HttpMethods.POST, uri = uri, entity = entity) -> updatedTransactionImage
        }
    }
}