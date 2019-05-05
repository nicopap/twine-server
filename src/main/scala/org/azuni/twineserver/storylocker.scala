package org.azuni.twineserver.storylocker

import scala.collection.mutable.Map
import scala.util.{Random, Try, Success, Failure}
import better.files._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import biz.neumann.url.NiceURLCodecs.{URLEncodedString, DecodedURLString}
import scala.math.Ordered

class UnreadableStory(name: String)
    extends RuntimeException(s"$name is unreadable")
class AlreadyEditing(name: String)
    extends RuntimeException(s"$name is already editing the same file")
class InnexistantLock extends RuntimeException("The lock doesn't exist")
class ExpiredLock     extends RuntimeException("The lock has expired")
class ImpossibleStory extends RuntimeException("The story can't be saved")

/** A `Duration` encodes a difference of time between two `Instant`s.
  */
object Duration {
  def fromMinutes(minutes: Long): Duration = Duration(minutes * 60000)
  def fromSeconds(seconds: Long): Duration = Duration(seconds * 1000)
}
case class Duration(inner: Long) extends Ordered[Duration] {
  def inMinutes: Long = inner / 60000

  override def compare(that: Duration): Int = inner.compare(that.inner)
}

/** An `Instant` is a set moment in time.
  */
object Instant {
  def now(): Instant =
    Instant(scala.compat.Platform.currentTime)
}
case class Instant(inner: Long) {

  /** the `Duration` between this `Instant` and now.
    */
  def elapsed(): Duration =
    Duration(scala.compat.Platform.currentTime - inner)
}

object StoryName {
  def fromEncoded(inner: URLEncodedString): StoryName = StoryName(inner)
  def fromUnencoded(s: DecodedURLString): StoryName =
    StoryName(s.encode("utf-8"))

  implicit val storyNameDecoder: Decoder[StoryName] =
    Decoder.decodeString.emap { str =>
      Right(StoryName.fromUnencoded(str))
    }
  implicit val storyNameEncoder: Encoder[StoryName] =
    Encoder.encodeString.contramap[StoryName](_.inner.decode("utf-8"))
}
case class StoryName(inner: URLEncodedString) {
  def urlSafe: String = inner.encodedString
}

/** A random value of 6 bytes used as a session token.
  */
object LockId {
  def init(): LockId = {
    var bytes: Array[Byte] = Array(0, 0, 0, 0, 0, 0)
    Random.nextBytes(bytes)
    LockId(bytes.toList)
  }
  def fromString(s: String): Option[LockId] =
    base64.Decode.urlSafe(s).toOption.map(s => LockId(s.toList))

  implicit val lockIdDecoder: Decoder[LockId] =
    Decoder.decodeString.emap { str =>
      LockId.fromString(str).toRight("Invalid lock id")
    }
  implicit val lockIdEncoder: Encoder[LockId] =
    Encoder.encodeString.contramap[LockId](_.toString())
}
case class LockId(inner: List[Byte]) {
  override def toString(): String =
    new String(base64.Encode.urlSafe(inner.toArray))
}

object StorySummary {
  implicit val storySummaryDecoder: Decoder[StorySummary] = deriveDecoder
  implicit val storySummaryEncoder: Encoder[StorySummary] = deriveEncoder
}
case class StorySummary(name: StoryName, editor: Option[String])

/** Manages files available for editing.
  */
object StoryLocker {
  def init(): StoryLocker = new StoryLocker(Map.empty, Map.empty)
}
class StoryLocker(
    locks: Map[LockId, (StoryName, ExpiringLock[File])],
    editors: Map[StoryName, (LockId, String)],
) {
  private def openDir(dirName: String): Option[File] =
    Try(File(dirName)).toOption
      .flatMap(s => if (s.isDirectory) Some(s) else None)

  def get(storyName: StoryName): Try[File] =
    Try(file"stories" / storyName.urlSafe).flatMap { s =>
      if (s.isReadable) {
        Success(s)
      } else {
        Failure(new UnreadableStory(storyName.urlSafe))
      }
    }

  def update(
      lockId: LockId,
      fileContent: Array[Byte],
  ): Either[Exception, Unit] =
    for {
      storyAndLock     <- locks.get(lockId) toRight (new InnexistantLock)
      fileAndRefreshed <- storyAndLock._2.get() toRight (new ExpiredLock)
    } yield {
      locks.update(lockId, (storyAndLock._1, fileAndRefreshed._2))
      fileAndRefreshed._1.writeByteArray(fileContent)
      Right(())
    }

  /** Remove from `editors` table the editors whos lock already expired
    */
  def dropReleased(): Unit =
    locks.foreach({
      case (lockId, (storyName, lock)) =>
        if (lock.isExpired) {
          editors.remove(storyName)
        },
    })

  def all: Iterator[StorySummary] = {
    dropReleased()
    openDir("stories").iterator.flatMap(_.list.map(store => {
      val name   = StoryName.fromEncoded(store.name)
      val editor = editors.get(name).map(_._2)
      new StorySummary(name, editor)
    }))
  }

  def release(lockId: LockId): Either[Unit, Unit] =
    for {
      lock <- locks.remove(lockId).map(_._2).toRight(())
      file <- lock.get().map(_._1).toRight(())
      _    <- editors.remove(StoryName.fromEncoded(file.name)).toRight(())
    } yield Right(())

  def aquire(storyName: StoryName, user: String): Either[Exception, LockId] = {
    val createFile = () =>
      try {
        val story = file"stories" / storyName.urlSafe
        story.touch()
        val newLock   = ExpiringLock.init(story, Duration.fromMinutes(10))
        val newLockId = LockId.init()
        locks.update(newLockId, (storyName, newLock))
        editors.update(storyName, (newLockId, user))
        Right(newLockId)
      } catch {
        case e: Throwable => Left(new ImpossibleStory)
      }
    editors.get(storyName) match {
      case Some((id, oldEditor)) if locks.get(id).flatMap(_._2.get).nonEmpty =>
        Left(new AlreadyEditing(oldEditor))
      case Some((lockId, _)) => {
        locks.remove(lockId)
        createFile()
      }
      case None => createFile()
    }
  }

  def refresh(lockId: LockId): Either[Unit, Unit] =
    for {
      storyAndLock <- locks.get(lockId) toRight (())
      refreshed    <- storyAndLock._2.refresh() toRight (())
    } yield Right(locks.update(lockId, (storyAndLock._1, refreshed)))
}

private object ExpiringLock {
  def init[T](inner: T, timeout: Duration): ExpiringLock[T] =
    new ExpiringLock(inner, Instant.now, timeout)
}

/** A container with content only accessible for a set amount of time.
  */
private case class ExpiringLock[T](
    inner: T,
    lastRefresh: Instant,
    timeout: Duration,
) {
  def withAccess[U](onSuccess: () => U): Option[U] = {
    if (isExpired) None else Some(onSuccess())
  }

  def refresh(): Option[ExpiringLock[T]] =
    withAccess(() => {
      val now = Instant.now()
      new ExpiringLock(inner, now, timeout)
    })

  def get(): Option[(T, ExpiringLock[T])] =
    withAccess(() => {
      val now = Instant.now()
      (inner, new ExpiringLock(inner, now, timeout))
    })

  /** Returns whether the lock is expired
    */
  def isExpired: Boolean = lastRefresh.elapsed() > timeout
}
