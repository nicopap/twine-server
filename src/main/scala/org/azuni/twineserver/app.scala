package org.azuni.twineserver.app

import com.typesafe.scalalogging.Logger
import shapeless._
import com.twitter.finagle.{Service}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.{
  Future => TFuture,
  Promise => TPromise,
  Return,
  Throw,
}
import com.twitter.io.{Reader, Buf}
import io.finch._
import io.finch.syntax._
import io.circe._
import io.circe.generic.semiauto._
import io.finch.circe._
import scala.util.{Failure, Success, Try}
import scala.concurrent.{Promise, Future, ExecutionContext}
import org.azuni.twineserver.storylocker.{
  StoryName,
  StoryLocker,
  LockId,
  StorySummary,
}

class InnexistantStory extends RuntimeException("Story not found")
class BusyStory(editor: String)
    extends RuntimeException(
      s"$editor is working on this story. Concurrent editing is not supported",
    )

object SaveChangeInput {
  implicit val saveChangeInputDecoder: Decoder[SaveChangeInput] = deriveDecoder
  implicit val saveChangeInputEncoder: Encoder[SaveChangeInput] = deriveEncoder
}
case class SaveChangeInput(lock: LockId, file: String)

object AquireInput {
  implicit val aquireInputDecoder: Decoder[AquireInput] = deriveDecoder
  implicit val aquireInputEncoder: Encoder[AquireInput] = deriveEncoder
}
case class AquireInput(user: String)

object LockInput {
  implicit val lockInputDecoder: Decoder[LockInput] = deriveDecoder
  implicit val lockInputEncoder: Encoder[LockInput] = deriveEncoder
}
case class LockInput(lock: LockId)

object App {
  def init(): App = new App(StoryLocker.init())
}
class App(private val storyLocker: StoryLocker) {
  private def encodeErrorList(es: List[Exception]): Json = {
    val messages = es.map(x => Json.fromString(x.getMessage))
    Json.obj("errors" -> Json.arr(messages: _*))
  }

  implicit val storyNameDecoder: DecodePath[StoryName] =
    DecodePath.instance(s => Some(StoryName.fromUnencoded(s)))

  implicit class RichTFuture[A](f: TFuture[A]) {
    def asScala(implicit e: ExecutionContext): Future[A] = {
      val p: Promise[A] = Promise()
      f.respond {
        case Return(value)    => p.success(value)
        case Throw(exception) => p.failure(exception)
      }
      p.future
    }
  }

  implicit class RichFuture[A](f: Future[A]) {
    def asTwitter(implicit e: ExecutionContext): TFuture[A] = {
      val p: TPromise[A] = new TPromise[A]
      f.onComplete {
        case Success(value)     => p.setValue(value)
        case Failure(exception) => p.setException(exception)
      }
      p
    }
  }

  implicit val encodeException: Encoder[Exception] = Encoder.instance({
    case e: io.finch.Errors => encodeErrorList(e.errors.toList)
    case e: io.finch.Error =>
      e.getCause match {
        case e: io.circe.Errors =>
          encodeErrorList(e.errors.toList)
        case err =>
          Json.obj("message" -> Json.fromString(e.getMessage))
      }
    case e: Exception => Json.obj("message" -> Json.fromString(e.getMessage))
  })

  private val logger = Logger("twine-server")

  private def adaptFn[Out](
      fn: (LockId) => Out,
  ): StoryName :: LockInput :: HNil => Output[Out] = {
    case _ :: LockInput(a) :: HNil => Ok(fn(a))
  }

  /// Get the list of available stories, and their status
  def listAvailable: Endpoint[List[StorySummary]] =
    get("stories").mapOutput(_ => Ok(storyLocker.all.to))

  /// Get the content of a given story
  def getContents: Endpoint[String] =
    get("stories" :: path[StoryName])
      .mapOutput(s => {
        logger.debug(s"attempt to read $s")
        storyLocker.get(s) match {
          case Success(file) => Ok(file.contentAsString)
          case Failure(err)  => NotFound(new Exception(err))
        }
      })

  def aquireLock: Endpoint[LockId] =
    ("stories" :: path[StoryName] :: "open" :: jsonBody[AquireInput])
      .mapOutput({
        case story :: AquireInput(user) :: HNil =>
          storyLocker.aquire(story, user) match {
            case Left(err) => BadRequest(err)
            case Right(id) => Ok(id)
          }
      })

  def releaseLock: Endpoint[Unit] =
    ("stories" :: path[StoryName] :: "close" :: jsonBody[LockInput])
      .mapOutput(adaptFn(storyLocker.release(_)))

  def keepOpen: Endpoint[Unit] =
    ("stories" :: path[StoryName] :: "keepup" :: jsonBody[LockInput])
      .mapOutput(adaptFn(storyLocker.refresh(_)))

  def saveChanges: Endpoint[Unit] =
    ("stories" :: path[StoryName] :: "save" :: jsonBody[SaveChangeInput])
      .mapOutput({
        case name :: SaveChangeInput(lock, file) :: HNil =>
          handleSaveChanges(name, lock, file.toCharArray().map(_.toByte))
      })

  def handleSaveChanges(
      name: StoryName,
      target: LockId,
      blob: Array[Byte],
  ): Output[Unit] =
    storyLocker.update(target, blob) match {
      case Right(()) => Ok(())
      case Left(exc) => BadRequest(exc)
    }

  private class Logged[A](inner: Endpoint[A]) extends Endpoint[A] {
    def apply(input: Input): Endpoint.Result[A] = {
      val trace   = input.request.path
      val method  = input.request.method
      val content = Buf.Utf8.unapply(input.request.content)
      logger.debug(s"$method $trace: $content")
      inner.apply(input)
    }
  }
  def toService: Service[Request, Response] =
    Bootstrap
      .serve[Application.Json](
        new Logged(
          listAvailable
            :+: aquireLock
            :+: releaseLock
            :+: keepOpen
            :+: saveChanges
            :+: getContents,
        ),
      )
      .toService
}
